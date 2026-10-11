package com.justme.xtls_core_proxy.vpn

import com.justme.xtls_core_proxy.split.SplitTunnelPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TunCapturePlanTest {

    private fun split() = SplitTunnelPlan(
        allowedPackages = listOf("com.example.allowed"),
        disallowedPackages = listOf("com.example.blocked"),
    )

    @Test
    fun ipv6Enabled_producesBothAddressFamiliesAndBothDnsServers() {
        val plan = TunCapturePlanFactory.create(
            sessionName = "xtls",
            mtu = 1500,
            ipv6Enabled = true,
            splitPlan = split(),
        )

        assertEquals("xtls", plan.sessionName)
        assertEquals(1500, plan.mtu)
        assertEquals(
            listOf("10.7.0.1" to 32, "fd00:1:fd00:1::1" to 128),
            plan.addresses,
        )
        assertEquals(
            listOf("0.0.0.0" to 0, "::" to 0),
            plan.routes,
        )
        assertEquals(listOf("1.1.1.1", "2606:4700:4700::1111"), plan.dnsServers)
        assertEquals(split(), plan.splitPlan)
    }

    @Test
    fun ipv6Disabled_keepsIpv6AddressAndRoute_butOnlyIpv4Dns() {
        val plan = TunCapturePlanFactory.create(
            sessionName = "xtls",
            mtu = 1500,
            ipv6Enabled = false,
            splitPlan = split(),
        )

        assertEquals(
            listOf("10.7.0.1" to 32, "fd00:1:fd00:1::1" to 128),
            plan.addresses,
        )
        assertEquals(
            listOf("0.0.0.0" to 0, "::" to 0),
            plan.routes,
        )
        assertEquals(listOf("1.1.1.1"), plan.dnsServers)
    }

    @Test
    fun ipv6Toggle_changesOnlyDnsServers() {
        val enabled = TunCapturePlanFactory.create(
            sessionName = "session",
            mtu = 1280,
            ipv6Enabled = true,
            splitPlan = split(),
        )
        val disabled = TunCapturePlanFactory.create(
            sessionName = "session",
            mtu = 1280,
            ipv6Enabled = false,
            splitPlan = split(),
        )

        assertEquals(enabled.sessionName, disabled.sessionName)
        assertEquals(enabled.mtu, disabled.mtu)
        assertEquals(enabled.addresses, disabled.addresses)
        assertEquals(enabled.routes, disabled.routes)
        assertEquals(enabled.splitPlan, disabled.splitPlan)
        assertEquals(listOf("1.1.1.1", "2606:4700:4700::1111"), enabled.dnsServers)
        assertEquals(listOf("1.1.1.1"), disabled.dnsServers)
    }

    @Test
    fun factory_retainsExactSplitListOrder() {
        val split = SplitTunnelPlan(
            allowedPackages = listOf("com.example.b", "com.example.a"),
            disallowedPackages = listOf("com.example.z", "com.example.y"),
        )

        val plan = TunCapturePlanFactory.create(
            sessionName = "xtls",
            mtu = 1500,
            ipv6Enabled = true,
            splitPlan = split,
        )

        assertEquals(listOf("com.example.b", "com.example.a"), plan.splitPlan.allowedPackages)
        assertEquals(listOf("com.example.z", "com.example.y"), plan.splitPlan.disallowedPackages)
    }

    @Test
    fun factory_doesNotMutateSuppliedSplitLists() {
        val allowed = mutableListOf("com.example.allowed")
        val disallowed = mutableListOf("com.example.blocked")
        val split = SplitTunnelPlan(
            allowedPackages = allowed,
            disallowedPackages = disallowed,
        )

        val plan = TunCapturePlanFactory.create(
            sessionName = "xtls",
            mtu = 1500,
            ipv6Enabled = false,
            splitPlan = split,
        )

        assertEquals(listOf("com.example.allowed"), allowed)
        assertEquals(listOf("com.example.blocked"), disallowed)
        assertEquals(listOf("com.example.allowed"), plan.splitPlan.allowedPackages)
        assertEquals(listOf("com.example.blocked"), plan.splitPlan.disallowedPackages)
        assertSame(split, plan.splitPlan)
    }
}
