package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.config.RoutingCountry
import com.justme.xtls_core_proxy.config.RoutingMode
import com.justme.xtls_core_proxy.config.RoutingSettings
import com.justme.xtls_core_proxy.config.TuningSettings
import com.justme.xtls_core_proxy.config.XrayCoreSettings
import com.justme.xtls_core_proxy.config.XrayDomainStrategy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderCoreTest {
    private val vless = """
        {"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"1.2.3.4","port":443,
        "users":[{"id":"u"}]}]},"streamSettings":{"network":"tcp","security":"reality"}}]}
    """.trimIndent()

    private fun root(config: String) = JSONObject(config)
    private fun sniffing(config: String) =
        root(config).getJSONArray("inbounds").getJSONObject(0).optJSONObject("sniffing")

    @Test fun default_is_noop() {
        val base = ConfigBuilder.buildRuntimeConfig(vless, tuning = TuningSettings.NONE)
        val withDefault = ConfigBuilder.buildRuntimeConfig(vless, tuning = TuningSettings(core = XrayCoreSettings.DEFAULT))
        assertEquals(base, withDefault)
    }

    @Test fun ipv6_off_blocks_and_forces_ipv4() {
        val out = ConfigBuilder.buildRuntimeConfig(vless, tuning = TuningSettings(core = XrayCoreSettings.DEFAULT.copy(ipv6 = false)))
        val rules = root(out).getJSONObject("routing").getJSONArray("rules")
        // ::/0 block at index 1 (right after port-53).
        val second = rules.getJSONObject(1)
        assertEquals("::/0", second.getJSONArray("ip").getString(0))
        assertTrue(root(out).getJSONObject("dns").getString("queryStrategy") == "UseIPv4")
    }

    @Test fun custom_mtu_rewrites_tun_inbound() {
        val out = ConfigBuilder.buildRuntimeConfig(vless, tuning = TuningSettings(core = XrayCoreSettings.DEFAULT.copy(mtu = 1360)))
        assertEquals(1360, root(out).getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings").getInt("MTU"))
    }

    @Test fun sniffing_added_when_toggle_on() {
        val out = ConfigBuilder.buildRuntimeConfig(vless, tuning = TuningSettings(core = XrayCoreSettings.DEFAULT.copy(sniffing = true)))
        val s = sniffing(out)!!
        assertTrue(s.getBoolean("enabled"))
        assertTrue(s.getBoolean("routeOnly"))
    }

    @Test fun sniffing_added_when_routing_needs_it_even_if_toggle_off() {
        val out = ConfigBuilder.buildRuntimeConfig(
            vless,
            tuning = TuningSettings(core = XrayCoreSettings.DEFAULT, routing = RoutingSettings.USER_DEFAULT.copy(blockAds = true))
        )
        assertTrue(sniffing(out)!!.getBoolean("enabled"))
    }

    @Test fun sniffing_absent_when_neither() {
        val out = ConfigBuilder.buildRuntimeConfig(vless, tuning = TuningSettings.NONE)
        assertFalse(root(out).getJSONArray("inbounds").getJSONObject(0).has("sniffing"))
    }

    @Test fun domain_strategy_from_config_preserved() {
        val withStrategy = JSONObject(vless).put("routing", JSONObject().put("domainStrategy", "IPIfNonMatch")).toString()
        val out = ConfigBuilder.buildRuntimeConfig(withStrategy, tuning = TuningSettings(core = XrayCoreSettings.DEFAULT))
        assertEquals("IPIfNonMatch", root(out).getJSONObject("routing").getString("domainStrategy"))
    }

    /**
     * ipv6-off × BLOCKED_ONLY is the most load-bearing overlay interaction: the ::/0 block must land
     * right after port-53 (index 1) WITHOUT shadowing the BLOCKED_ONLY DoH-guard (IPv4-literal resolver
     * IPs → proxy) or displacing the catch-all direct from last. Pins the fail-closed ordering the T7
     * reviewer verified only analytically.
     */
    @Test fun ipv6_off_with_blocked_only_preserves_doh_guard_and_catch_all() {
        val out = ConfigBuilder.buildRuntimeConfig(
            vless,
            tuning = TuningSettings(
                core = XrayCoreSettings.DEFAULT.copy(ipv6 = false),
                routing = RoutingSettings.USER_DEFAULT.copy(mode = RoutingMode.BLOCKED_ONLY, country = RoutingCountry.RU),
            ),
        )
        val rules = root(out).getJSONObject("routing").getJSONArray("rules")
        // port-53 stays index 0; ::/0 -> block inserted at index 1.
        assertEquals(53, rules.getJSONObject(0).getInt("port"))
        assertEquals("::/0", rules.getJSONObject(1).getJSONArray("ip").getString(0))
        // The DoH-guard (resolver IPv4 literals -> proxy) survives at index >= 2 (not shadowed by ::/0).
        var dohGuardIdx = -1
        for (i in 2 until rules.length()) {
            val r = rules.getJSONObject(i)
            if (r.optString("outboundTag") == "proxy" && (r.optJSONArray("ip")?.toString()?.contains("1.1.1.1") == true)) {
                dohGuardIdx = i
                break
            }
        }
        assertTrue("BLOCKED_ONLY DoH-guard proxy rule must survive at index >= 2", dohGuardIdx >= 2)
        // Catch-all direct remains last.
        val last = rules.getJSONObject(rules.length() - 1)
        assertEquals("tcp,udp", last.getString("network"))
        assertEquals("direct", last.getString("outboundTag"))
    }
}
