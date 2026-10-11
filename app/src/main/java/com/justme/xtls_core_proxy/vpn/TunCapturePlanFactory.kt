package com.justme.xtls_core_proxy.vpn

import com.justme.xtls_core_proxy.split.SplitTunnelPlan

internal object TunCapturePlanFactory {
    fun create(
        sessionName: String,
        mtu: Int,
        ipv6Enabled: Boolean,
        splitPlan: SplitTunnelPlan,
    ): TunCapturePlan {
        val addresses = listOf(
            "10.7.0.1" to 32,
            "fd00:1:fd00:1::1" to 128,
        )
        val routes = listOf(
            "0.0.0.0" to 0,
            "::" to 0,
        )
        val dnsServers = buildList {
            add("1.1.1.1")
            if (ipv6Enabled) add("2606:4700:4700::1111")
        }
        return TunCapturePlan(
            sessionName = sessionName,
            mtu = mtu,
            addresses = addresses,
            routes = routes,
            dnsServers = dnsServers,
            splitPlan = splitPlan,
        )
    }
}
