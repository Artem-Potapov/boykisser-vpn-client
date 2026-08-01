package com.justme.xtls_core_proxy.state

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justme.xtls_core_proxy.BuildConfig
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.bridge.XrayBridge
import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.db.AppDatabase
import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.db.Subscription
import com.justme.xtls_core_proxy.failover.FailoverPoolResolver
import com.justme.xtls_core_proxy.failover.FastestConnectOutcome
import com.justme.xtls_core_proxy.failover.FastestConnectRunner
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import com.justme.xtls_core_proxy.log.LogRepository
import com.justme.xtls_core_proxy.log.VpnConnectionState
import com.justme.xtls_core_proxy.subs.SubscriptionRefreshCoordinator
import com.justme.xtls_core_proxy.vpn.XrayVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SubGroup(val subscription: Subscription, val profiles: List<Profile>)
data class ProfilesView(val manual: List<Profile>, val groups: List<SubGroup>) {
    companion object {
        val EMPTY = ProfilesView(emptyList(), emptyList())
    }
}

/** Auto-ping fires once per app launch: only when enabled and not yet consumed this process. */
fun shouldAutoPing(autoOnOpen: Boolean, alreadyConsumed: Boolean): Boolean =
    autoOnOpen && !alreadyConsumed

/**
 * The set of servers the launch-time auto-ping probes: every stored profile, taken straight from the
 * single flat `profiles` query (`ProfileDao.getAll` returns manual and subscription-imported rows
 * alike, in one atomic emission), ordered to match the list UI — manual ("My profiles") servers
 * first, then subscription servers grouped by subscription, id-ascending within each partition.
 *
 * It deliberately does NOT rebuild the set from the subscription-*grouped* view. That view assembles
 * its groups from the separate `subscriptions` Room query, which loads on its own schedule; keying
 * the once-per-launch auto-ping on that union raced the subscriptions load and, ~30% of launches,
 * spent the [AutoPingLatch] on a manual-only partial set — silently skipping every subscription
 * server. Ordering here is likewise derived only from per-row fields (`subscriptionId`, `id`), NOT
 * from the `subscriptions` list, so the probe order can't flake on whether that list has loaded yet;
 * because subscription rows import as contiguous id blocks and subscription ids ascend with creation
 * time, this reproduces the visual top-to-bottom order. The `subscriptions` list is accepted only to
 * make explicit at the call site that auto-ping is intentionally independent of it; a CASCADE foreign
 * key guarantees no orphaned profiles, so the flat set already equals the rendered union.
 */
@Suppress("UNUSED_PARAMETER")
fun autoPingServers(profiles: List<Profile>, subscriptions: List<Subscription>): List<Profile> =
    profiles.sortedWith(compareBy({ it.subscriptionId != null }, { it.subscriptionId }, { it.id }))

/**
 * Whether a Connect action may be dispatched right now. False for `CONNECTED`/`CONNECTING`/
 * `PAUSED`/`BLACKHOLED` — every state where `XrayVpnService.startVpn` would hit its "VPN already
 * running" early return and silently no-op (see that function's `shouldRestartForRecovery` guard).
 *
 * Moved here (was a private `MainActivity` function) so [FastestConnectRunner] can re-check it at
 * delivery time, not only at the menu tap that started the probe — see that class's KDoc
 * ("Delivery-time re-gate") for why a point-in-time-only check let a winner misreport which server
 * traffic was actually on.
 */
internal fun canConnect(state: VpnConnectionState): Boolean =
    state != VpnConnectionState.CONNECTED &&
        state != VpnConnectionState.CONNECTING &&
        state != VpnConnectionState.PAUSED &&
        state != VpnConnectionState.BLACKHOLED

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val dao = db.profileDao()
    private val subDao = db.subscriptionDao()

    val profiles: StateFlow<List<Profile>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val subscriptions: StateFlow<List<Subscription>> = subDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groupedProfiles: StateFlow<ProfilesView> =
        combine(profiles, subscriptions) { allProfiles, subs ->
            val bySubId = allProfiles.groupBy { it.subscriptionId }
            ProfilesView(
                manual = bySubId[null].orEmpty(),
                groups = subs.map { sub -> SubGroup(sub, bySubId[sub.id].orEmpty()) }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfilesView.EMPTY)

    /**
     * Server set for the launch-time auto-ping, routed through [autoPingServers] so it is sourced
     * from the atomic flat `profiles` query and never races the subscription groups (which the
     * grouped [groupedProfiles] view assembles from the separately-loaded `subscriptions` query).
     * Keying auto-ping on this rather than the grouped union fixes the ~30% "subscriptions skipped"
     * flake. See [autoPingServers].
     */
    val autoPingProfiles: StateFlow<List<Profile>> =
        combine(profiles, subscriptions, ::autoPingServers)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeProfileId: StateFlow<Long?> = ActiveProfileRepository.activeProfileIdFlow
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ActiveProfileRepository.getActiveProfileId(application)
        )

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    data class DnsWarning(val name: String, val rawConfig: String)

    private val _dnsWarning = MutableStateFlow<DnsWarning?>(null)
    val dnsWarning: StateFlow<DnsWarning?> = _dnsWarning.asStateFlow()

    // Single, stable admission owner for the ViewModel lifetime: created once and NEVER swapped, so
    // its cross-run in-flight de-dup and fixed native-admission ceiling survive concurrency changes
    // (the per-run concurrency is passed into each runGroup call instead of rebuilding the owner).
    private val pingCoordinator = PingCoordinator()

    private val _pingStates = MutableStateFlow<Map<Long, PingState>>(emptyMap())
    val pingStates: StateFlow<Map<Long, PingState>> = _pingStates.asStateFlow()

    val connectionState = LogRepository.connectionState

    /**
     * Owns the "Connect fastest" orchestration (job replacement/cancellation, the delivery-time
     * re-gate, busy-vs-no-response messaging) — see [FastestConnectRunner]'s KDoc for the full
     * reasoning. Wiring here is ViewModel-scoped production plumbing only; the sequencing itself is
     * framework-free and unit-tested directly against [FastestConnectRunner] (see
     * `FastestConnectRunnerTest`), not through this ViewModel.
     *
     * [FastestConnectRunner.resolvePool] is backed by [FailoverPoolResolver.resolve] — the SAME pool
     * auto-failover itself rotates through (see that object's KDoc: "the single place that changes
     * when user-curated pools land"), not a separately-derived view of the on-screen group. This
     * keeps Connect-fastest and auto-failover from silently diverging on which servers count as "the
     * pool" if/when curated pools land.
     */
    private val fastestConnectRunner = FastestConnectRunner(
        scope = viewModelScope,
        pingCoordinator = pingCoordinator,
        pingStates = _pingStates,
        resolvePool = { profile -> FailoverPoolResolver.resolve(dao, profile) },
        loadPreferences = { PingPreferences.load(getApplication()) },
        probe = { profile, prefs -> probeProfile(profile, prefs.targetUrl, prefs.timeoutMs) },
        canConnect = { canConnect(connectionState.value) },
        onOutcome = { outcome ->
            LogRepository.emitError(
                when (outcome) {
                    FastestConnectOutcome.NO_RESPONSE -> R.string.failover_connect_fastest_no_response_error
                    FastestConnectOutcome.BUSY -> R.string.failover_connect_fastest_busy_error
                    FastestConnectOutcome.STATE_CHANGED -> R.string.failover_connect_fastest_state_changed_error
                }
            )
        },
    )

    /** True while a "Connect fastest" run (see [connectFastest]) is in flight. */
    val connectFastestActive: StateFlow<Boolean> = fastestConnectRunner.active

    /**
     * The winning profile id from the most recent [connectFastest] run, or null once consumed. The
     * ViewModel deliberately does NOT call [connect] itself here: every other Connect action in this
     * app routes through MainActivity's permission-checked flow (notification permission, then
     * `VpnService.prepare()` consent) before ever calling [connect], and the ViewModel has no access
     * to that Activity-owned `ActivityResultLauncher` machinery. Surfacing the winner as state and
     * letting the Compose layer consume it through the SAME `onConnect` callback every other row uses
     * (see MainActivity's `MainScreen`) keeps that invariant intact and — because it is ViewModel
     * state observed fresh on every recomposition, not a callback captured for the life of the
     * coroutine — survives an Activity recreation (rotation) mid-probe without capturing a stale
     * Activity instance.
     *
     * [FastestConnectRunner]'s own `canConnect` re-check only guards the moment this is PRODUCED —
     * it bounds the probe's window, but not the gap after. This value can then sit unconsumed
     * indefinitely (the Compose frame clock pauses below `STARTED` while the app is backgrounded),
     * so `MainScreen` re-checks `canConnect` a SECOND time at CONSUMPTION, right before calling
     * `onConnect`, and calls [discardFastestWinner] instead when that fails (Task 10 review round 2,
     * Important). Two checks around this unbounded gap are correct, not redundant.
     */
    val fastestWinnerId: StateFlow<Long?> = fastestConnectRunner.winnerId

    /** Consumes [fastestWinnerId] so it does not re-fire on the next recomposition. */
    fun consumeFastestWinner() {
        fastestConnectRunner.consumeWinner()
    }

    /**
     * Consumes a pending [fastestWinnerId] that failed the CONSUMPTION-side re-gate — see that
     * property's doc. Reports the same `STATE_CHANGED` message [FastestConnectRunner]'s own
     * production-side re-gate uses via `onOutcome`, since from the user's perspective this is the
     * identical outcome: a winner was found but never delivered because the connection state
     * changed. Keeps that messaging in one place rather than duplicating the string lookup in
     * `MainActivity`.
     */
    fun discardFastestWinner() {
        consumeFastestWinner()
        LogRepository.emitError(R.string.failover_connect_fastest_state_changed_error)
    }

    private val defaultUserAgent = "XTLSCoreProxy/${BuildConfig.VERSION_NAME}"

    init {
        // Mirror LogRepository.errorEvents into the VM's StateFlow, resolved
        // against an Application context so the message picks up the per-app
        // locale at observation time. Cancels with viewModelScope on
        // onCleared() — no manual cleanup needed.
        viewModelScope.launch {
            LogRepository.errorEvents.collect { resId ->
                _error.value = SupportedLanguage.localize(getApplication())
                    .getString(resId)
            }
        }
        // Clear the latest error on every transition to CONNECTING. This is
        // the "user (or tile) tried to start again" signal. Mirrors the
        // semantics of the deleted `_error.value = null` in connect().
        viewModelScope.launch {
            LogRepository.connectionState
                .filter { it == VpnConnectionState.CONNECTING }
                .collect { _error.value = null }
        }
        // Prune ephemeral ping results when the profile set changes (e.g. a subscription refresh
        // replaces rows with new ids) so stale id -> PingState entries don't accumulate for ids
        // that no longer exist. The UI only reads ids present in `view`, so this is housekeeping.
        viewModelScope.launch {
            profiles.collect { current ->
                val ids = current.mapTo(HashSet()) { it.id }
                _pingStates.update { states -> states.filterKeys { it in ids } }
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun addProfile(name: String, config: String) {
        viewModelScope.launch {
            val storage = ConfigBuilder.toProfileStorageConfig(config)
            if (runCatching { ConfigBuilder.dnsDiagnosis(storage) }.getOrNull() ==
                ConfigBuilder.DnsStatus.DIRTY
            ) {
                _dnsWarning.value = DnsWarning(name = name, rawConfig = config)
                return@launch
            }
            val stored = runCatching { ConfigBuilder.makeSecureDns(storage) }.getOrDefault(storage)
            dao.insert(Profile(name = name, config = stored, sanitizedDns = false))
        }
    }

    /**
     * DEBUG-only unrestricted insert (see DebugUnrestrictedAddProfileActivity): stores [raw] verbatim
     * — NO toProfileStorageConfig, NO makeSecureDns, NO validation — then activates the new row via the
     * sanctioned ActiveProfileRepository writer so Config Sanitization / Connect target it. Lets a
     * maintainer reproduce arbitrary (incl. malformed) profile state the fail-closed ingest gates reject.
     */
    fun addRawProfile(name: String, raw: String): Job = viewModelScope.launch {
        val id = dao.insert(Profile(name = name, config = raw, sanitizedDns = false))
        ActiveProfileRepository.setActiveProfileId(getApplication(), id)
    }

    fun confirmDnsFixAndAdd() {
        val warning = _dnsWarning.value ?: return
        viewModelScope.launch {
            try {
                val secured = ConfigBuilder.makeSecureDns(
                    ConfigBuilder.toProfileStorageConfig(warning.rawConfig)
                )
                dao.insert(Profile(name = warning.name, config = secured, sanitizedDns = true))
            } catch (t: Throwable) {
                LogRepository.append("confirmDnsFixAndAdd: failed to insert secured profile: ${t.message}")
            } finally {
                _dnsWarning.value = null
            }
        }
    }

    fun dismissDnsWarning() {
        _dnsWarning.value = null
    }

    fun updateProfile(profile: Profile) {
        viewModelScope.launch {
            val storedConfig = ConfigBuilder.toProfileStorageConfig(profile.config)
            dao.update(profile.copy(config = storedConfig))
        }
    }

    fun deleteProfile(profile: Profile) {
        viewModelScope.launch {
            dao.delete(profile)
            if (ActiveProfileRepository.getActiveProfileId(getApplication()) == profile.id) {
                ActiveProfileRepository.setActiveProfileId(getApplication(), null)
            }
        }
    }

    fun addSubscription(
        name: String,
        url: String,
        userAgentOverride: String? = null,
        allowInsecureTls: Boolean = false,
        userIntervalHours: Int? = null,
        refreshAfterInsert: Boolean = false
    ): Job = viewModelScope.launch {
        val newId = subDao.insert(
            Subscription(
                name = name,
                url = url,
                userAgentOverride = userAgentOverride,
                allowInsecureTls = allowInsecureTls,
                userIntervalHours = userIntervalHours
            )
        )
        if (refreshAfterInsert) {
            SubscriptionRefreshCoordinator.refresh(
                scope = viewModelScope,
                context = getApplication(),
                subId = newId,
                activeProfileIdProvider = { ActiveProfileRepository.getActiveProfileId(getApplication()) },
                db = db,
                defaultUserAgent = defaultUserAgent
            )
        }
    }

    fun updateSubscription(sub: Subscription, refreshAfterUpdate: Boolean = false): Job =
        viewModelScope.launch {
            subDao.update(sub)
            if (refreshAfterUpdate) {
                SubscriptionRefreshCoordinator.refresh(
                    scope = viewModelScope,
                    context = getApplication(),
                    subId = sub.id,
                    activeProfileIdProvider = { ActiveProfileRepository.getActiveProfileId(getApplication()) },
                    db = db,
                    defaultUserAgent = defaultUserAgent
                )
            }
        }

    fun deleteSubscription(context: Context, sub: Subscription): Job = viewModelScope.launch {
        val activeId = ActiveProfileRepository.getActiveProfileId(getApplication())
        if (activeId != null) {
            val activeProfile = dao.getById(activeId)
            if (activeProfile?.subscriptionId == sub.id) {
                disconnect(context)
            }
        }
        subDao.delete(sub)
    }

    fun refreshSubscription(context: Context, subId: Long): Job =
        SubscriptionRefreshCoordinator.refresh(
            scope = viewModelScope,
            context = context.applicationContext,
            subId = subId,
            activeProfileIdProvider = { ActiveProfileRepository.getActiveProfileId(getApplication()) },
            db = db,
            defaultUserAgent = defaultUserAgent
        )

    fun refreshAllStaleSubscriptions(context: Context): Job = viewModelScope.launch {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val candidates = subscriptions.value.filter { sub ->
            val last = sub.lastFetchedAt
            if (last == null) return@filter true
            val intervalMs = sub.effectiveIntervalHours().toLong() * 3_600_000L
            now - last >= intervalMs
        }
        candidates.forEach { sub ->
            SubscriptionRefreshCoordinator.refresh(
                scope = viewModelScope,
                context = appContext,
                subId = sub.id,
                activeProfileIdProvider = { ActiveProfileRepository.getActiveProfileId(getApplication()) },
                db = db,
                defaultUserAgent = defaultUserAgent
            )
        }
    }

    fun connect(context: Context, profileId: Long) {
        ActiveProfileRepository.setActiveProfileId(context, profileId)

        val appContext = context.applicationContext
        val startIntent = Intent(appContext, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_START
            putExtra(XrayVpnService.EXTRA_PROFILE_ID, profileId)
        }

        // startForegroundService can throw (e.g. ForegroundServiceStartNotAllowedException if the
        // app has lost foreground state by dispatch time). Surface failures through the same
        // LogRepository error channel the UI/tile already render rather than crashing the caller.
        try {
            appContext.startForegroundService(startIntent)
        } catch (e: Exception) {
            LogRepository.emitError(R.string.vpn_start_failed_error)
            LogRepository.append("connect() failed to start service: ${e.message}")
        }
    }

    fun disconnect(context: Context) {
        val appContext = context.applicationContext
        val stopIntent = Intent(appContext, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_STOP
        }
        // The UI gate is CONNECTED/CONNECTING/PAUSED/BLACKHOLED/ERROR. In the first four the
        // service is running and this is a plain stop. ERROR is the exception and covers two very
        // different situations: a failover give-up that could not contain traffic (service RUNNING,
        // a real stop), and a dying session that has already stopSelf()'d — where this
        // startForegroundService CREATES the service rather than signalling one. That is safe only
        // because ACTION_STOP reaches stopVpn's `!shouldStop && tunInterface == null` early return
        // and stopSelf() synchronously, well inside Android's ~5 s startForeground deadline; if the
        // stop path ever awaits work before that, this becomes
        // ForegroundServiceDidNotStartInTimeException.
        // startForegroundService (not startService) is also what keeps us clear of the API 31+
        // background-start restriction if the activity loses foreground state between gate and
        // dispatch.
        appContext.startForegroundService(stopIntent)
        ActiveProfileRepository.setActiveProfileId(context, null)
    }

    fun pingTestGroup(profiles: List<Profile>) {
        if (profiles.isEmpty()) return
        val prefs = PingPreferences.load(getApplication())
        val byId = profiles.associateBy { it.id }
        viewModelScope.launch {
            // pingCoordinator is a stable val (never swapped), so capturing it inside the launched
            // coroutine is safe — the R5 hazard (dereferencing a mutable tester field) is gone.
            pingCoordinator.runGroup(
                ids = byId.keys.toList(),
                concurrency = prefs.concurrency,
                onUpdate = { id, state -> _pingStates.update { it + (id to state) } },
                // byId.getValue is safe: runGroup only invokes probe with ids from the list we
                // passed (byId.keys). Even a contract violation is caught by runGroup (Throwable ->
                // Unavailable), so a missing key cannot leave a row stuck on Testing.
                probe = { id ->
                    probeProfile(byId.getValue(id), prefs.targetUrl, prefs.timeoutMs)
                }
            )
        }
    }

    fun pingTestProfile(profile: Profile) = pingTestGroup(listOf(profile))

    /**
     * Probes [profile]'s pool (resolved via [FailoverPoolResolver.resolve], see
     * [fastestConnectRunner]'s doc) and, once the fastest successful responder is known and the
     * connection is still in a connectable state, surfaces it via [fastestWinnerId] rather than
     * connecting directly (see that property's doc for why). All sequencing — job replacement, the
     * delivery-time re-gate, cancellation cleanup, busy-vs-no-response messaging — lives in
     * [FastestConnectRunner]; this is a thin delegation.
     */
    fun connectFastest(profile: Profile) {
        fastestConnectRunner.start(profile)
    }

    /** Stops an in-flight [connectFastest] run. See [FastestConnectRunner.start]'s doc for cancellation semantics. */
    fun cancelConnectFastest() {
        fastestConnectRunner.cancel()
    }

    private suspend fun probeProfile(
        profile: Profile,
        targetUrl: String,
        timeoutMs: Long,
    ): PingState {
        val config = runCatching { ConfigBuilder.toPingTestConfig(profile.config) }.getOrElse {
            LogRepository.append("ping: config build failed for ${profile.name}: ${it.message}")
            return PingState.Unavailable
        }
        // The coordinator launches the blocking JNI probe on viewModelScope (NOT a child of this
        // call) under a fixed native-admission ceiling, so the wall-clock backstop stops WAITING on
        // an uninterruptible native call without cancelling it, and orphans that outlive the backstop
        // stay bounded (each holds a native slot until measureLatency actually returns). Go already
        // bounds the dial at timeoutMs; the derived backstop guards the unbounded setup path
        // (core.New/Start) so a row can't hang on Testing forever.
        val backstop = PingTester.backstopFor(timeoutMs)
        return pingCoordinator.probeWithBackstop(
            scope = viewModelScope,
            backstopMs = backstop,
            context = Dispatchers.IO,
            onAdmissionRejected = {
                LogRepository.append("ping: native admission full, skipping ${profile.name}")
            },
            onBackstop = {
                LogRepository.append("ping: probe exceeded ${backstop}ms backstop for ${profile.name}")
            },
            nativeCall = { XrayBridge.measureLatency(config, targetUrl, timeoutMs) },
        )
    }

}
