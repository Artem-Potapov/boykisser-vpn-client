package com.justme.xtls_core_proxy.split

data class SplitTunnelPlan(
    val allowedPackages: List<String>,
    val disallowedPackages: List<String>
)

object SplitTunnelPlanner {
    /**
     * Whole-app tunneling: the app itself always rides the tunnel (its own
     * non-Xray traffic must be tunneled, not leak direct); Xray's own sockets
     * bypass the tun via VpnService.protect(), not via app exclusion.
     *
     * ALLOW_ONLY                -> tunnel the user's apps PLUS self (deduped).
     * BLOCK_ALL_EXCEPT_SELECTED -> tunnel everything except the user's apps;
     *                              self is never excluded.
     */
    fun plan(
        mode: SplitTunnelMode,
        userPackages: Set<String>,
        selfPackage: String
    ): SplitTunnelPlan = when (mode) {
        SplitTunnelMode.ALLOW_ONLY -> SplitTunnelPlan(
            allowedPackages = (userPackages - selfPackage).toList() + selfPackage,
            disallowedPackages = emptyList()
        )
        SplitTunnelMode.BLOCK_ALL_EXCEPT_SELECTED -> SplitTunnelPlan(
            allowedPackages = emptyList(),
            disallowedPackages = (userPackages - selfPackage).toList()
        )
    }
}
