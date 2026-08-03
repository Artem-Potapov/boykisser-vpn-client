package com.justme.xtls_core_proxy.vpn

import com.justme.xtls_core_proxy.failover.FailoverSettings
import com.justme.xtls_core_proxy.log.VpnConnectionState

/**
 * Returns whether an asynchronous lifecycle callback still owns the currently running session.
 *
 * A `running` flag alone is insufficient: a full stop can be followed by a new start before an
 * old background callback arrives. The callback's epoch must therefore match the active epoch.
 */
internal fun acceptsSessionLifecycleCallback(
    running: Boolean,
    activeSessionEpoch: Long?,
    callbackSessionEpoch: Long,
): Boolean = running && activeSessionEpoch == callbackSessionEpoch

/** Per-session tunnel ownership states, mutated only while the VPN lifecycle lock is held. */
internal enum class SessionTunnelState {
    STARTING,
    CONNECTED,
    PAUSED,
    REVIVING,
    ROTATING,
    STOPPED,
}

internal fun ownsTunnelTransition(
    running: Boolean,
    activeSessionEpoch: Long?,
    callbackSessionEpoch: Long,
    tunnelState: SessionTunnelState,
    expectedState: SessionTunnelState,
): Boolean =
    acceptsSessionLifecycleCallback(running, activeSessionEpoch, callbackSessionEpoch) &&
        tunnelState == expectedState

/** A paused session may reserve exactly one asynchronous revive transition. */
internal fun canReserveRevive(
    running: Boolean,
    activeSessionEpoch: Long?,
    callbackSessionEpoch: Long,
    tunnelState: SessionTunnelState,
): Boolean = ownsTunnelTransition(
    running = running,
    activeSessionEpoch = activeSessionEpoch,
    callbackSessionEpoch = callbackSessionEpoch,
    tunnelState = tunnelState,
    expectedState = SessionTunnelState.PAUSED,
)

/**
 * A CONNECTED session may reserve exactly one asynchronous failover rotation.
 *
 * Deliberately NOT expressed via [canReserveRevive]: revive reserves from PAUSED, rotation from
 * CONNECTED. Sharing one state would let a kill-switch revive and a failover rotation each believe
 * they own the same transition.
 */
internal fun canReserveRotation(
    running: Boolean,
    activeSessionEpoch: Long?,
    callbackSessionEpoch: Long,
    tunnelState: SessionTunnelState,
): Boolean = ownsTunnelTransition(
    running = running,
    activeSessionEpoch = activeSessionEpoch,
    callbackSessionEpoch = callbackSessionEpoch,
    tunnelState = tunnelState,
    expectedState = SessionTunnelState.CONNECTED,
)

/**
 * Whether a kill-switch event landing mid-transition must be DEFERRED (recorded and replayed once
 * the transition commits) rather than dropped.
 *
 * A kill can only tear down a CONNECTED tunnel. Both transitional states take the tunnel down and
 * bring it back up — the kill-switch's own revive (`REVIVING`) and a failover rotation (`ROTATING`)
 * — so a kill arriving in either window would otherwise be silently and permanently lost, because
 * the foreground monitor is edge-triggered and never re-fires it, leaving the tunnel CONNECTED with
 * a kill-listed app in the foreground. True only for the CURRENT session: a CONNECTED session kills
 * immediately, and PAUSED/STARTING/STOPPED/stale/stopped states have nothing to defer to.
 *
 * This is the ONE home for the rule. A `REVIVING`-only variant existed alongside it for a while,
 * production-dead but carrying the rule's entire test coverage — so the tests read as thorough while
 * exercising a function nothing called. Do not reintroduce a second copy.
 */
internal fun shouldDeferKillDuringTransition(
    running: Boolean,
    activeSessionEpoch: Long?,
    callbackSessionEpoch: Long,
    tunnelState: SessionTunnelState,
): Boolean =
    acceptsSessionLifecycleCallback(running, activeSessionEpoch, callbackSessionEpoch) &&
        (tunnelState == SessionTunnelState.REVIVING || tunnelState == SessionTunnelState.ROTATING)

/**
 * The app label to announce when a failover give-up DISCHARGES a kill-switch event that was
 * deferred by [shouldDeferKillDuringTransition] — or null when nothing may be announced.
 *
 * A rotation has three exits, not two. The two that commit CONNECTED replay the deferred kill;
 * the give-up funnel is the third, and it must drop the event instead: replaying it up to
 * `rotationWindowMs` later would tear down a just-restored tunnel and blame an app that closed
 * long ago. Dropping it silently is not acceptable either — the user asked for the VPN to be off
 * for that app and it is not — hence this notice.
 *
 * [tunnelStillUp] is what keeps the notice honest. It is the give-up's own fd state, so it covers
 * every case in one rule: the two CONTAINED outcomes still own a TUN (a live one, or the blackhole
 * — either way the listed app still sees a VPN, which is exactly what the kill-switch exists to
 * prevent), while UNPROTECTED and the give-up that stops the service own no TUN at all. In those
 * the app is NOT behind a VPN, so telling the user it still is would be the opposite of the truth,
 * and both already report themselves on their own surfaces.
 */
internal fun deferredKillNoticeLabel(
    pendingKillLabel: String?,
    tunnelStillUp: Boolean,
): String? = pendingKillLabel?.takeIf { tunnelStillUp }

/**
 * The screen on/off receiver is SHARED by the kill-switch and failover monitors: hold it while
 * EITHER is live, release it only when NEITHER is.
 *
 * Previously the receiver's whole lifecycle belonged to the kill-switch, which failed failover two
 * ways: with failover on and the kill-switch off (the default pairing) no receiver existed at all,
 * and turning the kill-switch off mid-session tore the receiver out from under a running failover
 * monitor.
 */
internal fun shouldHoldScreenReceiver(killSwitchLive: Boolean, failoverLive: Boolean): Boolean =
    killSwitchLive || failoverLive

/**
 * The health monitor is meaningful only against a live tunnel in the current session.
 *
 * `CONNECTED` only, deliberately: in `PAUSED` the kill-switch has torn the tunnel down on purpose,
 * so every probe would fail and the engine would "rotate" a tunnel nobody wants back yet; in
 * `ROTATING`/`REVIVING`/`STARTING` another owner is mid-transition and will re-apply afterwards.
 */
internal fun shouldRunFailoverMonitor(
    enabled: Boolean,
    running: Boolean,
    tunnelState: SessionTunnelState,
): Boolean = enabled && running && tunnelState == SessionTunnelState.CONNECTED

/**
 * Whether a settings emission changes an input the LIVE monitor already baked in, and therefore
 * requires stopping and rebuilding it.
 *
 * Only the three timing fields qualify — they are constructor arguments of `TunnelHealthMonitor` /
 * `Http204HealthProbe` and cannot be changed on a running instance. `enabled` is handled by
 * [shouldRunFailoverMonitor]; `maxRotations` / `rotationWindowMs` are read fresh at rotation time.
 * An unchanged emission MUST return false: the settings StateFlow re-emits on every save, and
 * rebuilding on each one would restart the poll cycle continuously so the tunnel is never observed.
 * A null [builtFrom] means we have no record of what the live monitor was built from, so rebuild.
 */
internal fun failoverMonitorNeedsRebuild(
    builtFrom: FailoverSettings?,
    next: FailoverSettings,
): Boolean = builtFrom == null ||
    builtFrom.probeIntervalMs != next.probeIntervalMs ||
    builtFrom.probeTimeoutMs != next.probeTimeoutMs ||
    builtFrom.failureThreshold != next.failureThreshold

/**
 * Whether giving up on failover must re-establish a blackhole TUN — an fd nobody reads, so packets
 * are dropped instead of falling back to the clear network.
 *
 * The all-servers-dead path tears the TUN down before bring-up fails, so give-up can otherwise end
 * with no fd at all, and whether the user is exposed would depend on where bring-up died. That is
 * worse than either consistent answer, hence the re-establish.
 *
 * `CONNECTED` only. `PAUSED` is the kill-switch's deliberate no-tunnel state and its compliance
 * contract is "no tunnel must exist" — establishing one there would break it outright. Every other
 * state has a different owner mid-transition who will establish (or tear down) itself.
 */
internal fun shouldEstablishBlackholeTunnel(
    hasTunnel: Boolean,
    tunnelState: SessionTunnelState,
): Boolean = !hasTunnel && tunnelState == SessionTunnelState.CONNECTED

/**
 * What a failover give-up actually left behind. Three physically different situations that must
 * never share one message to the user.
 */
internal enum class FailoverGiveUpOutcome {
    /** No tunnel existed and a blackhole was established: traffic is deliberately dropped. */
    CONTAINED_BY_BLACKHOLE,

    /**
     * The current profile's tunnel is still up and still proxying — the no-candidate and thrash-cap
     * give-ups both run BEFORE any teardown. Nothing was blocked; there was simply nowhere to
     * rotate to. Telling this user "your traffic is blocked" would be false.
     */
    CONTAINED_BY_LIVE_TUNNEL,

    /** No tunnel and the blackhole could not be established: the user IS on the clear network. */
    UNPROTECTED,
}

/**
 * Classifies a give-up from the two facts the service knows: whether a TUN existed before the
 * attempt, and whether establishing a blackhole succeeded.
 *
 * [hadTunnel] wins outright — if an fd was already owned we never tried to blackhole, so
 * [blackholeEstablished] carries no information in that case.
 */
internal fun classifyGiveUpOutcome(
    hadTunnel: Boolean,
    blackholeEstablished: Boolean,
): FailoverGiveUpOutcome = when {
    hadTunnel -> FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL
    blackholeEstablished -> FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE
    else -> FailoverGiveUpOutcome.UNPROTECTED
}

/**
 * The user-facing connection state a give-up outcome produces.
 *
 * Extracted from the service so the branch cannot be inverted silently: this is the ONLY runtime
 * producer of [VpnConnectionState.BLACKHOLED], and BLACKHOLED is a live, stoppable state whereas
 * ERROR is not. It was extracted because its three pure siblings ([classifyGiveUpOutcome],
 * [shouldStopServiceOnGiveUp], [shouldRestartForRecovery]) already had tests while this branch was
 * still inline in the service and had none. It is covered now — `SessionLifecycleDecisionTest`'s
 * `onlyTheUncontainedGiveUpMapsToError` pins all three outcomes.
 */
internal fun connectionStateForGiveUp(outcome: FailoverGiveUpOutcome): VpnConnectionState =
    when (outcome) {
        FailoverGiveUpOutcome.UNPROTECTED -> VpnConnectionState.ERROR
        FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE,
        FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL -> VpnConnectionState.BLACKHOLED
    }

/**
 * "Disconnect now, stop if the re-arm fails": whether a give-up should switch the service off
 * rather than schedule another re-arm.
 *
 * Only [FailoverGiveUpOutcome.UNPROTECTED] can ever stop the service, and only once its single
 * automatic recovery attempt has already been spent ([unprotectedRetryConsumed]). The first
 * unprotected give-up must NOT stop: forfeiting the re-arm would switch the VPN off without the
 * user asking. The two contained outcomes never stop at all — traffic is held in a tunnel either
 * way, so there is nothing to be honest about turning off.
 */
internal fun shouldStopServiceOnGiveUp(
    outcome: FailoverGiveUpOutcome,
    unprotectedRetryConsumed: Boolean,
): Boolean = outcome == FailoverGiveUpOutcome.UNPROTECTED && unprotectedRetryConsumed

/**
 * Whether a give-up's re-arm timer may still act when it finally fires.
 *
 * The timer is scheduled under one set of preferences and fires up to an hour later under another,
 * so it must re-check [failoverEnabled] at the firing point rather than trusting the settings that
 * authorised it. Without this a user who turned auto-failover OFF could still be handed an
 * automatic server rotation — and, on the unprotected retry path, an automatic VPN shutdown.
 * Cancelling the job on disable is the root fix; this is the backstop for a timer that fires
 * concurrently with the settings edit.
 */
internal fun shouldFireFailoverRetry(
    failoverEnabled: Boolean,
    isCurrentSession: Boolean,
): Boolean = failoverEnabled && isCurrentSession

/**
 * Whether an incoming start request should RESTART the running session instead of taking
 * `startVpn`'s "VPN already running" early return.
 *
 * Deliberately narrow: only the UNPROTECTED give-up, where the service is running but owns no
 * tunnel and traffic is not contained, so a start is the user acting on copy that told them to turn
 * the VPN off and on again or pick another server. Everything else keeps the early return, because
 * the tile, `START_REDELIVER_INTENT` crash recovery and stray/duplicated intents all depend on
 * "start while running" being idempotent — a general restart would let a stray intent bounce a
 * perfectly healthy tunnel. The two contained outcomes are excluded for the same reason: they still
 * hold a TUN, so there is nothing for a restart to rescue.
 */
internal fun shouldRestartForRecovery(
    running: Boolean,
    giveUpOutcome: FailoverGiveUpOutcome?,
): Boolean = running && giveUpOutcome == FailoverGiveUpOutcome.UNPROTECTED

/**
 * Which profile id must be written back to `ActiveProfileRepository` when `startVpn` takes its
 * "VPN already running" early return — or `null` when nothing should be written.
 *
 * Every connect path (the per-server rows, the profile-actions dialog, the QS tile, always-on via
 * `resolveActiveAndStart`, connect-to-fastest) records the requested profile as active BEFORE
 * dispatching the start. When the start is refused, traffic keeps flowing through
 * [currentProfileId] while the UI and the tile would go on labelling [requestedProfileId] as the
 * connected server — the one fact a VPN client must never get wrong.
 *
 * Applies ONLY to the refusal; the UNPROTECTED recovery restart really does go on to start
 * [requestedProfileId], so it must keep the caller's write. `null` covers the two cases where a
 * write would be wrong or pointless: the request already matches the running session, and no real
 * session profile exists (the field carries -1L when unset and Room never issues a non-positive id,
 * so restoring one would point the app at nothing).
 */
internal fun activeProfileIdToRestoreOnRefusedStart(
    requestedProfileId: Long,
    currentProfileId: Long,
): Long? = currentProfileId.takeIf { it > 0L && it != requestedProfileId }

/**
 * Whether switching auto-failover OFF must also release a give-up state it left behind.
 *
 * Disabling cancels the re-arm job, which is the ONLY automatic recovery from a **contained**
 * give-up. Leaving `giveUpOutcome` set would therefore strand the user behind a blackhole TUN as
 * a direct result of turning the feature off — the most natural reaction to it having gone wrong.
 *
 * [FailoverGiveUpOutcome.UNPROTECTED] is DELIBERATELY EXCLUDED, and the exclusion is the whole
 * point of this predicate being three-valued rather than a null check. That outcome has no re-arm
 * to be stranded behind: it owns no TUN, and its recovery is the `startVpn` restart that
 * [shouldRestartForRecovery] unlocks — keyed off this very marker. Releasing it would leave the
 * service RUNNING, holding no tunnel, with the user on the clear network and every Connect surface
 * taking `startVpn`'s "VPN already running" early return; with the monitor stopped no second
 * give-up could fire [shouldStopServiceOnGiveUp] either, so nothing would ever end that state.
 * Only Disconnect would work, and the loudest warning would have been retracted on the way in.
 */
internal fun shouldReleaseGiveUpOnDisable(
    enabled: Boolean,
    giveUpOutcome: FailoverGiveUpOutcome?,
): Boolean = !enabled &&
    giveUpOutcome != null &&
    giveUpOutcome != FailoverGiveUpOutcome.UNPROTECTED

/**
 * Whether an incoming start request may claim the single `pendingProfileId` slot.
 *
 * THREE callers write that slot: the manual Connect lambda, connect-fastest's winner delivery, and
 * the QS-tile hand-off. A system permission dialog can park any of them in it for minutes, and that
 * dialog is a modal CONTINUATION of the request that raised it — so answering it must complete THAT
 * request. Hence one uniform rule, "whichever request parked first wins", rather than a per-source
 * hierarchy: no refusal occurs on an overwrite, so nothing downstream would ever notice the
 * substitution, and a rule that applied to only some of the three writers would be an asymmetry the
 * user has no way to observe or predict.
 */
internal fun shouldOverwritePendingConnect(pending: Long, incoming: Long): Boolean =
    pending == -1L || pending == incoming
