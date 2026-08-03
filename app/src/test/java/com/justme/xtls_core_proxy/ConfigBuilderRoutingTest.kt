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

    // I1 — the failover health probe must traverse the PROXY, not just the tunnel. BLOCKED_ONLY's
    // final `network: tcp,udp -> direct` catch-all would otherwise send the 204 GET out through
    // freedom on a protect()'d socket, where it succeeds with the proxy completely dead — so the
    // watchdog could never fire, and the healthy branch would clear a legitimate give-up.
    @Test fun blocked_only_carves_the_health_probe_target_through_the_proxy() {
        val out = ConfigBuilder.buildRuntimeConfig(
            vless,
            tuning = TuningSettings(
                routing = RoutingSettings(RoutingMode.BLOCKED_ONLY, RoutingCountry.RU, bypassLan = true, blockAds = true)
            )
        )
        val items = ruleItems(out)
        val carveOutIndex = items.indexOfFirst {
            val o = JSONObject(it)
            o.optJSONArray("domain")?.toString()?.contains("full:${ConfigBuilder.HEALTH_PROBE_HOST}") == true
        }
        assertTrue(
            "BLOCKED_ONLY must route the health-probe host to the proxy; rules=$items",
            carveOutIndex >= 0
        )
        assertEquals(
            "the carve-out must move traffic TOWARD the proxy, never away from it",
            "proxy",
            JSONObject(items[carveOutIndex]).getString("outboundTag")
        )
        // Ahead of the LAN and ad-block rules, exactly like its dohGuardRules sibling: a later
        // geosite:category-ads-all -> block match would turn the probe into a permanent failure.
        val lanIndex = items.indexOfFirst { it.contains("geoip:private") }
        val adsIndex = items.indexOfFirst { it.contains("geosite:category-ads-all") }
        assertTrue("carve-out must precede the LAN rule; rules=$items", carveOutIndex < lanIndex)
        assertTrue("carve-out must precede the ad-block rule; rules=$items", carveOutIndex < adsIndex)
        // And obviously before the direct catch-all, which is last.
        assertTrue(carveOutIndex < items.lastIndex)
    }

    // The carve-out is emitted in EVERY mode, not just BLOCKED_ONLY. The old scoping rested on the
    // claim that the other modes' only direct rule is the geoip:private LAN bypass, which cannot
    // match a public host. That was false twice over: EXCEPT_COUNTRY emits three country-scoped
    // DIRECT rules (directTags — geoip:ru can match an anycast Cloudflare address), and every mode
    // preserves the imported config's own rules. Either can route the probe direct, which returns
    // 204 with the proxy dead — the same watchdog-can-never-fire failure BLOCKED_ONLY's catch-all
    // caused. The rule only ever moves traffic TOWARD the proxy, so it is safe everywhere.
    @Test fun the_health_probe_carve_out_is_emitted_in_every_mode() {
        for (mode in RoutingMode.entries) {
            val out = ConfigBuilder.buildRuntimeConfig(
                vless,
                tuning = TuningSettings(
                    routing = RoutingSettings(mode, RoutingCountry.RU, bypassLan = true, blockAds = true)
                )
            )
            val items = ruleItems(out)
            val carveOutIndex = items.indexOfFirst {
                JSONObject(it).optJSONArray("domain")?.toString()
                    ?.contains("full:${ConfigBuilder.HEALTH_PROBE_HOST}") == true
            }
            assertTrue("$mode must carve the health-probe host to the proxy; rules=$items", carveOutIndex >= 0)
            assertEquals(
                "$mode: the carve-out must move traffic TOWARD the proxy, never away from it",
                "proxy",
                JSONObject(items[carveOutIndex]).getString("outboundTag")
            )
            // Ahead of every rule that could route it elsewhere — the LAN bypass, ads -> block, and
            // (EXCEPT_COUNTRY) the country direct rules. Three rules may precede it and none can
            // claim the probe: the port-53 -> dns-out rule, the BLOCKED_ONLY DoH guards, and — with
            // XRAY IPv6 off, which this test does not exercise — applyCoreSettings' ::/0 -> block
            // inserted at index 1, which cannot match because IPv6 off also forces queryStrategy to
            // UseIPv4.
            val firstDivertIndex = items.indexOfFirst {
                JSONObject(it).optString("outboundTag") !in setOf("dns-out", "proxy")
            }
            assertTrue(
                "$mode: carve-out must precede every non-proxy rule; rules=$items",
                firstDivertIndex < 0 || carveOutIndex < firstDivertIndex
            )
        }
    }

    // R2 — the degrade path. BLOCKED_ONLY + a country with no blocked dataset becomes PROXY_ALL at
    // effectiveRoutingMode, so it must emit the PROXY_ALL rule set: no DoH guard, no blocked-list
    // proxy rules, no direct catch-all — and the carve-out, which every mode now gets. Pinning it
    // here keeps the emission keyed to the EFFECTIVE mode rather than the raw setting.
    @Test fun degraded_blocked_only_emits_the_proxy_all_rule_set() {
        val out = ConfigBuilder.buildRuntimeConfig(
            vless,
            tuning = TuningSettings(
                routing = RoutingSettings(RoutingMode.BLOCKED_ONLY, RoutingCountry.IR, bypassLan = true, blockAds = false)
            )
        )
        val items = ruleItems(out)
        val proxyRules = items.filter { JSONObject(it).optString("outboundTag") == "proxy" }
        assertEquals(
            "the degraded mode must emit exactly one proxy rule — the carve-out, and no DoH guard; rules=$items",
            1,
            proxyRules.size
        )
        assertTrue(
            "that one proxy rule must be the health-probe carve-out; rules=$items",
            proxyRules.single().contains("full:${ConfigBuilder.HEALTH_PROBE_HOST}")
        )
        val last = JSONObject(items.last())
        assertFalse(
            "the degraded mode must not emit the direct catch-all; rules=$items",
            last.optString("network") == "tcp,udp" && last.optString("outboundTag") == "direct"
        )
    }

    // The domain rule can only match if the tun inbound sniffs the HTTP Host header. BLOCKED_ONLY
    // forces sniffing via routingNeedsDomainRules, and this pins that dependency: drop the forcing
    // and the carve-out silently stops matching while the JSON still looks correct.
    @Test fun blocked_only_forces_sniffing_so_the_carve_out_can_match() {
        val out = ConfigBuilder.buildRuntimeConfig(
            vless,
            tuning = TuningSettings(
                routing = RoutingSettings(RoutingMode.BLOCKED_ONLY, RoutingCountry.RU, bypassLan = false, blockAds = false)
            )
        )
        val sniffing = JSONObject(out).getJSONArray("inbounds").getJSONObject(0).getJSONObject("sniffing")
        assertTrue(sniffing.getBoolean("enabled"))
        assertTrue(sniffing.getJSONArray("destOverride").toString().contains("http"))
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

    // WB-NEW-3 — adopting an untagged freedom must not reuse a tag already taken by another outbound
    // (here a blackhole mis-tagged "direct"), or Xray refuses to start on a duplicate tag.
    @Test fun untagged_freedom_gets_unique_tag_when_direct_already_taken() {
        val pasted = """
            {"outbounds":[
            {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"1.2.3.4","port":443,"users":[{"id":"u"}]}]},"streamSettings":{"network":"tcp","security":"reality"}},
            {"protocol":"freedom"},
            {"tag":"direct","protocol":"blackhole"}]}
        """.trimIndent()
        val out = ConfigBuilder.buildRuntimeConfig(
            pasted,
            tuning = TuningSettings(routing = RoutingSettings(RoutingMode.EXCEPT_COUNTRY, RoutingCountry.RU, bypassLan = true, blockAds = false))
        )
        val obs = JSONObject(out).getJSONArray("outbounds")
        val tags = (0 until obs.length()).mapNotNull { obs.optJSONObject(it)?.optString("tag")?.takeIf { t -> t.isNotBlank() } }
        assertEquals("outbound tags must be unique — adopting the untagged freedom must not duplicate 'direct': $tags", tags.size, tags.toSet().size)
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
