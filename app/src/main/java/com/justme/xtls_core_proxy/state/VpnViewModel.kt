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
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import com.justme.xtls_core_proxy.log.LogRepository
import com.justme.xtls_core_proxy.log.VpnConnectionState
import com.justme.xtls_core_proxy.subs.SubscriptionRefreshCoordinator
import com.justme.xtls_core_proxy.vpn.XrayVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class SubGroup(val subscription: Subscription, val profiles: List<Profile>)
data class ProfilesView(val manual: List<Profile>, val groups: List<SubGroup>) {
    companion object {
        val EMPTY = ProfilesView(emptyList(), emptyList())
    }
}

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

    private val pingTester = PingTester()
    private val _pingStates = MutableStateFlow<Map<Long, PingState>>(emptyMap())
    val pingStates: StateFlow<Map<Long, PingState>> = _pingStates.asStateFlow()

    val logs = LogRepository.logs
    val connectionState = LogRepository.connectionState

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
        // XrayVpnService is a foreground service and disconnect is only
        // invoked from UI gated on CONNECTED/CONNECTING/PAUSED, so the
        // service exists; startForegroundService keeps us safe against
        // API 31+ background-start restrictions if the activity loses
        // foreground state between gating and dispatch.
        appContext.startForegroundService(stopIntent)
        ActiveProfileRepository.setActiveProfileId(context, null)
    }

    fun pingTestGroup(profiles: List<Profile>) {
        if (profiles.isEmpty()) return
        val byId = profiles.associateBy { it.id }
        viewModelScope.launch {
            pingTester.testAll(
                ids = byId.keys.toList(),
                onUpdate = { id, state -> _pingStates.update { it + (id to state) } },
                // byId.getValue is safe: PingTester only invokes probe with ids from the list we
                // passed (byId.keys). Even a contract violation is caught by testAll (Throwable ->
                // Unavailable), so a missing key cannot leave a row stuck on Testing.
                probe = { id -> probeProfile(byId.getValue(id)) }
            )
        }
    }

    fun pingTestProfile(profile: Profile) = pingTestGroup(listOf(profile))

    private suspend fun probeProfile(profile: Profile): PingState {
        val config = runCatching { ConfigBuilder.toPingTestConfig(profile.config) }.getOrElse {
            LogRepository.append("ping: config build failed for ${profile.name}: ${it.message}")
            return PingState.Unavailable
        }
        // Run the blocking JNI probe as a viewModelScope child (NOT a child of this call), so the
        // backstop stops WAITING on it without blocking on the uninterruptible native call:
        // await() is the suspension point withTimeoutOrNull can cancel. Go already bounds the dial
        // at PING_TIMEOUT_MS; PING_BACKSTOP_MS guards the unbounded setup path (core.New/Start) so a
        // row can't hang on Testing forever. On backstop the probe is orphaned (finishes on its own,
        // result discarded) rather than cancelled, since the native call ignores cancellation.
        val probe = viewModelScope.async(Dispatchers.IO) {
            XrayBridge.measureLatency(config, PingTester.PING_TEST_TARGET, PingTester.PING_TIMEOUT_MS)
        }
        val result = withTimeoutOrNull(PingTester.PING_BACKSTOP_MS) { probe.await() }
        if (result == null) {
            LogRepository.append("ping: probe exceeded ${PingTester.PING_BACKSTOP_MS}ms backstop for ${profile.name}")
            return PingState.Unavailable
        }
        return PingState.fromResult(result)
    }

}
