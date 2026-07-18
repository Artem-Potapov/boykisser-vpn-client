package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.config.RoutingCountry
import com.justme.xtls_core_proxy.config.RoutingMode
import com.justme.xtls_core_proxy.config.RoutingSettings
import com.justme.xtls_core_proxy.config.TuningSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderRoutingTest {
    private val vless = """
        {"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"1.2.3.4","port":443,
        "users":[{"id":"u"}]}]},"streamSettings":{"network":"tcp","security":"reality"}}]}
    """.trimIndent()

    private fun rules(config: String) = JSONObject(config).getJSONObject("routing").getJSONArray("rules")
    private fun ruleItems(config: String): List<String> {
        val r = rules(config); val out = mutableListOf<String>()
        for (i in 0 until r.length()) out += r.getJSONObject(i).toString()
        return out
    }

    @Test fun null_routing_is_passthrough() {
        val base = ConfigBuilder.buildRuntimeConfig(vless, tuning = TuningSettings.NONE)
        val nullRouting = ConfigBuilder.buildRuntimeConfig(vless, tuning = TuningSettings(routing = null))
        assertEquals(rules(base).toString(), rules(nullRouting).toString())
    }

    @Test fun port53_rule_stays_first() {
        val out = ConfigBuilder.buildRuntimeConfig(
            vless, tuning = TuningSettings(routing = RoutingSettings.USER_DEFAULT.copy(mode = RoutingMode.EXCEPT_COUNTRY))
        )
        assertEquals(53, rules(out).getJSONObject(0).getInt("port"))
    }

    @Test fun blocked_only_appends_catch_all_direct_last() {
        val out = ConfigBuilder.buildRuntimeConfig(
            vless, tuning = TuningSettings(routing = RoutingSettings(RoutingMode.BLOCKED_ONLY, RoutingCountry.RU, bypassLan = true, blockAds = false))
        )
        val r = rules(out)
        val last = r.getJSONObject(r.length() - 1)
        assertEquals("tcp,udp", last.getString("network"))
        assertEquals("direct", last.getString("outboundTag"))
        // A DoH-guard proxy rule sits right after the port-53 rule.
        assertEquals("proxy", r.getJSONObject(1).getString("outboundTag"))
    }

    @Test fun lan_rule_ownership() {
        val withLan = """
            {"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"1.2.3.4","port":443,"users":[{"id":"u"}]}]},"streamSettings":{"network":"tcp","security":"reality"}},{"tag":"direct","protocol":"freedom"}],
            "routing":{"rules":[{"type":"field","ip":["geoip:private"],"outboundTag":"direct"}]}}
        """.trimIndent()
        val off = ConfigBuilder.buildRuntimeConfig(
            withLan, tuning = TuningSettings(routing = RoutingSettings.USER_DEFAULT.copy(bypassLan = false))
        )
        assertFalse(ruleItems(off).any { it.contains("geoip:private") })
    }

    @Test fun ext_rules_stripped_from_ping_config() {
        val runtime = ConfigBuilder.buildRuntimeConfig(
            vless, tuning = TuningSettings(routing = RoutingSettings(RoutingMode.BLOCKED_ONLY, RoutingCountry.RU, bypassLan = false, blockAds = false))
        )
        // Simulate storing then probing: toPingTestConfig must drop ext: rules (asset-less probe core).
        val ping = ConfigBuilder.toPingTestConfig(runtime)
        assertFalse(JSONObject(ping).optJSONObject("routing")?.optJSONArray("rules").toString().contains("ext:"))
    }
}
