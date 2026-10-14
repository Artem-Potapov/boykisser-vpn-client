package com.justme.xtls_core_proxy.log

/**
 * The shared four-state membership invariant used by active-profile highlighting, tile active
 * rendering, and tile Stop eligibility. This is not a general service-running, connectability,
 * disconnectability, label, notification, or recovery predicate.
 */
internal fun VpnConnectionState.isActiveSessionState(): Boolean = when (this) {
    VpnConnectionState.CONNECTING,
    VpnConnectionState.CONNECTED,
    VpnConnectionState.PAUSED,
    VpnConnectionState.BLACKHOLED -> true

    VpnConnectionState.DISCONNECTED,
    VpnConnectionState.ERROR -> false
}
