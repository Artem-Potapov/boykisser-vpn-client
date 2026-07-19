package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.config.DnsQueryStrategy
import com.justme.xtls_core_proxy.config.DnsResolver
import com.justme.xtls_core_proxy.config.DnsSettings
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

    // FIX 1 — a BLOCKED_ONLY country with no blocked dataset must not fail OPEN.
    @Test fun blocked_only_without_dataset_never_emits_catch_all_direct() {
        val out = ConfigBuilder.buildRuntimeConfig(
            vless,
            tuning = TuningSettings(
                routing = RoutingSettings(RoutingMode.BLOCKED_ONLY, RoutingCountry.IR, bypassLan = true, blockAds = false)
            )
        )
        val r = rules(out)
        val last = r.getJSONObject(r.length() - 1)
        val isCatchAllDirect = last.optString("network") == "tcp,udp" && last.optString("outboundTag") == "direct"
        assertFalse("IR BLOCKED_ONLY must degrade to PROXY_ALL, not force all traffic direct", isCatchAllDirect)
    }

    // FIX 2 — a hostname resolver pinned via dns.hosts must also be guarded on the ip side.
    @Test fun doh_guard_covers_pinned_ip_of_hostname_custom_resolver() {
        val out = ConfigBuilder.buildRuntimeConfig(
            vless,
            tuning = TuningSettings(
                dns = DnsSettings(
                    resolver = DnsResolver.CUSTOM,
                    customUrl = "https://dns.comss.one/dns-query",
                    customPinnedIp = "92.223.65.71",
                    queryStrategy = DnsQueryStrategy.USE_IP,
                ),
                routing = RoutingSettings(RoutingMode.BLOCKED_ONLY, RoutingCountry.RU, bypassLan = false, blockAds = false)
            )
        )
        val guardIpRule = ruleItems(out).firstOrNull {
            val o = JSONObject(it)
            o.has("ip") && o.optString("outboundTag") == "proxy"
        }
        assertTrue(
            "DoH-guard must emit an ip rule covering the dns.hosts-pinned resolver IP; rules=${ruleItems(out)}",
            guardIpRule != null && guardIpRule.contains("92.223.65.71")
        )
    }

    // M2 — a bracketed IPv6 custom DoH resolver must be guarded as an ip rule, not a broken domain rule.
    @Test fun doh_guard_handles_bracketed_ipv6_custom_resolver() {
        val out = ConfigBuilder.buildRuntimeConfig(
            vless,
            tuning = TuningSettings(
                dns = DnsSettings(
                    resolver = DnsResolver.CUSTOM,
                    customUrl = "https://[2606:4700:4700::1111]/dns-query",
                    customPinnedIp = "",
                    queryStrategy = DnsQueryStrategy.USE_IP,
                ),
                routing = RoutingSettings(RoutingMode.BLOCKED_ONLY, RoutingCountry.RU, bypassLan = false, blockAds = false)
            )
        )
        val items = ruleItems(out)
        // Order-independent: BLOCKED_ONLY also emits blocked-geoip ip+proxy rules, so search all of them.
        val hasGuardIpRule = items.any {
            val o = JSONObject(it)
            o.has("ip") && o.optString("outboundTag") == "proxy" && it.contains("2606:4700:4700::1111")
        }
        assertTrue(
            "DoH-guard must emit an ip rule covering the bracketed IPv6 resolver; rules=$items",
            hasGuardIpRule
        )
        assertFalse(
            "DoH-guard must not fall back to a broken 'full:[2606' domain rule; rules=$items",
            items.any { it.contains("full:[2606") }
        )
    }

    // FIX 3 — a redirecting freedom outbound must not be adopted as the "direct" helper.
    @Test fun redirecting_freedom_outbound_is_not_adopted_as_direct() {
        val withRedirect = """
            {"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"1.2.3.4","port":443,"users":[{"id":"u"}]}]},"streamSettings":{"network":"tcp","security":"reality"}},
            {"tag":"direct","protocol":"freedom","settings":{"redirect":"127.0.0.1:40000"}}]}
        """.trimIndent()
        val out = ConfigBuilder.buildRuntimeConfig(
            withRedirect,
            tuning = TuningSettings(
                routing = RoutingSettings(RoutingMode.EXCEPT_COUNTRY, RoutingCountry.RU, bypassLan = true, blockAds = false)
            )
        )
        // The injected direct rules are the geoip:private LAN rule + the EXCEPT_COUNTRY country rules.
        val lanRule = ruleItems(out).first { it.contains("geoip:private") }
        val lanTag = JSONObject(lanRule).getString("outboundTag")
        assertFalse("LAN/direct rules must not point at the redirecting outbound", lanTag == "direct")

        val outbounds = JSONObject(out).getJSONArray("outbounds")
        var matches = 0
        for (i in 0 until outbounds.length()) {
            if (outbounds.getJSONObject(i).optString("tag") == lanTag) matches++
        }
        assertEquals("exactly one outbound may carry the direct-helper tag '$lanTag'", 1, matches)
    }

    // FIX 4 — production path: a pasted panel config that natively carries ext: rules.
    @Test fun ext_rules_in_pasted_config_stripped_from_ping_config() {
        val pasted = """
            {"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"1.2.3.4","port":443,"users":[{"id":"u"}]}]},"streamSettings":{"network":"tcp","security":"reality"}}],
            "routing":{"rules":[{"type":"field","ip":["ext:geoip_RU.dat:ru-blocked"],"outboundTag":"proxy"},
            {"type":"field","domain":["ext:geosite_RU.dat:ru-blocked"],"outboundTag":"proxy"}]}}
        """.trimIndent()
        val ping = ConfigBuilder.toPingTestConfig(pasted)
        assertFalse(JSONObject(ping).optJSONObject("routing")?.optJSONArray("rules").toString().contains("ext:"))
    }
}
