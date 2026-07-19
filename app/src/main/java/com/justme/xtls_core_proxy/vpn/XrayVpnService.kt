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
                LogRepository.append("VPN already running")
                return
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
                        // mid-revive, DEFER the event (record it, replay after the revive commits)
                        // rather than dropping it — the foreground monitor is edge-triggered and
                        // would never re-fire this safety event. Any other state (stale epoch,
                        // stopped, already paused) has nothing to defer to, so drop as before.
                        if (shouldDeferKillDuringRevive(
                                running = running,
                                activeSessionEpoch = activeSessionEpoch,
                                callbackSessionEpoch = sessionEpoch,
                                tunnelState = sessionTunnelState,
                            )
                        ) {
                            pendingKillLabel = triggerPackageLabel
                            LogRepository.append(
                                "Kill-switch: deferring kill for $triggerPackageLabel until revive completes"
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
                    // State first, then the notification: notify() is a silent no-op when
                    // POST_NOTIFICATIONS is denied, but writing state ahead keeps the machine
                    // correct even if the exposed-notification build ever throws.
                    LogRepository.setConnectionState(VpnConnectionState.PAUSED)
                    lastTriggerLabel = triggerPackageLabel
                    // Quiet, persistent FGS notification (id 1101, low channel) drops to a
                    // paused status line; the loud heads-up exposed alert is a SEPARATE
                    // notification on the high channel (id 1103) so it can actually alert.
                    updateNotification(localizedString(R.string.vpn_status_paused, triggerPackageLabel))
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
                            updateNotification(localizedString(R.string.vpn_status_connected))
                            // A kill-switch event deferred during this revive must now be replayed
                            // so the tunnel does not stay CONNECTED with the kill-listed app in the
                            // foreground. Clear the pending marker under the same lock, then re-run
                            // the normal kill path (pause + exposed heads-up) for the current epoch.
                            pendingKillLabel.also { pendingKillLabel = null }
                        }
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
                unregisterScreenReceiver()
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
                registerScreenReceiver(sessionEpoch)
                LogRepository.append("Kill-switch monitor started with ${prefs.packages.size} package(s)")
            } else {
                killSwitchMonitor?.updatePackages(prefs.packages)
            }
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
                        when (action) {
                            Intent.ACTION_SCREEN_OFF -> killSwitchMonitor?.pausePolling()
                            Intent.ACTION_SCREEN_ON -> killSwitchMonitor?.resumePolling()
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

    private fun stopVpn(expectedSessionEpoch: Long? = null) {
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
            // Keep stop, global TUN/Xray teardown, and the next start admission under one lock.
            // This prevents an old full stop from tearing down a newer session's resources.
            val tailerToStop = logTailer
            logTailer = null

            if (!shouldStop && tunInterface == null) {
                // No live session and no TUN yet — still stop any tailer we extracted
                // defensively and exit cleanly.
                tailerToStop?.stop()
                stopSelf()
                return
            }

            tailerToStop?.stop()

            killSwitchMonitor?.stop()
            killSwitchMonitor = null
            settingsObserverJob?.cancel()
            settingsObserverJob = null
            unregisterScreenReceiver()

            tearDownTunnelLocked()

            currentProfileId = -1L
            sessionLogFile = null
            sessionTuning = TuningSettings.NONE
            LogRepository.setConnectionState(VpnConnectionState.DISCONNECTED)
            LogRepository.append("VPN stopped")
            // The exposed alert is a separate notification id; stopForeground won't remove it.
            VpnNotifications.cancelExposed(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
            VpnConnectionState.DISCONNECTED, VpnConnectionState.ERROR -> {
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
