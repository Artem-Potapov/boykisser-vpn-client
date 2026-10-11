package com.justme.xtls_core_proxy.vpn

import com.justme.xtls_core_proxy.split.SplitTunnelPlan

internal data class TunCapturePlan(
    val sessionName: String,
    val mtu: Int,
    val addresses: List<Pair<String, Int>>,
    val routes: List<Pair<String, Int>>,
    val dnsServers: List<String>,
    val splitPlan: SplitTunnelPlan,
)
