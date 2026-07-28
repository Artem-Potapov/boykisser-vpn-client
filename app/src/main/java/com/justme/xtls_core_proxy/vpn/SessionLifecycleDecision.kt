package com.justme.xtls_core_proxy.vpn

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
 * Whether a kill-switch event that lands while a revive is in flight must be DEFERRED (recorded and
 * replayed after the revive commits) rather than dropped. A kill can only tear down a CONNECTED
 * tunnel; if the same session is mid-revive (`REVIVING`), the event would otherwise be silently and
 * permanently lost, because the foreground monitor is edge-triggered and never re-fires it. This is
 * true only for the CURRENT session and only in `REVIVING` — a CONNECTED session kills immediately,
 * and PAUSED/stale/stopped states have nothing to defer to.
 */
internal fun shouldDeferKillDuringRevive(
    running: Boolean,
    activeSessionEpoch: Long?,
    callbackSessionEpoch: Long,
    tunnelState: SessionTunnelState,
): Boolean = ownsTunnelTransition(
    running = running,
    activeSessionEpoch = activeSessionEpoch,
    callbackSessionEpoch = callbackSessionEpoch,
    tunnelState = tunnelState,
    expectedState = SessionTunnelState.REVIVING,
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
 * Whether a kill-switch event landing mid-transition must be DEFERRED and replayed rather than
 * dropped. Generalises [shouldDeferKillDuringRevive] to also cover ROTATING: a failover rotation
 * tears the tunnel down and brings it back up, so a kill arriving in that window would otherwise be
 * permanently lost and leave the tunnel CONNECTED with a kill-listed app in the foreground.
 */
internal fun shouldDeferKillDuringTransition(
    running: Boolean,
    activeSessionEpoch: Long?,
    callbackSessionEpoch: Long,
    tunnelState: SessionTunnelState,
): Boolean =
    acceptsSessionLifecycleCallback(running, activeSessionEpoch, callbackSessionEpoch) &&
        (tunnelState == SessionTunnelState.REVIVING || tunnelState == SessionTunnelState.ROTATING)
