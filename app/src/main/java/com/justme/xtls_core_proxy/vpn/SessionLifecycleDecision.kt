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
