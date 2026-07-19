package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.ConfigBuilder
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
}
