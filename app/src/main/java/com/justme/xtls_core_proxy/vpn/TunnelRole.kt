package com.justme.xtls_core_proxy.vpn

/**
 * The policy-facing projection of [TunnelInterfaceOwner], derived and **never stored**. A second
 * copy of the role is exactly the drift hazard the sealed owner exists to remove, so consumers call
 * [toRole] at the point of use rather than caching it in a field.
 */
internal enum class TunnelRole {
    NONE,
    LIVE_PROXY,
    UNREAD_ROTATION_BRIDGE,
    UNREAD_BLACKHOLE,
}

/**
 * Exhaustive over the sealed owner: a fifth variant makes this a compile error rather than a
 * silently-misclassified tunnel.
 */
internal fun <Handle> TunnelInterfaceOwner<Handle>.toRole(): TunnelRole = when (this) {
    is TunnelInterfaceOwner.None -> TunnelRole.NONE
    is TunnelInterfaceOwner.LiveProxy -> TunnelRole.LIVE_PROXY
    is TunnelInterfaceOwner.UnreadRotationBridge -> TunnelRole.UNREAD_ROTATION_BRIDGE
    is TunnelInterfaceOwner.UnreadBlackhole -> TunnelRole.UNREAD_BLACKHOLE
}
