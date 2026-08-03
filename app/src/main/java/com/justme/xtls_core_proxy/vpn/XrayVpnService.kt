package com.justme.xtls_core_proxy.vpn

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.justme.xtls_core_proxy.MainActivity
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.bridge.XrayBridge
import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.config.DnsPreferences
import com.justme.xtls_core_proxy.config.FragmentationPreferences
import com.justme.xtls_core_proxy.config.LogSettings
import com.justme.xtls_core_proxy.config.MuxPreferences
import com.justme.xtls_core_proxy.config.RoutingPreferences
import com.justme.xtls_core_proxy.config.TuningSettings
import com.justme.xtls_core_proxy.config.XrayCorePreferences
import com.justme.xtls_core_proxy.config.XrayLogLevel
import com.justme.xtls_core_proxy.db.AppDatabase
import com.justme.xtls_core_proxy.db.Profile
import androidx.annotation.StringRes
import com.justme.xtls_core_proxy.failover.AndroidNetworkAvailability
import com.justme.xtls_core_proxy.failover.FailoverDecision
import com.justme.xtls_core_proxy.failover.FailoverPoolResolver
import com.justme.xtls_core_proxy.failover.FailoverPreferences
import com.justme.xtls_core_proxy.failover.FailoverSettings
import com.justme.xtls_core_proxy.failover.Http204HealthProbe
import com.justme.xtls_core_proxy.failover.RotationAdmission
import com.justme.xtls_core_proxy.failover.TunnelHealthMonitor
import com.justme.xtls_core_proxy.geo.GeoAssetPreparer
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import com.justme.xtls_core_proxy.killswitch.AndroidUsageStatsEventSource
import com.justme.xtls_core_proxy.killswitch.ForegroundAppMonitor
import com.justme.xtls_core_proxy.killswitch.KillSwitchRepository
import com.justme.xtls_core_proxy.killswitch.UsageStatsForegroundAppMonitor
import com.justme.xtls_core_proxy.log.LogPreferences
import com.justme.xtls_core_proxy.log.LogRepository
import com.justme.xtls_core_proxy.log.VpnConnectionState
import com.justme.xtls_core_proxy.log.XrayCoreLogTailer
import com.justme.xtls_core_proxy.state.ActiveProfileRepository
import com.justme.xtls_core_proxy.split.SplitTunnelMode
import com.justme.xtls_core_proxy.split.SplitTunnelPlanner
import com.justme.xtls_core_proxy.split.SplitTunnelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

@SuppressLint("VpnServicePolicy")
class XrayVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.justme.xtls_core_proxy.action.START"
        const val ACTION_STOP = "com.justme.xtls_core_proxy.action.STOP"
        // Fired by the ongoing notification's deleteIntent when the user swipes it away.
        // Android 14+ makes ongoing foreground-service notifications user-dismissable with
        // no opt-out flag, so we re-post to keep the status persistent while the VPN runs.
        const val ACTION_NOTIFICATION_DISMISSED = "com.justme.xtls_core_proxy.action.NOTIFICATION_DISMISSED"
        const val EXTRA_PROFILE_ID = "extra_profile_id"

        private const val CHANNEL_ID = "xray_vpn_channel"
        private const val ERROR_CHANNEL_ID = "xray_vpn_error_channel"
        private const val ERROR_NOTIFICATION_ID = 1102
    }

    private val lock = Any()
    private var tunInterface: ParcelFileDescriptor? = null
    private var running = false
    private var nextSessionEpoch = 0L
    private var activeSessionEpoch: Long? = null
    private var sessionTunnelState = SessionTunnelState.STOPPED

    // Lock-free mirror of activeSessionEpoch (authored ONLY under `lock`, when activeSessionEpoch is
    // set/cleared). The screen-state BroadcastReceiver reads this to answer "is this still the current
    // session?" without taking the full lifecycle lock on the main thread. It is a best-effort hint:
    // the receiver only decides whether to enqueue lightweight polling pause/resume, and the monitor's
    // own state machine is the source of truth. `lock` remains the authority for all session mutation.
    @Volatile private var activeEpochVolatile: Long? = null

    @Volatile private var currentProfileId: Long = -1L

    // Captured ONCE per connection in startVpn(); reused by bringUpTunnel() on the initial
    // connect AND every kill-switch revive, so a mid-session log-level change can't leak in.
    @Volatile private var sessionLog: LogSettings = LogSettings(XrayLogLevel.WARNING, null)
    @Volatile private var sessionLogFile: File? = null

    // Captured ONCE per connection alongside sessionLog; reused on kill-switch revive so a
    // mid-session fragmentation change can't leak in (same discipline as sessionLog).
    @Volatile private var sessionTuning: TuningSettings = TuningSettings.NONE
    private var logTailer: XrayCoreLogTailer? = null

    // Last controlled-app label that triggered the exposed state; used to rebuild the
    // exposed notification if the user swipes it away while paused.
    @Volatile private var lastTriggerLabel: String = ""

    // A kill-switch event that landed while a revive was in flight (state REVIVING) is deferred
    // here instead of being dropped, then replayed once the revive commits CONNECTED. Mutated only
    // under `lock`. Cleared on replay and on full-stop teardown so it never leaks across sessions.
    private var pendingKillLabel: String? = null

    private var killSwitchMonitor: UsageStatsForegroundAppMonitor? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var settingsObserverJob: Job? = null

    // --- Auto-failover ---
    /** Live health monitor, or null when failover is not armed. Guarded by `lock`. */
    private var failoverMonitor: TunnelHealthMonitor? = null
    /**
     * The settings [failoverMonitor] was CONSTRUCTED from. Interval/timeout/threshold are
     * constructor arguments of TunnelHealthMonitor/Http204HealthProbe and cannot be changed on a
     * running instance, so this is what tells a live timing edit apart from a no-op re-emission.
     * Guarded by `lock`.
     */
    private var failoverMonitorSettings: FailoverSettings? = null
    private var failoverSettingsJob: Job? = null
    /** Pending "try again once the thrash window elapsed" timer from a give-up. Guarded by `lock`. */
    private var failoverRearmJob: Job? = null
    @Volatile private var failoverSettings: FailoverSettings = FailoverPreferences.DEFAULT
    /** Rotation attempt timestamps for the sliding thrash window. Guarded by `lock`. */
    private var rotationAttempts: List<Long> = emptyList()
    /** Candidates that failed bring-up in the CURRENT rotation episode. Guarded by `lock`. */
    private var episodeFailedIds: Set<Long> = emptySet()
    /**
     * What the last failover give-up left behind, or null when no give-up state is showing.
     *
     * Not a bare "blackholed" boolean: the three outcomes need three different messages, and the
     * uncontained one must never be reported with containment copy. Authored under `lock`; read
     * off-lock by repostOngoingNotification, which uses it to tell a give-up (service still
     * running, restore the ongoing line) from a session that is simply dying (nothing to restore).
     */
    @Volatile private var giveUpOutcome: FailoverGiveUpOutcome? = null

    /**
     * Whether the single automatic recovery attempt granted to an UNPROTECTED give-up has been
     * spent. "Disconnect now, stop if the re-arm fails": the first unprotected give-up re-arms, and
     * if the re-armed rotation also fails to bring anything up we stop the service rather than
     * leaving it running-but-unprotected forever. Cleared wherever [giveUpOutcome] is — successful
     * rotation, successful revive, the recovery callback, and full teardown.
     *
     * Two carry-over cases are known and ACCEPTED rather than fixed, because both err towards
     * stopping a service that cannot protect anything:
     *  - a retry whose give-up classifies CONTAINED_BY_BLACKHOLE leaves the flag set (traffic is
     *    contained, so nothing clears it), so a later UNPROTECTED stops immediately with no retry
     *    of its own;
     *  - a kill-switch pause landing before the timer fires makes [rotateTunnel] bail at
     *    `canReserveRotation`, silently spending the retry without attempting anything.
     *
     * Guarded by `lock`.
     */
    private var unprotectedRetryConsumed: Boolean = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val tunnelOpScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )

    private data class SessionContext(
        val epoch: Long,
        val profileId: Long,
        val log: LogSettings,
    )

    private class StaleSessionException : IllegalStateException("VPN session is no longer active")

    private fun isCurrentSessionLocked(sessionEpoch: Long): Boolean =
        acceptsSessionLifecycleCallback(running, activeSessionEpoch, sessionEpoch)

    private fun ownsTunnelTransitionLocked(
        sessionEpoch: Long,
        expectedState: SessionTunnelState,
    ): Boolean = ownsTunnelTransition(
        running = running,
        activeSessionEpoch = activeSessionEpoch,
        callbackSessionEpoch = sessionEpoch,
        tunnelState = sessionTunnelState,
        expectedState = expectedState,
    )

    private fun isCurrentSession(sessionEpoch: Long): Boolean =
        synchronized(lock) { isCurrentSessionLocked(sessionEpoch) }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val profileId = intent?.getLongExtra(EXTRA_PROFILE_ID, StartCommandDecision.SENTINEL)
            ?: StartCommandDecision.SENTINEL
        when (val decision = StartCommandDecision.decide(intent?.action, profileId)) {
            is StartCommandDecision.StartProfile -> startVpn(decision.profileId)
            StartCommandDecision.StartActiveProfile -> resolveActiveAndStart()
            StartCommandDecision.Stop ->
                // User-initiated stop. Route through tunnelOpScope instead of running the blocking
                // stopVpn (Xray/TUN teardown, plus contention on the lock a connect holds across the
                // seconds-long XrayBridge.startXray) on the main thread — a Disconnect during a connect
                // would otherwise freeze the UI / risk an ANR. limitedParallelism(1) serializes this
                // behind any in-flight kill/revive; stopVpn (no expected epoch) then tears down whatever
                // session is current, so a stop landing mid-start still reliably stops the tunnel.
                // onDestroy/onRevoke keep the SYNCHRONOUS stopVpn where teardown must complete inline.
                tunnelOpScope.launch { stopVpn() }
            StartCommandDecision.RepostNotification -> {
                // User swiped the ongoing notification (allowed on Android 14+). Re-post it
                // so the connected/exposed status stays visible while the VPN runs; if we are
                // no longer running this was a stale delivery, so just clean up.
                // Marshal the re-post onto tunnelOpScope so it serializes behind any in-flight
                // kill/revive (which write the same NOTIFICATION_ID); a swipe mid-transition then
                // reads the settled state instead of racing the authoritative notification writer.
                // If the VPN has stopped by the time it runs, connectionState is DISCONNECTED and
                // repostOngoingNotification() is a no-op.
                if (running) tunnelOpScope.launch { repostOngoingNotification() } else stopSelf()
            }
            StartCommandDecision.RefuseNoProfile -> {
                LogRepository.setConnectionState(VpnConnectionState.ERROR)
                LogRepository.emitError(R.string.vpn_start_failed_error)
                LogRepository.append("Refused to start: no profile ID provided")
                stopSelf()
            }
        }
        // REDELIVER: an OS kill or process crash re-delivers the last ACTION_START
        // (profile id included) so the same profile reconnects. An explicit stop calls
        // no-arg stopSelf(), which clears all pending intents, so it is not redelivered.
        return START_REDELIVER_INTENT
    }

    private fun resolveActiveAndStart() {
        // System-initiated start (always-on / boot) arrives with startForegroundService
        // semantics: we must call startForeground within the OS deadline (~5s) or be killed.
        // pickOrPersistActive is an async Room read that can be slow on a cold boot, so
        // promote to foreground BEFORE resolving, and tear it down if no profile resolves.
        createNotificationChannel()
        startForeground(
            VpnNotifications.NOTIFICATION_ID,
            buildNotification(localizedString(R.string.vpn_status_connecting))
        )
        serviceScope.launch {
            val id = ActiveProfileRepository.pickOrPersistActive(this@XrayVpnService)
            if (id == null) {
                LogRepository.append("Always-on start: no active profile to bring up")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                startVpn(id)
            }
        }
    }

    override fun onRevoke() {
        LogRepository.append("VPN permission revoked by system")
        LogRepository.emitError(R.string.vpn_permission_revoked_error)
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        tunnelOpScope.cancel()
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn(profileId: Long) {
        val sessionEpoch = synchronized(lock) {
            if (running) {
                if (!shouldRestartForRecovery(running, giveUpOutcome)) {
                    LogRepository.append("VPN already running")
                    // The caller recorded the requested profile as active before dispatching this
                    // start; we are refusing it, so roll that write back to the profile the tunnel
                    // is really on or the UI and the QS tile would label a server we never
                    // connected to as connected. Same rollback duty as rotateTunnel's bring-up
                    // failure arm, from the other side: ActiveProfileRepository must only ever
                    // name a profile some tunnel actually carries. setActiveProfileId writes via
                    // apply(), so this holds no disk I/O under the lock.
                    activeProfileIdToRestoreOnRefusedStart(profileId, currentProfileId)?.let {
                        ActiveProfileRepository.setActiveProfileId(this@XrayVpnService, it)
                    }
                    return
                }
                // The user acted on the "turn the VPN off and on again, or choose another server"
                // copy from an UNPROTECTED give-up: the service is running but owns no tunnel and
                // is protecting nothing, so the early return above would silently swallow their
                // only in-app recovery. Tear the dead session down and fall through to the NORMAL
                // start path, which takes a fresh epoch — no parallel bring-up. stopService = false
                // keeps this service instance alive across the restart: a real stopSelf() here
                // would schedule our own destruction and onDestroy would then tear down the session
                // we are about to start. Reentrant on the same thread, and it flips `running` false
                // so the assignment below stays coherent.
                LogRepository.append(
                    "Restarting the tunnel to recover from an unprotected state (profile id=$profileId)"
                )
                stopVpn(stopService = false)
            }
            running = true
            nextSessionEpoch += 1
            activeSessionEpoch = nextSessionEpoch
            activeEpochVolatile = nextSessionEpoch
            sessionTunnelState = SessionTunnelState.STARTING
            nextSessionEpoch
        }

        val foregrounded = synchronized(lock) {
            if (!isCurrentSessionLocked(sessionEpoch)) {
                false
            } else {
                createNotificationChannel()
                startForeground(
                    VpnNotifications.NOTIFICATION_ID,
                    buildNotification(localizedString(R.string.vpn_status_connecting))
                )
                true
            }
        }
        if (!foregrounded) return

        // Defensive re-check: a caller (e.g. the QS tile) may have pre-flighted
        // VpnService.prepare() before dispatching ACTION_START, and the user
        // could have revoked permission in the gap before we got here. Without
        // this guard, establish() would later fail with a silent
        // SecurityException and the user would only see ERROR state with no
        // explanation. startForeground above satisfies the FGS contract before
        // we stop ourselves.
        if (VpnService.prepare(this) != null) {
            failInitialStart(
                sessionEpoch = sessionEpoch,
                errorRes = R.string.vpn_permission_revoked_error,
                logMessage = "Refused to start: VPN permission not granted",
                postNotification = ::postPermissionRevokedNotification,
            )
            return
        }

        val announced = synchronized(lock) {
            if (!isCurrentSessionLocked(sessionEpoch)) {
                false
            } else {
                LogRepository.setConnectionState(VpnConnectionState.CONNECTING)
                LogRepository.append("Starting VPN service")
                true
            }
        }
        if (!announced) return

        Thread {
            try {
                val profile = runBlocking {
                    AppDatabase.get(this@XrayVpnService).profileDao().getById(profileId)
                }
                if (profile == null) {
                    failInitialStart(
                        sessionEpoch = sessionEpoch,
                        errorRes = R.string.vpn_start_failed_error,
                        logMessage = "Profile not found (id=$profileId)",
                    )
                    return@Thread
                }

                // Capture the log level for the whole session (survives kill-switch revives).
                val logFile = File(filesDir, "logs/xray-core.log")
                val initialLog = LogSettings(
                    LogPreferences.getLogLevel(this@XrayVpnService),
                    logFile.absolutePath,
                )
                val initialized = synchronized(lock) {
                    if (!isCurrentSessionLocked(sessionEpoch)) {
                        false
                    } else {
                        logFile.parentFile?.mkdirs()
                        // Truncation is best-effort: a failure must not abort connect, but leave a
                        // sanitized breadcrumb so operators know core logs may be missing.
                        runCatching { logFile.writeText("") }
                            .onFailure {
                                LogRepository.append(
                                    "Core log file truncate failed; Xray-core logs may be unavailable this session"
                                )
                            }
                        currentProfileId = profileId
                        sessionLogFile = logFile
                        sessionTuning = TuningSettings(
                            fragmentation = FragmentationPreferences.load(this@XrayVpnService),
                            mux = MuxPreferences.load(this@XrayVpnService),
                            dns = DnsPreferences.load(this@XrayVpnService),
                            routing = RoutingPreferences.load(this@XrayVpnService),
                            core = XrayCorePreferences.load(this@XrayVpnService),
                        )
                        sessionLog = initialLog
                        true
                    }
                }
                if (!initialized) return@Thread

                bringUpTunnel(
                    profile = profile,
                    log = initialLog,
                    sessionEpoch = sessionEpoch,
                    expectedState = SessionTunnelState.STARTING,
                )
                    .onSuccess {
                        if (!isCurrentSession(sessionEpoch)) return@onSuccess
                        val prefs = KillSwitchRepository.load(this@XrayVpnService)
                        // Seeded read, mirroring the KillSwitchRepository.load above and for the
                        // same reason: FailoverPreferences.state is a process-global
                        // MutableStateFlow(DEFAULT), so an observer-only wiring would receive
                        // `enabled = false` and never arm failover on any path where the settings
                        // Activity never ran in this process (process death, always-on restart, a
                        // first-launch QS-tile connect). Both loads touch SharedPreferences and so
                        // stay OUTSIDE the lifecycle lock.
                        val failoverPrefs = FailoverPreferences.load(this@XrayVpnService)
                        val committed = synchronized(lock) {
                            if (!ownsTunnelTransitionLocked(sessionEpoch, SessionTunnelState.STARTING)) {
                                false
                            } else {
                                sessionLogFile?.let { f ->
                                    if (logTailer == null) {
                                        logTailer = XrayCoreLogTailer(f).also { it.start() }
                                    }
                                }
                                sessionTunnelState = SessionTunnelState.CONNECTED
                                LogRepository.setConnectionState(VpnConnectionState.CONNECTED)
                                updateNotification(localizedString(R.string.vpn_status_connected))
                                applyKillSwitchPreferences(prefs, sessionEpoch)
                                settingsObserverJob?.cancel()
                                settingsObserverJob = serviceScope.launch {
                                    KillSwitchRepository.state.collect { newPrefs ->
                                        if (isCurrentSession(sessionEpoch)) {
                                            applyKillSwitchPreferences(newPrefs, sessionEpoch)
                                        }
                                    }
                                }
                                applyFailoverPreferences(failoverPrefs, sessionEpoch)
                                failoverSettingsJob?.cancel()
                                failoverSettingsJob = serviceScope.launch {
                                    FailoverPreferences.state.collect { newSettings ->
                                        applyFailoverPreferences(newSettings, sessionEpoch)
                                    }
                                }
                                true
                            }
                        }
                        if (!committed) return@onSuccess
                    }
                    .onFailure { error ->
                        failInitialStart(
                            sessionEpoch = sessionEpoch,
                            errorRes = R.string.vpn_start_failed_error,
                            logMessage = "Xray start failed: ${error.message}",
                        )
                    }
            } catch (error: Throwable) {
                failInitialStart(
                    sessionEpoch = sessionEpoch,
                    errorRes = R.string.vpn_start_failed_error,
                    logMessage = "VPN start failed: ${error.message}",
                )
            }
        }.start()
    }

    private fun failInitialStart(
        sessionEpoch: Long,
        @StringRes errorRes: Int,
        logMessage: String,
        postNotification: (() -> Unit)? = null,
    ) {
        val shouldStop = synchronized(lock) {
            if (!ownsTunnelTransitionLocked(sessionEpoch, SessionTunnelState.STARTING)) {
                false
            } else {
                LogRepository.setConnectionState(VpnConnectionState.ERROR)
                LogRepository.emitError(errorRes)
                LogRepository.append(logMessage)
                postNotification?.invoke()
                true
            }
        }
        if (shouldStop) stopVpn(expectedSessionEpoch = sessionEpoch)
    }

    private fun bringUpTunnel(
        profile: Profile,
        log: LogSettings,
        sessionEpoch: Long,
        expectedState: SessionTunnelState,
    ): Result<Unit> {
        return runCatching {
            val ownsTransition = synchronized(lock) {
                ownsTunnelTransitionLocked(sessionEpoch, expectedState)
            }
            if (!ownsTransition) throw StaleSessionException()
            val configJson = ConfigBuilder.buildRuntimeConfig(profile.config, log, sessionTuning)

            val geoAssetDir = GeoAssetPreparer.prepare(this)
                .getOrElse { error ->
                    throw IllegalStateException("Geofile preparation failed: ${error.message}", error)
                }

            val builder = Builder()
                .setSession(localizedString(R.string.app_name))
                .setMtu(sessionTuning.core.mtu)
                .addAddress("10.7.0.1", 32)
                .addAddress("fd00:1:fd00:1::1", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .also { if (sessionTuning.core.ipv6) it.addDnsServer("2606:4700:4700::1111") }

            val splitPrefs = SplitTunnelRepository.load(this@XrayVpnService)
            if (splitPrefs.mode == SplitTunnelMode.ALLOW_ONLY && splitPrefs.packages.isEmpty()) {
                LogRepository.append("Split tunnel allow-only mode enabled with no selected apps")
            }
            // Whole-app tunneling: self is never excluded. Xray's own sockets bypass the
            // tun via protect() (registered above), not via app exclusion.
            val plan = SplitTunnelPlanner.plan(splitPrefs.mode, splitPrefs.packages, packageName)
            plan.allowedPackages.forEach { pkg ->
                try {
                    builder.addAllowedApplication(pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                    LogRepository.append("Split tunnel skipped missing package: $pkg")
                }
            }
            plan.disallowedPackages.forEach { pkg ->
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                    LogRepository.append("Split tunnel skipped missing package: $pkg")
                }
            }

            // Serialise ownership of the global TUN/Xray bridge with full stop and the next
            // session admission. A stale starter is rejected before it can publish resources.
            synchronized(lock) {
                if (!ownsTunnelTransitionLocked(sessionEpoch, expectedState)) {
                    throw StaleSessionException()
                }
                check(tunInterface == null) {
                    "Cannot establish a tunnel while the active transition already owns a TUN interface"
                }

                val pfd = builder.establish()
                    ?: throw IllegalStateException("VpnService.establish() returned null")

                tunInterface = pfd
                val fd = pfd.fd
                LogRepository.append("TUN established with fd=$fd")
                LogRepository.append("Using geofiles from ${geoAssetDir.absolutePath}")

                // Loop-avoidance: Xray's own sockets bypass the tun via protect().
                // Must succeed before Xray dials, or (with self-exclusion removed in
                // Task 2) the proxy socket would route into the tun and loop. getOrThrow()
                // also surfaces a controller-install failure from the Go bridge.
                XrayBridge.registerProtector(this@XrayVpnService).getOrThrow()

                XrayBridge.startXray(configJson, fd, geoAssetDir.absolutePath).getOrThrow()
                LogRepository.append("Xray core started")
            }
        }
    }

    private fun tearDownTunnelLocked() {
        XrayBridge.stopXray().onFailure { error ->
            LogRepository.append("Xray stop warning: ${error.message}")
        }
        try {
            tunInterface?.close()
        } catch (error: Throwable) {
            LogRepository.append("TUN close warning: ${error.message}")
        } finally {
            tunInterface = null
        }
    }

    private fun killTunnel(sessionEpoch: Long, triggerPackageLabel: String) {
        tunnelOpScope.launch {
            try {
                synchronized(lock) {
                    if (!ownsTunnelTransitionLocked(sessionEpoch, SessionTunnelState.CONNECTED)) {
                        // A kill can only tear down a CONNECTED tunnel. If the same session is
                        // mid-transition — a kill-switch revive OR a failover rotation, both of
                        // which tear the tunnel down and bring it back up — DEFER the event (record
                        // it, replay once the transition commits) rather than dropping it: the
                        // foreground monitor is edge-triggered and would never re-fire this safety
                        // event, leaving the tunnel CONNECTED with a kill-listed app in the
                        // foreground. Any other state (stale epoch, stopped, already paused) has
                        // nothing to defer to, so drop as before.
                        if (shouldDeferKillDuringTransition(
                                running = running,
                                activeSessionEpoch = activeSessionEpoch,
                                callbackSessionEpoch = sessionEpoch,
                                tunnelState = sessionTunnelState,
                            )
                        ) {
                            pendingKillLabel = triggerPackageLabel
                            LogRepository.append(
                                "Kill-switch: deferring kill for $triggerPackageLabel " +
                                    "until the in-flight transition completes"
                            )
                        }
                        return@launch
                    }
                    // The kill-switch subsystem may have been disabled after this event was queued on
                    // tunnelOpScope (applyKillSwitchPreferences stops + nulls the monitor under the lock).
                    // Do not tear down a tunnel for a feature that is no longer active — drop the stale
                    // queued kill. (The defer/replay path can't reach here with a null monitor: the
                    // disable branch clears pendingKillLabel, so no replay is dispatched.)
                    if (killSwitchMonitor == null) {
                        LogRepository.append(
                            "Kill-switch: ignoring queued kill for $triggerPackageLabel (feature disabled)"
                        )
                        return@launch
                    }
                    LogRepository.append("Kill-switch: tearing down tunnel for $triggerPackageLabel")
                    tearDownTunnelLocked()
                    sessionTunnelState = SessionTunnelState.PAUSED
                    // No tunnel exists while PAUSED, so every health probe would fail and we would
                    // "rotate" a tunnel the kill-switch deliberately tore down. Stop, don't pause:
                    // pausePolling() preserves the failure count, which would then trip instantly on
                    // revive. reviveTunnel re-applies prefs to bring the monitor back. The screen
                    // receiver is deliberately NOT reconciled here — the kill-switch monitor is
                    // still live, so it must stay registered.
                    stopFailoverMonitorLocked()
                    // State first, then the notification: notify() is a silent no-op when
                    // POST_NOTIFICATIONS is denied, but writing state ahead keeps the machine
                    // correct even if the exposed-notification build ever throws.
                    LogRepository.setConnectionState(VpnConnectionState.PAUSED)
                    lastTriggerLabel = triggerPackageLabel
                    // Quiet, persistent FGS notification (id 1101, low channel) drops to a
                    // paused status line; the loud heads-up exposed alert is a SEPARATE
                    // notification on the high channel (id 1103) so it can actually alert.
                    updateNotification(localizedString(R.string.vpn_status_paused, triggerPackageLabel))
                    // 1106 says a kill was DEFERRED and the listed app is still tunnelled. The kill
                    // has now landed and the tunnel is gone, so that notice is false — and it lives
                    // on this same high-importance channel, so leaving it up would pair "VPN is OFF
                    // for every app" with "that app is still going through the VPN". Retract before
                    // posting so the two contradictory heads-ups never coexist.
                    VpnNotifications.cancelKillSwitchNotApplied(this@XrayVpnService)
                    // 1105, the give-up alert, for exactly the same reason — and it IS reachable,
                    // for all three outcomes. giveUpRotationLocked leaves sessionTunnelState
                    // CONNECTED on every path that posts 1105 (mechanically required, so the re-arm
                    // can reserve another rotation), which is precisely the state this kill needs to
                    // proceed from, and nothing in the give-up path touches the kill-switch monitor.
                    // So a blackholed or degraded session can be paused, and its "your connection
                    // was paused to keep you protected" alert would then sit beside 1103's "the VPN
                    // is OFF and you're exposed" on an equally loud channel.
                    //
                    // giveUpOutcome is deliberately NOT cleared here. Nothing reads it while PAUSED:
                    // repostOngoingNotification takes its PAUSED branch, and shouldRestartForRecovery
                    // is unreachable because every start surface refuses PAUSED
                    // (connectAction(PAUSED) == UNAVAILABLE, decideTileClick(PAUSED) == Stop). It
                    // cannot outlive the pause either — reviveTunnel's success path clears it, and
                    // failRevive stops the session, which clears it too. Against that, clearing it
                    // would add a SECOND disarm site for the marker shouldRestartForRecovery keys
                    // off, which is exactly the coupling that produced the running-but-unconnectable
                    // bug on the disable path. If a start affordance is ever added in PAUSED, clear
                    // it here first — that is the trade-off being made, not an oversight.
                    VpnNotifications.cancelFailoverBlackholed(this@XrayVpnService)
                    VpnNotifications.postExposed(
                        this@XrayVpnService,
                        triggerPackageLabel,
                        notificationDismissIntent()
                    )
                }
            } catch (error: Throwable) {
                failKillSwitch(sessionEpoch, "killTunnel failed: ${error.message}")
            }
        }
    }

    private fun failKillSwitch(sessionEpoch: Long, logMessage: String) {
        val shouldStop = synchronized(lock) {
            if (!isCurrentSessionLocked(sessionEpoch)) {
                false
            } else {
                LogRepository.append(logMessage)
                LogRepository.setConnectionState(VpnConnectionState.ERROR)
                LogRepository.emitError(R.string.vpn_revive_error)
                true
            }
        }
        if (shouldStop) stopVpn(expectedSessionEpoch = sessionEpoch)
    }

    private fun reviveTunnel(sessionEpoch: Long) {
        tunnelOpScope.launch {
            // Mirror killTunnel's guard: revive's async getById/append/bringUpTunnel would otherwise
            // let an unexpected Throwable escape into the SupervisorJob scope with no handler, which
            // crashes the process. Route any escape through failRevive (a no-op unless this coroutine
            // still owns the REVIVING transition for sessionEpoch).
            try {
                val session = synchronized(lock) {
                    if (!canReserveRevive(
                            running = running,
                            activeSessionEpoch = activeSessionEpoch,
                            callbackSessionEpoch = sessionEpoch,
                            tunnelState = sessionTunnelState,
                        )
                    ) {
                        null
                    } else {
                        // Reserve PAUSED → REVIVING before any async DB/config work. A duplicate
                        // same-epoch revive now observes REVIVING and returns without establishing.
                        sessionTunnelState = SessionTunnelState.REVIVING
                        SessionContext(sessionEpoch, currentProfileId, sessionLog)
                    }
                } ?: return@launch
                if (session.profileId == -1L) {
                    failRevive(session.epoch, "reviveTunnel: no current profile, cannot revive")
                    return@launch
                }
                if (!isCurrentSession(session.epoch)) return@launch
                LogRepository.append("Kill-switch: reviving tunnel for profile id=${session.profileId}")
                val profile = AppDatabase.get(this@XrayVpnService).profileDao().getById(session.profileId)
                if (profile == null) {
                    failRevive(session.epoch, "reviveTunnel: profile ${session.profileId} not found")
                    return@launch
                }
                bringUpTunnel(
                    profile = profile,
                    log = session.log,
                    sessionEpoch = session.epoch,
                    expectedState = SessionTunnelState.REVIVING,
                )
                    .onSuccess {
                        val replayKillLabel = synchronized(lock) {
                            if (!ownsTunnelTransitionLocked(session.epoch, SessionTunnelState.REVIVING)) {
                                return@onSuccess
                            }
                            sessionTunnelState = SessionTunnelState.CONNECTED
                            LogRepository.setConnectionState(VpnConnectionState.CONNECTED)
                            // Dismiss the separate exposed heads-up; restore the connected status.
                            VpnNotifications.cancelExposed(this@XrayVpnService)
                            // A revive that lands on a blackholed session replaces the unread fd
                            // with a real Xray-backed tunnel, so the give-up alert would now be
                            // actively misleading — it claims the internet is off while it works.
                            giveUpOutcome = null
                            unprotectedRetryConsumed = false
                            VpnNotifications.cancelFailoverBlackholed(this@XrayVpnService)
                            updateNotification(localizedString(R.string.vpn_status_connected))
                            // A kill-switch event deferred during this revive must now be replayed
                            // so the tunnel does not stay CONNECTED with the kill-listed app in the
                            // foreground. Clear the pending marker under the same lock, then re-run
                            // the normal kill path (pause + exposed heads-up) for the current epoch.
                            pendingKillLabel.also { pendingKillLabel = null }
                        }
                        // Restart failover for the restored tunnel — nothing else does, so without
                        // this the feature would stay dead for the rest of the session after the
                        // first kill-switch pause. Reads the current settings flow value (the
                        // observer keeps it fresh) and re-checks epoch + CONNECTED internally.
                        // MUST run outside the lock block above: it takes the lock itself, and
                        // running it before CONNECTED is committed would read REVIVING and no-op.
                        applyFailoverPreferences(FailoverPreferences.state.value, session.epoch)
                        // Ordered before the replay so a replayed kill correctly stops the monitor
                        // again through killTunnel's pause path.
                        if (replayKillLabel != null) {
                            killTunnel(session.epoch, replayKillLabel)
                        }
                    }
                    .onFailure { error ->
                        failRevive(session.epoch, "reviveTunnel failed: ${error.message}")
                    }
            } catch (ce: CancellationException) {
                // Structured-concurrency cancellation (e.g. tunnelOpScope.cancel() in onDestroy) must
                // propagate, not be reported as a revive failure. Unlike killTunnel (whose body has no
                // suspension points), reviveTunnel suspends at getById, so its catch CAN observe a CE.
                throw ce
            } catch (error: Throwable) {
                failRevive(sessionEpoch, "reviveTunnel failed: ${error.message}")
            }
        }
    }

    private fun failRevive(sessionEpoch: Long, logMessage: String) {
        val shouldStop = synchronized(lock) {
            if (!ownsTunnelTransitionLocked(sessionEpoch, SessionTunnelState.REVIVING)) {
                false
            } else {
                LogRepository.append(logMessage)
                LogRepository.emitError(R.string.vpn_revive_error)
                postReviveErrorNotification()
                true
            }
        }
        if (shouldStop) stopVpn(expectedSessionEpoch = sessionEpoch)
    }

    /**
     * Failover rotation: tear the dead tunnel down and bring a sibling server up, inside the SAME
     * session epoch. Sibling of [killTunnel]/[reviveTunnel] and keeps their locking discipline —
     * reserve the transition under `lock` before any async work, re-check ownership before every
     * mutation, and route every escape through a single fail path.
     */
    private fun rotateTunnel(sessionEpoch: Long) {
        tunnelOpScope.launch {
            try {
                val session = synchronized(lock) {
                    if (!canReserveRotation(
                            running = running,
                            activeSessionEpoch = activeSessionEpoch,
                            callbackSessionEpoch = sessionEpoch,
                            tunnelState = sessionTunnelState,
                        )
                    ) return@launch
                    when (val admission = FailoverDecision.admitRotation(
                        attempts = rotationAttempts,
                        now = System.currentTimeMillis(),
                        maxRotations = failoverSettings.maxRotations,
                        windowMs = failoverSettings.rotationWindowMs,
                    )) {
                        RotationAdmission.Denied -> {
                            giveUpRotationLocked(sessionEpoch, "thrash cap reached")
                            return@launch
                        }
                        is RotationAdmission.Admitted -> rotationAttempts = admission.attempts
                    }
                    sessionTunnelState = SessionTunnelState.ROTATING
                    // The monitor that fired is already TERMINAL (TunnelHealthMonitor clears its own
                    // isStarted/job before invoking the listener) but the FIELD still holds it. Drop
                    // it here so the post-rotation re-apply constructs a FRESH monitor instead of
                    // early-returning on a non-null field — otherwise failover would arm exactly
                    // once per session. The screen receiver is deliberately NOT reconciled yet: the
                    // rotation is transient, and the post-rotation apply reconciles it.
                    stopFailoverMonitorLocked()
                    SessionContext(sessionEpoch, currentProfileId, sessionLog)
                }

                val dao = AppDatabase.get(this@XrayVpnService).profileDao()
                val current = dao.getById(session.profileId)
                if (current == null) {
                    failRotation(
                        session.epoch,
                        "rotateTunnel: current profile ${session.profileId} not found"
                    )
                    return@launch
                }
                val pool = FailoverPoolResolver.resolve(dao, current)
                val failed = synchronized(lock) { episodeFailedIds }
                val next = FailoverDecision.nextCandidate(pool, current.id, failed)
                if (next == null) {
                    synchronized(lock) {
                        // Same ownership re-check as the bring-up block below, and for the same
                        // reason: getById and FailoverPoolResolver.resolve both ran OFF-LOCK just
                        // above, so a stop+restart in that window would otherwise let this
                        // old-epoch give-up stop the NEW session's monitor, unregister its shared
                        // screen receiver, and write BLACKHOLED over a healthy tunnel — with its
                        // re-arm keyed to the dead epoch, so nothing would ever clear it.
                        if (!ownsTunnelTransitionLocked(session.epoch, SessionTunnelState.ROTATING)) {
                            return@launch
                        }
                        giveUpRotationLocked(session.epoch, "no healthy candidate left in pool")
                    }
                    return@launch
                }

                LogRepository.append("Failover: rotating ${current.name} -> ${next.name}")
                synchronized(lock) {
                    if (!ownsTunnelTransitionLocked(session.epoch, SessionTunnelState.ROTATING)) {
                        return@launch
                    }
                    tearDownTunnelLocked()
                    currentProfileId = next.id
                    // ANNOUNCE THE GAP. From here until establish() there is no VPN interface at
                    // all — bringUpTunnel does buildRuntimeConfig, geo-asset prep and the split
                    // read off-lock first — so leaving the UI, the ongoing notification and the QS
                    // tile saying CONNECTED would claim protection the user does not have. The
                    // teardown-before-bring-up ordering is forced by bringUpTunnel's
                    // check(tunInterface == null) and is not changed here; only the silence is.
                    // Every arm below re-announces: success -> CONNECTED, retry -> CONNECTING
                    // again on the next attempt, give-up -> BLACKHOLED/ERROR.
                    LogRepository.setConnectionState(VpnConnectionState.CONNECTING)
                    updateNotification(localizedString(R.string.vpn_status_switching))
                }

                bringUpTunnel(
                    profile = next,
                    log = session.log,
                    sessionEpoch = session.epoch,
                    expectedState = SessionTunnelState.ROTATING,
                )
                    .onSuccess {
                        val replayKillLabel = synchronized(lock) {
                            if (!ownsTunnelTransitionLocked(session.epoch, SessionTunnelState.ROTATING)) {
                                return@onSuccess
                            }
                            // ---- COMMIT ----
                            // This write is the commit: from here the rotation has succeeded, and
                            // NOTHING that follows may reach the outer `catch (error: Throwable)`.
                            // That calls failRotation, which funnels straight into
                            // giveUpRotationLocked with sessionTunnelState == CONNECTED and a real
                            // fd, i.e. classifies CONTAINED_BY_LIVE_TUNNEL and writes BLACKHOLED,
                            // posts the give-up alert and stops the monitor OVER A HEALTHY,
                            // JUST-RESTORED TUNNEL. A committed success must not be reclassifiable.
                            sessionTunnelState = SessionTunnelState.CONNECTED
                            episodeFailedIds = emptySet()   // episode ends on a successful rotation
                            // ---- POST-COMMIT, still under `lock` ----
                            // The three calls below reach a subsystem and can therefore throw:
                            // getSystemService(...).notify/cancel are binder calls, and
                            // localizedString builds a configuration context and resolves a
                            // resource. The bare field writes between them cannot, so only the
                            // calls are guarded.
                            //
                            // They are guarded IN PLACE rather than moved out of the lock. Order
                            // and atomicity are load-bearing for the first one: publishing
                            // CONNECTED under the same lock as the state transition it describes is
                            // what stops a concurrent kill-switch pause or stop — both of which
                            // write LogRepository state while holding `lock` — from being
                            // overwritten by a late CONNECTED from here. Moving the commit itself
                            // after the calls would not help either: a throw would then escape with
                            // the state still ROTATING and a live fd, which failRotation classifies
                            // CONTAINED_BY_LIVE_TUNNEL just the same while additionally stranding
                            // the transition.
                            //
                            // afterRotationCommitted is safe to call while holding `lock`: it is a
                            // plain try/catch whose only side effect is LogRepository.append (taken
                            // under this lock in a dozen places here), it never suspends and never
                            // takes a lock of its own. CancellationException still propagates — out
                            // of the synchronized block, out of .onSuccess, to the CE arm below.
                            //
                            // Swallowing these is the lesser evil, not a free win: a dropped
                            // setConnectionState leaves the UI on CONNECTING over a live tunnel
                            // until the next state change or a repostOngoingNotification. That is
                            // recoverable and cosmetic; letting it escape tears down a healthy
                            // session.
                            afterRotationCommitted("publishing the connected state") {
                                LogRepository.setConnectionState(VpnConnectionState.CONNECTED)
                            }
                            // Traffic flows again, so a give-up alert left over from an earlier
                            // blackhole would now claim the internet is off while it works. This
                            // also covers a rotation kicked off by the re-arm timer.
                            giveUpOutcome = null
                            unprotectedRetryConsumed = false
                            afterRotationCommitted("retracting the give-up alert") {
                                VpnNotifications.cancelFailoverBlackholed(this@XrayVpnService)
                            }
                            afterRotationCommitted("refreshing the ongoing notification") {
                                updateNotification(localizedString(R.string.vpn_status_connected))
                            }
                            pendingKillLabel.also { pendingKillLabel = null }
                        }
                        // ---- POST-COMMIT, off the lock ----
                        // Same rule as inside the block: none of this may reach the outer
                        // `catch (error: Throwable)`, for the reason recorded at the commit above.
                        //
                        // Guarded per step rather than as one block, deliberately: the last two are
                        // not cosmetic. Skipping applyFailoverPreferences leaves the watchdog dead
                        // for the rest of the session, and skipping the replay silently drops a
                        // kill-switch event the user asked for. A single shared guard would let a
                        // throw in the first, most trivial step take both of those out.
                        //
                        // The app's notion of "active profile" MUST follow, or the UI, the QS tile,
                        // and the next manual reconnect all still point at the dead server. It is
                        // also what a system-initiated start reads: resolveActiveAndStart (always-on
                        // / boot) brings up whatever ActiveProfileRepository names, so without this
                        // an always-on restart would return to the server failover just rotated off.
                        // NOT START_REDELIVER_INTENT, though — a redelivered intent carries the
                        // original EXTRA_PROFILE_ID and StartCommandDecision.decide routes it by
                        // that, never through the active profile.
                        afterRotationCommitted("recording the new active profile") {
                            ActiveProfileRepository.setActiveProfileId(this@XrayVpnService, next.id)
                        }
                        afterRotationCommitted("posting the switched-server notice") {
                            VpnNotifications.postFailover(this@XrayVpnService, current.name, next.name)
                        }
                        afterRotationCommitted("restarting the health monitor") {
                            applyFailoverPreferences(failoverSettings, session.epoch)
                        }
                        if (replayKillLabel != null) {
                            afterRotationCommitted("replaying the deferred kill") {
                                killTunnel(session.epoch, replayKillLabel)
                            }
                        }
                    }
                    .onFailure { error ->
                        synchronized(lock) {
                            // Return to CONNECTED so the next attempt can reserve the transition.
                            if (ownsTunnelTransitionLocked(session.epoch, SessionTunnelState.ROTATING)) {
                                // INSIDE the ownership check, like every other mutation here. This
                                // set is per-session episode state, and getById / resolve /
                                // bringUpTunnel all ran off-lock above — so a stop+restart in that
                                // window would otherwise let this old-epoch failure blacklist a
                                // server in the NEW session's episode, skipping a server that is
                                // perfectly healthy for it. Keeping it inside also keeps the retry
                                // correct in the case that matters: this is the branch that hands
                                // control to the recursive rotateTunnel below, and that attempt
                                // needs next.id excluded or it would pick the same dead server
                                // again. A rotation that no longer owns the transition dispatches
                                // no such retry — canReserveRotation refuses it — so it has
                                // nothing to record for.
                                episodeFailedIds = episodeFailedIds + next.id
                                sessionTunnelState = SessionTunnelState.CONNECTED
                                // bringUpTunnel can fail AFTER establish() (e.g. startXray threw),
                                // leaving a real fd with an indeterminate Xray behind it. Drop it,
                                // so "tunInterface != null" keeps its single meaning downstream:
                                // the live, still-proxying tunnel. Without this, a give-up would
                                // mistake that half-built fd for a working tunnel.
                                tearDownTunnelLocked()
                                // Roll the profile back to the last one that actually connected.
                                // currentProfileId is what reviveTunnel brings up, so leaving it on
                                // a server we just proved dead makes a kill-switch revive fail and
                                // stopVpn take the whole tunnel down. It also keeps this in step
                                // with ActiveProfileRepository, which only advances on success.
                                currentProfileId = session.profileId
                            }
                        }
                        LogRepository.append("Failover: ${next.name} failed to come up: ${error.message}")
                        rotateTunnel(session.epoch)   // try the next candidate, still under the cap
                    }
            } catch (ce: CancellationException) {
                // Structured-concurrency cancellation (tunnelOpScope.cancel() in onDestroy) must
                // propagate rather than be reported as a rotation failure. Like reviveTunnel and
                // unlike killTunnel, this body suspends (getById/resolve), so it CAN observe a CE.
                throw ce
            } catch (error: Throwable) {
                failRotation(sessionEpoch, "rotateTunnel failed: ${error.message}")
            }
        }
    }

    /**
     * Give up on rotation, FAIL-CLOSED.
     *
     * "Just keep the tunnel established" does not hold on the path that matters: the all-servers-
     * dead case tears the TUN down *before* bring-up fails, so a give-up can land with
     * `tunInterface == null` and hand the user's traffic straight back to the clear network —
     * and whether that happens would depend on where bring-up died (after `establish()` = contained,
     * before = exposed), which is worse than either consistent answer. So when this session should
     * own a tunnel and has none, re-establish a BLACKHOLE one: same routes and captured apps, no
     * protector, no Xray — packets enter an fd nobody reads and are dropped.
     *
     * Note the DELIBERATE disagreement between the two "states" this leaves behind:
     * [sessionTunnelState] returns to CONNECTED because that is mechanically required — a rotation
     * reserves from CONNECTED, so the re-arm timer could never try again otherwise — while the
     * user-facing [LogRepository] connection state becomes BLACKHOLED (or ERROR when nothing could
     * be contained), which is what the UI and the tile show.
     *
     * The three outcomes are reported DIFFERENTLY on every user-facing surface. In particular the
     * uncontained one must never inherit the reassuring "your connection is paused on purpose"
     * copy — that would tell a user their traffic is safe at the exact moment it is not.
     *
     * **This does not always leave the service running.** Under "disconnect now, stop if the re-arm
     * fails", an UNPROTECTED outcome gets exactly one automatic recovery attempt (a rotation driven
     * from [scheduleFailoverRearmLocked], not a monitor restart — there is no tunnel for a probe to
     * test). If a second give-up is still uncontained, [shouldStopServiceOnGiveUp] fires and this
     * method calls [stopVpn]: an honest off state beats a service that is running and protecting
     * nothing while its own notification tells the user to reconnect. The two contained outcomes
     * never stop the service.
     *
     * Caller must hold `lock`.
     */
    private fun giveUpRotationLocked(sessionEpoch: Long, reason: String) {
        LogRepository.append("Failover: giving up ($reason)")
        // A rotation has THREE exits, and this is the third. The two that commit CONNECTED replay a
        // kill deferred during the rotation; a give-up must DROP it — replaying it up to
        // rotationWindowMs later would tear down a just-restored tunnel and blame an app the user
        // closed long ago. Cleared HERE, in the one funnel every give-up passes through, so no exit
        // below (including the stand-down return) can leave the marker armed.
        val deferredKillLabel = pendingKillLabel.also { pendingKillLabel = null }
        if (sessionTunnelState == SessionTunnelState.ROTATING) {
            sessionTunnelState = SessionTunnelState.CONNECTED
        }
        episodeFailedIds = emptySet()
        stopFailoverMonitorLocked()
        reconcileScreenReceiverLocked(sessionEpoch)

        if (sessionTunnelState != SessionTunnelState.CONNECTED) {
            // Another owner holds this session's tunnel — most importantly the kill-switch's PAUSED
            // state, whose compliance contract is literally "no tunnel must exist". Establishing a
            // blackhole (or overwriting the PAUSED connection state) here would break that outright.
            LogRepository.append(
                "Failover: tunnel is $sessionTunnelState; leaving it to its owner"
            )
            scheduleFailoverRearmLocked(sessionEpoch, retryByRotation = false)
            announceDroppedDeferredKillLocked(deferredKillLabel)
            return
        }

        // hadTunnel is captured BEFORE the establish attempt. Thanks to the teardown in the
        // bring-up-failure arm, a non-null fd here can only be the live, still-proxying tunnel
        // (the no-candidate and thrash-cap give-ups both run before any teardown) — never a
        // half-built one whose Xray state is unknown.
        val hadTunnel = tunInterface != null
        val blackholeEstablished = if (
            shouldEstablishBlackholeTunnel(hasTunnel = hadTunnel, tunnelState = sessionTunnelState)
        ) {
            establishBlackholeTunnelLocked()
        } else {
            false
        }

        val outcome = classifyGiveUpOutcome(hadTunnel, blackholeEstablished)

        if (shouldStopServiceOnGiveUp(outcome, unprotectedRetryConsumed)) {
            // The one automatic recovery attempt has been spent and traffic is STILL not contained.
            // Leaving a service running that cannot protect anything — while its own copy tells the
            // user to reconnect — is the dishonest option. Land in the real off state instead.
            LogRepository.append(
                "Failover: recovery attempt also failed to bring up a tunnel; stopping the VPN"
            )
            giveUpOutcome = null
            unprotectedRetryConsumed = false
            // Both surfaces, because stopVpn clears the foreground notification: the in-app error
            // for a user who is looking, the 1102 error notification (its own id, survives
            // stopForeground) for one who is not.
            LogRepository.emitError(R.string.vpn_failover_stopped_error)
            postErrorNotification(R.string.vpn_failover_stopped_error)
            stopVpn(expectedSessionEpoch = sessionEpoch)
            // After stopVpn there is no tunnel, so this is a no-op by rule rather than by omission:
            // the VPN really is off for the kill-listed app, which is what the deferred kill wanted.
            announceDroppedDeferredKillLocked(deferredKillLabel)
            return
        }

        giveUpOutcome = outcome
        // State first, then the notifications — same ordering discipline as killTunnel.
        LogRepository.setConnectionState(connectionStateForGiveUp(outcome))
        when (outcome) {
            FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL -> {
                LogRepository.append(
                    "Failover: no server to switch to; the current tunnel is still up and traffic " +
                        "stays inside it"
                )
                updateNotification(localizedString(R.string.vpn_status_no_response))
                VpnNotifications.postFailoverNoResponse(this)
            }
            FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE -> {
                LogRepository.append(
                    "Failover: traffic is held in an unread tunnel; nothing leaks to the open network"
                )
                updateNotification(localizedString(R.string.vpn_status_blackholed))
                VpnNotifications.postFailoverBlackholed(this)
            }
            FailoverGiveUpOutcome.UNPROTECTED -> {
                LogRepository.append(
                    "Failover: WARNING - no tunnel could be established, traffic is NOT contained"
                )
                // AGENTS.md: user-visible errors go through LogRepository, not only the log buffer.
                // The Logs screen is not where a user learns their traffic just went clear.
                LogRepository.emitError(R.string.vpn_failover_unprotected_error)
                updateNotification(localizedString(R.string.vpn_status_unprotected))
                VpnNotifications.postFailoverUnprotected(this)
            }
        }

        // Scheduled LAST, and only on the paths that keep the service alive. An unprotected give-up
        // spends its single recovery attempt here; a second one lands in the stop branch above.
        val retryByRotation = outcome == FailoverGiveUpOutcome.UNPROTECTED
        if (retryByRotation) unprotectedRetryConsumed = true
        scheduleFailoverRearmLocked(sessionEpoch, retryByRotation = retryByRotation)
        announceDroppedDeferredKillLocked(deferredKillLabel)
    }

    /**
     * Tells the user that a kill-switch event deferred during a rotation was DROPPED because no
     * server could be reached — so the listed app is still going through the tunnel they asked to
     * have torn down. A silently non-functioning kill-switch is the failure mode this closes.
     *
     * Posts nothing when [deferredKillNoticeLabel] returns null: no kill was deferred, or the
     * give-up left no tunnel at all (in which case the app is not behind a VPN and the claim would
     * be false — those outcomes report themselves on their own surfaces).
     *
     * Caller must hold `lock`, and must have already settled the session state: same ordering
     * discipline as [killTunnel] — state first, then notifications.
     */
    private fun announceDroppedDeferredKillLocked(deferredKillLabel: String?) {
        val label = deferredKillNoticeLabel(
            pendingKillLabel = deferredKillLabel,
            tunnelStillUp = tunInterface != null,
        ) ?: return
        LogRepository.append(
            "Kill-switch: dropping the kill deferred for $label — no server could be reached, so " +
                "the tunnel was not torn down for it"
        )
        VpnNotifications.postKillSwitchNotApplied(this, label)
    }

    /**
     * Clears a give-up state once the tunnel is demonstrably passing traffic again.
     *
     * Driven by [TunnelHealthMonitor]'s recovery callback, because the give-up state would
     * otherwise be able to outlive the condition it describes: the no-candidate and thrash-cap
     * give-ups can both land on a tunnel that is merely having a bad minute, and the monitor only
     * ever reported failure — so the re-armed monitor would probe successfully, never fire again,
     * and the user would be left staring at an error state over a working connection until they
     * stopped and restarted the VPN by hand.
     */
    private fun clearGiveUpStateOnRecovery(sessionEpoch: Long) {
        synchronized(lock) {
            if (!isCurrentSessionLocked(sessionEpoch)) return
            if (giveUpOutcome == null) return
            if (sessionTunnelState != SessionTunnelState.CONNECTED) return
            // Load-bearing: after an UNPROTECTED give-up there is NO tunnel, so the probe travels
            // the clear network and succeeds for the wrong reason. Clearing on that would announce
            // CONNECTED with no VPN at all — the exact lie this fix round exists to remove. That
            // state has no automatic recovery by design; it needs the user action the notification
            // and the in-app error both ask for.
            if (tunInterface == null) return
            LogRepository.append("Failover: tunnel is passing traffic again; clearing the give-up state")
            giveUpOutcome = null
            unprotectedRetryConsumed = false
            // The episode is demonstrably over, so the sliding thrash window starts clean too.
            rotationAttempts = emptyList()
            LogRepository.setConnectionState(VpnConnectionState.CONNECTED)
            updateNotification(localizedString(R.string.vpn_status_connected))
            VpnNotifications.cancelFailoverBlackholed(this)
        }
    }

    /**
     * Re-arm failover once the thrash window has fully elapsed, so a network that recovers on its
     * own self-heals without a manual reconnect.
     *
     * This lives in the single funnel every give-up passes through: the thrash-cap and no-candidate
     * give-ups call [giveUpRotationLocked] directly and never go through [failRotation], so wiring
     * the timer there would leave the common "all servers dead" case permanently disarmed.
     *
     * [retryByRotation] is set only for an UNPROTECTED give-up, where restarting the health monitor
     * would achieve nothing: there is NO tunnel, so its probe travels the clear network, succeeds
     * for the wrong reason, and can never ask for a rotation. That state's one recovery attempt has
     * to be a rotation driven directly from here. Every other give-up leaves a tunnel in place, so
     * the monitor is the right thing to re-arm.
     *
     * Caller must hold `lock`.
     */
    private fun scheduleFailoverRearmLocked(sessionEpoch: Long, retryByRotation: Boolean) {
        val windowMs = failoverSettings.rotationWindowMs
        failoverRearmJob?.cancel()
        failoverRearmJob = serviceScope.launch {
            delay(windowMs)
            // BACKSTOP for the cancel in applyFailoverPreferences: this timer was scheduled under
            // one set of preferences and fires up to an hour later, possibly concurrently with the
            // settings edit that disables the feature. Re-read the flow rather than the captured
            // settings — the user may have edited them during the wait.
            val proceed = synchronized(lock) {
                val ok = shouldFireFailoverRetry(
                    failoverEnabled = FailoverPreferences.state.value.enabled,
                    isCurrentSession = isCurrentSessionLocked(sessionEpoch),
                )
                if (ok) rotationAttempts = emptyList()
                ok
            }
            if (!proceed) {
                LogRepository.append(
                    "Failover: retry timer stood down (feature disabled or session ended)"
                )
                return@launch
            }
            if (retryByRotation) {
                // rotateTunnel re-checks epoch + CONNECTED under the lock itself, so a stale timer
                // is a no-op here too.
                rotateTunnel(sessionEpoch)
                return@launch
            }
            // Re-checks epoch, running and CONNECTED internally, so a stale timer is a no-op.
            applyFailoverPreferences(FailoverPreferences.state.value, sessionEpoch)
        }
    }

    /**
     * Runs one settle-up [step] that follows a COMMITTED rotation, converting a throw into a log
     * line instead of letting it escape.
     *
     * The escape is the whole point. `rotateTunnel`'s body is wrapped in `catch (Throwable) ->
     * failRotation`, which funnels into `giveUpRotationLocked`; after the commit that runs with
     * `sessionTunnelState == CONNECTED` and a live fd, so it classifies `CONTAINED_BY_LIVE_TUNNEL`
     * and writes `BLACKHOLED` over the healthy tunnel the rotation just restored. A committed
     * success must never be reclassifiable by follow-up work.
     *
     * `CancellationException` still propagates — structured-concurrency cancellation
     * (`tunnelOpScope.cancel()` in `onDestroy`) is not a step failure and must not be swallowed,
     * the same rule the surrounding handler follows.
     */
    private fun afterRotationCommitted(step: String, block: () -> Unit) {
        try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Throwable) {
            LogRepository.append("Failover: $step failed after the rotation committed: ${error.message}")
        }
    }

    private fun failRotation(sessionEpoch: Long, logMessage: String) {
        synchronized(lock) {
            if (!isCurrentSessionLocked(sessionEpoch)) return
            LogRepository.append(logMessage)
            giveUpRotationLocked(sessionEpoch, "rotation error")
        }
    }

    /**
     * Establishes a TUN with no reader attached: same session name, MTU, addresses, default routes,
     * DNS servers and split-tunnel plan as a real bring-up, but NO protector registration and NO
     * Xray. Packets enter the fd and are dropped, which is what makes give-up genuinely fail-closed
     * rather than fail-closed-if-you-are-lucky.
     *
     * Caller must hold `lock`, and must only call this while [tunInterface] is null.
     * Returns whether the traffic is now actually contained.
     */
    private fun establishBlackholeTunnelLocked(): Boolean {
        return try {
            // INSIDE the try on purpose. This runs from a give-up, which is itself reached from
            // rotateTunnel's `catch (Throwable)` — and a throw raised inside a catch block escapes
            // that try/catch entirely, landing uncaught on a SupervisorJob with no handler, i.e.
            // process death with the VPN up. Unreachable today (the single call site is guarded by
            // shouldEstablishBlackholeTunnel under the same held lock), but a contract violation
            // must degrade to "uncontained", never to a crash.
            check(tunInterface == null) {
                "Cannot blackhole while the active transition already owns a TUN interface"
            }
            val builder = Builder()
                .setSession(localizedString(R.string.app_name))
                .setMtu(sessionTuning.core.mtu)
                .addAddress("10.7.0.1", 32)
                .addAddress("fd00:1:fd00:1::1", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                // Mirrors bringUpTunnel: DNS is aimed INTO the tun, so resolver traffic is dropped
                // here too instead of falling back to the underlying network's resolvers.
                .addDnsServer("1.1.1.1")
                .also { if (sessionTuning.core.ipv6) it.addDnsServer("2606:4700:4700::1111") }

            // Same plan as a real bring-up, so exactly the same apps stay captured: anything the
            // user split OUT keeps the direct route it already had while connected, and everything
            // else keeps riding the tun — now into the blackhole.
            val splitPrefs = SplitTunnelRepository.load(this)
            val plan = SplitTunnelPlanner.plan(splitPrefs.mode, splitPrefs.packages, packageName)
            plan.allowedPackages.forEach { pkg ->
                try {
                    builder.addAllowedApplication(pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                    LogRepository.append("Blackhole tunnel skipped missing package: $pkg")
                }
            }
            plan.disallowedPackages.forEach { pkg ->
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                    LogRepository.append("Blackhole tunnel skipped missing package: $pkg")
                }
            }

            val pfd = builder.establish()
            if (pfd == null) {
                LogRepository.append("Failover: blackhole establish() returned null")
                false
            } else {
                tunInterface = pfd
                LogRepository.append("Failover: blackhole TUN established with fd=${pfd.fd}")
                true
            }
        } catch (error: Throwable) {
            LogRepository.append("Failover: blackhole TUN could not be established: ${error.message}")
            false
        }
    }

    private inner class KillSwitchListener(
        private val sessionEpoch: Long,
    ) : ForegroundAppMonitor.Listener {
        override fun onControlledAppForeground(packageName: String) {
            val label = runCatching {
                val pm = packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrElse { packageName }
            killTunnel(sessionEpoch, label)
        }

        override fun onControlledAppLeftForeground() {
            reviveTunnel(sessionEpoch)
        }
    }

    private fun applyKillSwitchPreferences(
        prefs: KillSwitchRepository.Preferences,
        sessionEpoch: Long,
    ) {
        synchronized(lock) {
            if (!isCurrentSessionLocked(sessionEpoch)) return
            val shouldRun = prefs.enabled && prefs.packages.isNotEmpty()

            if (!shouldRun) {
                val wasPaused = LogRepository.connectionState.value == VpnConnectionState.PAUSED
                killSwitchMonitor?.stop()
                killSwitchMonitor = null
                // Reconcile instead of unregistering outright: the receiver is now shared with the
                // failover monitor, and an unconditional unregister here would tear it out from
                // under a still-running failover session.
                reconcileScreenReceiverLocked(sessionEpoch)
                // Void any kill deferred during an in-flight revive. The feature is now OFF, so
                // replaying it when the revive commits would park the tunnel PAUSED for a feature the
                // user just disabled — and with the monitor gone, no left-foreground event would ever
                // revive it, stranding the connection until a manual stop/start.
                pendingKillLabel = null
                // If the user disabled the feature (or cleared all packages) while
                // the tunnel was paused, restore the tunnel. Without this the user
                // has to manually stop+restart the VPN to recover.
                if (wasPaused) {
                    reviveTunnel(sessionEpoch)
                }
                return
            }

            if (killSwitchMonitor == null) {
                val source = AndroidUsageStatsEventSource(this)
                val monitor = UsageStatsForegroundAppMonitor(source)
                killSwitchMonitor = monitor
                monitor.start(prefs.packages, KillSwitchListener(sessionEpoch))
                reconcileScreenReceiverLocked(sessionEpoch)
                LogRepository.append("Kill-switch monitor started with ${prefs.packages.size} package(s)")
            } else {
                killSwitchMonitor?.updatePackages(prefs.packages)
            }
        }
    }

    /**
     * Starts, rebuilds, or stops the health monitor to match the current preferences and tunnel
     * state. Mirrors [applyKillSwitchPreferences] — including its stale-session discipline: a
     * superseded epoch returns WITHOUT touching the monitor, so a late emission from an already
     * cancelled observer can never stop the CURRENT session's monitor.
     *
     * The monitor runs ONLY in CONNECTED: in PAUSED there is no tunnel, so every probe would fail
     * and we would "rotate" a tunnel the kill-switch deliberately tore down.
     */
    private fun applyFailoverPreferences(settings: FailoverSettings, sessionEpoch: Long) {
        synchronized(lock) {
            if (!isCurrentSessionLocked(sessionEpoch)) return
            failoverSettings = settings

            if (!settings.enabled) {
                // ROOT FIX for a pending re-arm outliving the setting that authorised it. Gated on
                // `enabled` specifically, NOT on the shouldRun check below: shouldRun is also false
                // in PAUSED/ROTATING, and an unrelated settings save during a kill-switch pause
                // must not silently drop a legitimate pending retry. Disabling the feature must.
                failoverRearmJob?.cancel()
                failoverRearmJob = null
                // Unconditional, for the same reason the cancel above is: the re-arm job is the
                // owner of this reset (scheduleFailoverRearmLocked clears the window when its
                // timer fires, so a re-armed episode starts with a clean budget) and we have just
                // cancelled it. Whoever disables the feature must therefore perform the reset the
                // cancelled job would have. Leaving a stale sliding window in place could only
                // ever DENY the first automatic rotation of the next episode — a spurious give-up
                // charged to attempts made before the user intervened. This is outside the release
                // branch below on purpose: it must also run for UNPROTECTED (which no longer
                // releases) and for a disable with no give-up showing at all.
                rotationAttempts = emptyList()

                val releasedOutcome = giveUpOutcome
                if (shouldReleaseGiveUpOnDisable(settings.enabled, releasedOutcome)) {
                    // The user switched the feature off, and the re-arm we just cancelled was the
                    // only automatic way out of a CONTAINED give-up. Drop the episode state so
                    // nothing stale survives, and stop the alert claiming a repair is pending.
                    //
                    // UNPROTECTED never reaches here (see shouldReleaseGiveUpOnDisable): its
                    // marker is what keeps shouldRestartForRecovery true, i.e. what keeps Connect
                    // alive at all, and its 1105 alert is the user's only remaining warning that
                    // they are on the clear network. Both must survive the disable.
                    // unprotectedRetryConsumed is therefore left alone too — it records that the
                    // single automatic recovery has been spent, which stays true, and it is
                    // meaningless for the two outcomes that do reach here.
                    //
                    // The TUN is deliberately NOT torn down and the connection state deliberately
                    // STAYS BLACKHOLED. Both are load-bearing:
                    //   * tearing the TUN down would drop the user onto the clear network as a
                    //     side effect of a settings change — the exact thing this feature exists
                    //     to prevent;
                    //   * BLACKHOLED is the honest state (traffic really is held), and it is what
                    //     makes connectAction() offer RECONNECT. Switching to ERROR here would
                    //     offer a plain Connect, which startVpn refuses with "VPN already running"
                    //     because the service is still up — a dead button, one state over.
                    // Reconnect (VpnViewModel.reconnect) is the way back to a live tunnel.
                    giveUpOutcome = null
                    unprotectedRetryConsumed = false
                    VpnNotifications.cancelFailoverBlackholed(this)
                    // Two outcomes share the BLACKHOLED state but not the truth: the blackhole
                    // really is holding traffic, while a live tunnel is still proxying and merely
                    // unhealthy. Selecting on the state would tell the second group their
                    // connection is paused when it is not. The UNPROTECTED/null arm is unreachable
                    // now that the predicate excludes it, and stays only for exhaustiveness.
                    when (releasedOutcome) {
                        FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE ->
                            LogRepository.emitError(R.string.vpn_failover_disabled_while_blackholed)
                        FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL ->
                            LogRepository.emitError(R.string.vpn_failover_disabled_while_degraded)
                        FailoverGiveUpOutcome.UNPROTECTED, null -> Unit
                    }
                }
            }

            if (!shouldRunFailoverMonitor(
                    enabled = settings.enabled,
                    running = running,
                    tunnelState = sessionTunnelState,
                )
            ) {
                stopFailoverMonitorLocked()
                reconcileScreenReceiverLocked(sessionEpoch)
                return
            }

            // A live monitor bakes interval/timeout/threshold in at construction, so a timing edit
            // can only land by rebuilding it. An UNCHANGED emission must fall through untouched:
            // the settings StateFlow re-emits on every save, and rebuilding on each one would
            // restart the poll cycle continuously so the tunnel is never actually observed. The
            // non-null check is also what stops the observer stacking duplicate monitors — the fix
            // for the stale post-rotation monitor is to CLEAR the field (see rotateTunnel), never
            // to drop this guard.
            if (failoverMonitor != null) {
                if (!failoverMonitorNeedsRebuild(failoverMonitorSettings, settings)) return
                LogRepository.append("Failover: rebuilding monitor for updated probe timings")
                stopFailoverMonitorLocked()
            }

            failoverMonitorSettings = settings
            failoverMonitor = TunnelHealthMonitor(
                // FIXED target, deliberately NOT PingPreferences.targetUrl. It is half of a routing
                // rule: applyRouting carves this exact host through the proxy in every routing mode,
                // and a static rule cannot cover a user-editable target. Reading the Ping Test
                // setting here also inherited its validation gap — that target is only checked for
                // an http:// prefix, so any non-204 URL would fail every probe forever and drive a
                // rotation storm plus a give-up over perfectly healthy servers.
                probe = Http204HealthProbe(ConfigBuilder.HEALTH_PROBE_TARGET_URL, settings.probeTimeoutMs),
                availability = AndroidNetworkAvailability(applicationContext),
                intervalMs = settings.probeIntervalMs,
                failureThreshold = settings.failureThreshold,
            ).also { monitor ->
                // Named arguments deliberately: onHealthy is declared first so existing
                // trailing-lambda callers keep binding to onUnhealthy, which makes a positional
                // call here easy to mis-read.
                monitor.start(
                    onHealthy = { clearGiveUpStateOnRecovery(sessionEpoch) },
                    onUnhealthy = { rotateTunnel(sessionEpoch) },
                )
            }
            LogRepository.append(
                "Failover monitor started (interval=${settings.probeIntervalMs}ms, " +
                    "threshold=${settings.failureThreshold})"
            )
            reconcileScreenReceiverLocked(sessionEpoch)
        }
    }

    /**
     * Stops and forgets the health monitor. `stop()` (not `pausePolling()`) on purpose: pausing
     * preserves the consecutive-failure count, which would trip instantly the next time polling
     * resumes. Caller must hold `lock`.
     */
    private fun stopFailoverMonitorLocked() {
        failoverMonitor?.stop()
        failoverMonitor = null
        failoverMonitorSettings = null
    }

    /**
     * The screen receiver is shared by the kill-switch and failover monitors. Register while EITHER
     * is live, unregister only when NEITHER is — never let one feature's teardown strand the other.
     * [registerScreenReceiver] is already idempotent (`if (screenReceiver != null) return`) and
     * [unregisterScreenReceiver] already tolerates a non-registered receiver, so this is safe to
     * call on every preference change. Caller must hold `lock`.
     */
    private fun reconcileScreenReceiverLocked(sessionEpoch: Long) {
        if (shouldHoldScreenReceiver(
                killSwitchLive = killSwitchMonitor != null,
                failoverLive = failoverMonitor != null,
            )
        ) {
            registerScreenReceiver(sessionEpoch)
        } else {
            unregisterScreenReceiver()
        }
    }

    private fun registerScreenReceiver(sessionEpoch: Long) {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                // onReceive runs on the main thread. Do NOT take the lifecycle lock here — a connect
                // in flight holds it across the blocking XrayBridge.startXray, which would stall the
                // main thread (ANR risk). Read the lock-free epoch mirror to cheaply reject a stale
                // session, then enqueue the actual monitor mutation onto tunnelOpScope, where it runs
                // under the lock and serializes behind any in-flight kill/revive. The monitor field is
                // re-read and re-checked under the lock there, so this stays race-free.
                if (activeEpochVolatile != sessionEpoch) return
                val action = intent?.action ?: return
                if (action != Intent.ACTION_SCREEN_OFF && action != Intent.ACTION_SCREEN_ON) return
                tunnelOpScope.launch {
                    synchronized(lock) {
                        if (!isCurrentSessionLocked(sessionEpoch)) return@launch
                        // Both monitors share this receiver; each nullable field is independently
                        // null when its feature is off, so this covers every on/off pairing.
                        when (action) {
                            Intent.ACTION_SCREEN_OFF -> {
                                killSwitchMonitor?.pausePolling()
                                failoverMonitor?.pausePolling()
                            }
                            Intent.ACTION_SCREEN_ON -> {
                                killSwitchMonitor?.resumePolling()
                                failoverMonitor?.resumePolling()
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(receiver, filter)
        screenReceiver = receiver
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Throwable) {
                // not registered
            }
        }
        screenReceiver = null
    }

    /**
     * Tears the current session down.
     *
     * [stopService] is false for exactly one caller: `startVpn`'s unprotected-recovery restart,
     * which needs the SESSION torn down but this service instance kept alive so it can immediately
     * start a fresh one. Calling `stopSelf()` there would schedule our own destruction and
     * `onDestroy` would then tear down the session we just started; skipping `stopForeground` also
     * keeps the FGS promotion continuous across the restart instead of dropping and re-taking it.
     */
    private fun stopVpn(expectedSessionEpoch: Long? = null, stopService: Boolean = true) {
        synchronized(lock) {
            if (expectedSessionEpoch != null && !isCurrentSessionLocked(expectedSessionEpoch)) return

            val shouldStop = running
            running = false
            activeSessionEpoch = null
            activeEpochVolatile = null
            sessionTunnelState = SessionTunnelState.STOPPED
            // Drop any kill-switch event deferred during a revive so it can't replay into a later
            // session (epoch is invalidated above under the same lock). Cleared on every teardown
            // path, including the no-live-session early return below.
            pendingKillLabel = null
            // Same discipline for the failover episode state: a stale thrash count carried into a
            // new session would deny its very first rotation, and stale episode failures would skip
            // servers that are perfectly healthy now.
            rotationAttempts = emptyList()
            episodeFailedIds = emptySet()
            giveUpOutcome = null
            unprotectedRetryConsumed = false
            // Keep stop, global TUN/Xray teardown, and the next start admission under one lock.
            // This prevents an old full stop from tearing down a newer session's resources.
            val tailerToStop = logTailer
            logTailer = null

            if (!shouldStop && tunInterface == null) {
                // No live session and no TUN yet — still stop any tailer we extracted
                // defensively and exit cleanly.
                tailerToStop?.stop()
                if (stopService) stopSelf()
                return
            }

            tailerToStop?.stop()

            killSwitchMonitor?.stop()
            killSwitchMonitor = null
            settingsObserverJob?.cancel()
            settingsObserverJob = null
            stopFailoverMonitorLocked()
            failoverSettingsJob?.cancel()
            failoverSettingsJob = null
            failoverRearmJob?.cancel()
            failoverRearmJob = null
            // Unconditional here (the whole session is ending), but it must follow the monitor
            // teardown above so the shared-receiver invariant still holds if anything re-enters.
            unregisterScreenReceiver()

            tearDownTunnelLocked()

            currentProfileId = -1L
            sessionLogFile = null
            sessionTuning = TuningSettings.NONE
            LogRepository.setConnectionState(VpnConnectionState.DISCONNECTED)
            LogRepository.append("VPN stopped")
            // These alerts each live under their own notification id; stopForeground removes none
            // of them.
            VpnNotifications.cancelExposed(this)
            VpnNotifications.cancelFailoverBlackholed(this)
            // 1106 asserts in the present tense that a listed app is still going through the VPN.
            // After a stop that is simply false, and its setAutoCancel(true) only clears it if the
            // user taps it.
            VpnNotifications.cancelKillSwitchNotApplied(this)
            if (stopService) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Resolves a string in the user's chosen app locale. We can't rely on the
     * service's own getString because Service contexts don't pick up per-app locale
     * changes mid-session on API <33 — wrap via SupportedLanguage.localize each call.
     * (Notification channel name/description are still cached by the system at channel
     * creation time; that is an Android limitation and unavoidable here.)
     */
    private fun localizedString(@StringRes resId: Int, vararg args: Any): String =
        SupportedLanguage.localize(this).getString(resId, *args)

    // SDK_INT < O check below is dead at minSdk 29 (dead for any minSdk >= 26), but intentionally
    // retained as a guard should minSdk ever drop below 26; @SuppressLint keeps lint quiet without
    // removing the guard.
    @SuppressLint("ObsoleteSdkInt")
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            localizedString(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = localizedString(R.string.vpn_channel_description)
        manager.createNotificationChannel(channel)

        val errorChannel = NotificationChannel(
            ERROR_CHANNEL_ID,
            localizedString(R.string.vpn_error_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        errorChannel.description = localizedString(R.string.vpn_error_channel_description)
        manager.createNotificationChannel(errorChannel)

        VpnNotifications.createExposedChannel(this)
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.boykisser_notification_icon)
            .setContentTitle(localizedString(R.string.vpn_notification_title))
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            // Show the FGS notification immediately. Android 12+ otherwise defers the
            // foreground-service notification up to 10s (the system decides per start), which
            // left the status notification missing on cold/first connects. specialUse is not a
            // deferral-exempt FGS type, so we must opt out of deferral explicitly.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true)
            .setDeleteIntent(notificationDismissIntent())
            // A Stop action on the ONGOING notification is the one surface that is present in every
            // running state, including the failover give-up states where the app UI may be closed
            // and the copy is actively telling the user to turn the VPN off.
            .addAction(
                R.drawable.boykisser_notification_icon,
                localizedString(R.string.vpn_notification_action_stop),
                notificationStopIntent()
            )
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(VpnNotifications.NOTIFICATION_ID, buildNotification(contentText))
    }

    /**
     * PendingIntent fired when the user swipes away the ongoing FGS notification.
     * Android 14+ makes ongoing FGS notifications user-dismissable with no opt-out
     * flag, so the deleteIntent lets us re-post and keep the status visible. Targets
     * this already-running foreground service, so getService is not background-blocked.
     */
    private fun notificationDismissIntent(): PendingIntent {
        val intent = Intent(this, XrayVpnService::class.java)
            .setAction(ACTION_NOTIFICATION_DISMISSED)
        return PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * PendingIntent behind the ongoing notification's Stop action. Same shape as
     * [notificationDismissIntent] — an explicit service Intent, not background-blocked because the
     * target is this already-running foreground service. The distinct request code is defensive
     * rather than strictly required (PendingIntent matching runs `Intent.filterEquals`, which does
     * compare the action, so the differing actions already separate them); it keeps them separate
     * even if one of these Intents ever loses its action.
     */
    private fun notificationStopIntent(): PendingIntent {
        val intent = Intent(this, XrayVpnService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Re-posts the notification matching the current state after a user dismissal. */
    private fun repostOngoingNotification() {
        when (LogRepository.connectionState.value) {
            VpnConnectionState.PAUSED -> {
                // Two notifications back the paused state (the quiet FGS line + the loud
                // exposed alert); restore both, since either could have been the one swiped.
                updateNotification(localizedString(R.string.vpn_status_paused, lastTriggerLabel))
                VpnNotifications.postExposed(this, lastTriggerLabel, notificationDismissIntent())
            }
            VpnConnectionState.CONNECTING ->
                updateNotification(localizedString(R.string.vpn_status_connecting))
            VpnConnectionState.CONNECTED ->
                updateNotification(localizedString(R.string.vpn_status_connected))
            VpnConnectionState.BLACKHOLED ->
                // The give-up alert (id 1105) is setAutoCancel, so once the user dismisses it this
                // persistent line is their only remaining indication. Only 1101 is restored — 1105
                // was dismissed deliberately and re-posting it would fight the user.
                updateNotification(
                    localizedString(
                        if (giveUpOutcome == FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL) {
                            R.string.vpn_status_no_response
                        } else {
                            R.string.vpn_status_blackholed
                        }
                    )
                )
            VpnConnectionState.ERROR -> {
                // ERROR normally means the session is dying, with nothing ongoing to restore. The
                // uncontained give-up is the exception: the service is still RUNNING, and the line
                // it needs is the honest "not protected" one, never the containment copy.
                if (giveUpOutcome == FailoverGiveUpOutcome.UNPROTECTED) {
                    updateNotification(localizedString(R.string.vpn_status_unprotected))
                }
            }
            VpnConnectionState.DISCONNECTED -> {
                // Nothing ongoing to restore.
            }
        }
    }

    private fun postReviveErrorNotification() {
        postErrorNotification(R.string.vpn_revive_error)
    }

    private fun postPermissionRevokedNotification() {
        postErrorNotification(R.string.vpn_permission_revoked_error)
    }

    private fun postErrorNotification(@StringRes messageRes: Int) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, ERROR_CHANNEL_ID)
            .setSmallIcon(R.drawable.boykisser_notification_icon)
            .setContentTitle(localizedString(R.string.vpn_notification_title))
            .setContentText(localizedString(messageRes))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(ERROR_NOTIFICATION_ID, notification)
    }
}
