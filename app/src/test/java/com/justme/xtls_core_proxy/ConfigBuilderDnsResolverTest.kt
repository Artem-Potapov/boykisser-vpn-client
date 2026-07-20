package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.config.DnsResolver
import com.justme.xtls_core_proxy.config.DnsQueryStrategy
import com.justme.xtls_core_proxy.config.DnsSettings
import com.justme.xtls_core_proxy.config.TuningSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderDnsResolverTest {
    // A vless config addressed by hostname, so makeSecureDns emits the +local bootstrap pair.
    private val vlessHostname = """
        {"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"proxy.example.com",
        "port":443,"users":[{"id":"u"}]}]},"streamSettings":{"network":"tcp","security":"reality"}}]}
    """.trimIndent()

    private fun dns(config: String) = JSONObject(config).getJSONObject("dns")
    private fun serverAddrs(config: String): List<String> {
        val arr = dns(config).getJSONArray("servers")
        return (0 until arr.length()).map {
            val e = arr.opt(it)
            if (e is JSONObject) e.getString("address") else e.toString()
        }
    }

    @Test fun from_config_is_passthrough() {
        val base = ConfigBuilder.buildRuntimeConfig(vlessHostname, tuning = TuningSettings.NONE)
        val withFromConfig = ConfigBuilder.buildRuntimeConfig(
            vlessHostname, tuning = TuningSettings(dns = DnsSettings.FROM_CONFIG)
        )
        assertEquals(JSONObject(base).getJSONObject("dns").toString(), JSONObject(withFromConfig).getJSONObject("dns").toString())
    }

    @Test fun preset_rewrites_unscoped_and_bootstrap_pairs() {
        val out = ConfigBuilder.buildRuntimeConfig(
            vlessHostname, tuning = TuningSettings(dns = DnsSettings.FROM_CONFIG.copy(resolver = DnsResolver.QUAD9))
        )
        val addrs = serverAddrs(out)
        // No Cloudflare anywhere; Quad9 present in both scoped (+local) and unscoped forms.
        assertTrue(addrs.none { it.contains("1.1.1.1") || it.contains("1.0.0.1") })
        assertTrue(addrs.any { it == "https://9.9.9.9/dns-query" })
        assertTrue(addrs.any { it == "https+local://9.9.9.9/dns-query" })
    }

    @Test fun hostname_custom_pins_via_hosts() {
        val out = ConfigBuilder.buildRuntimeConfig(
            vlessHostname,
            tuning = TuningSettings(dns = DnsSettings(DnsResolver.CUSTOM, "https://doh.myhost.net/dns-query", "5.6.7.8", DnsQueryStrategy.USE_IP))
        )
        val hosts = dns(out).getJSONObject("hosts")
        assertEquals("5.6.7.8", hosts.getString("doh.myhost.net"))
        assertTrue(serverAddrs(out).any { it == "https://doh.myhost.net/dns-query" })
    }

    @Test fun query_strategy_is_forced() {
        val out = ConfigBuilder.buildRuntimeConfig(
            vlessHostname, tuning = TuningSettings(dns = DnsSettings.FROM_CONFIG.copy(resolver = DnsResolver.GOOGLE, queryStrategy = DnsQueryStrategy.USE_IPV4))
        )
        assertEquals("UseIPv4", dns(out).getString("queryStrategy"))
    }

    @Test fun preset_pairs_bootstrap_failover_not_collapsed() {
        // F4: a preset swap must map the two scoped +local bootstrap entries PAIRWISE to the preset's
        // primary/secondary IPs, not collapse both onto the primary — otherwise the failover pair is lost.
        val out = ConfigBuilder.buildRuntimeConfig(
            vlessHostname, tuning = TuningSettings(dns = DnsSettings.FROM_CONFIG.copy(resolver = DnsResolver.QUAD9))
        )
        val arr = dns(out).getJSONArray("servers")
        val scoped = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { it.optString("address").startsWith("https+local://") }
        val addrs = scoped.map { it.getString("address") }
        assertTrue("expected primary bootstrap, got $addrs", addrs.any { it == "https+local://9.9.9.9/dns-query" })
        assertTrue("expected secondary bootstrap, got $addrs", addrs.any { it == "https+local://149.112.112.112/dns-query" })
        assertTrue("expected two distinct scoped bootstrap entries, got $addrs", addrs.toSet().size == 2)
    }

    @Test fun custom_bootstrap_preserves_port_and_path() {
        // F5: a CUSTOM resolver's port + path must survive into the scoped bootstrap rewrite, not be
        // collapsed to a hardcoded `/dns-query`.
        val out = ConfigBuilder.buildRuntimeConfig(
            vlessHostname,
            tuning = TuningSettings(
                dns = DnsSettings(DnsResolver.CUSTOM, "https://doh.example.com:8443/resolve", "1.2.3.4", DnsQueryStrategy.USE_IP)
            )
        )
        val arr = dns(out).getJSONArray("servers")
        val scoped = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        assertTrue(scoped.any { it.optString("address") == "https+local://1.2.3.4:8443/resolve" })
        // The unscoped custom entry (with its original host, port, and path) is still present.
        assertTrue(serverAddrs(out).any { it == "https://doh.example.com:8443/resolve" })
    }

    @Test fun custom_bracketed_ipv6_resolver_bootstraps_with_brackets_no_pin() {
        // WB-NEW-2: a bracketed-IPv6 CUSTOM DoH URL is an IP literal (no pin needed); its scoped +local
        // bootstrap must keep the brackets, not cascade into a malformed unbracketed address.
        val out = ConfigBuilder.buildRuntimeConfig(
            vlessHostname,
            tuning = TuningSettings(dns = DnsSettings(DnsResolver.CUSTOM, "https://[2606:4700:4700::1111]/dns-query", "", DnsQueryStrategy.USE_IP))
        )
        val addrs = serverAddrs(out)
        assertTrue(
            "scoped +local bootstrap must keep the bracketed v6 host; addrs=$addrs",
            addrs.any { it == "https+local://[2606:4700:4700::1111]/dns-query" }
        )
        assertTrue("unscoped custom entry present", addrs.any { it == "https://[2606:4700:4700::1111]/dns-query" })
        assertTrue("no unbracketed/malformed v6 bootstrap; addrs=$addrs", addrs.none { it.contains("//2606:") })
    }

    @Test fun preset_preserves_config_owned_scoped_server() {
        // makeSecureDns preserves a pasted config's own secure domain-scoped resolver; the overlay
        // must rewrite ONLY the +local proxy-hostname bootstrap entries, not this one.
        val withScopedServer = """
            {"dns":{"servers":[
                "https://1.1.1.1/dns-query",
                {"address":"https://dns.corp.example/dns-query","domains":["corp.internal"]}
            ]},
            "outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"proxy.example.com",
            "port":443,"users":[{"id":"u"}]}]},"streamSettings":{"network":"tcp","security":"reality"}}]}
        """.trimIndent()
        val out = ConfigBuilder.buildRuntimeConfig(
            withScopedServer, tuning = TuningSettings(dns = DnsSettings.FROM_CONFIG.copy(resolver = DnsResolver.QUAD9))
        )
        val arr = dns(out).getJSONArray("servers")
        val scoped = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        // Config-owned scoped entry survives with its own address and scope.
        assertTrue(scoped.any {
            it.optString("address") == "https://dns.corp.example/dns-query" &&
                it.getJSONArray("domains").toString() == """["corp.internal"]"""
        })
        // The proxy-hostname bootstrap IS rewritten to the chosen resolver's +local IP form.
        assertTrue(scoped.any {
            it.optString("address") == "https+local://9.9.9.9/dns-query" &&
                it.getJSONArray("domains").toString().contains("full:proxy.example.com")
        })
    }

    // --- IPv6-off degrade (design 2026-07-20): a v6-only CUSTOM resolver is unusable while IPv6 is
    // off (applyCoreSettings' ::/0->block kills its dial), so applyDns degrades it to Cloudflare v4. ---

    private val ipv6Off = com.justme.xtls_core_proxy.config.XrayCoreSettings.DEFAULT.copy(ipv6 = false)

    @Test fun ipv6_off_degrades_bracketed_v6_custom_resolver_to_cloudflare_v4() {
        val out = ConfigBuilder.buildRuntimeConfig(
            vlessHostname,
            tuning = TuningSettings(
                dns = DnsSettings(DnsResolver.CUSTOM, "https://[2606:4700:4700::1111]/dns-query", "", DnsQueryStrategy.USE_IP),
                core = ipv6Off,
            )
        )
        val addrs = serverAddrs(out)
        assertTrue("degraded resolver must be Cloudflare v4; addrs=$addrs", addrs.any { it == "https://1.1.1.1/dns-query" })
        assertTrue("no v6 literal survives the degrade; addrs=$addrs", addrs.none { it.contains("2606:") })
    }

    @Test fun ipv6_off_degrades_hostname_with_v6_pin_to_cloudflare_v4() {
        val out = ConfigBuilder.buildRuntimeConfig(
            vlessHostname,
            tuning = TuningSettings(
                dns = DnsSettings(DnsResolver.CUSTOM, "https://doh.example.com/dns-query", "2606:4700:4700::1111", DnsQueryStrategy.USE_IP),
                core = ipv6Off,
            )
        )
        val addrs = serverAddrs(out)
        assertTrue("degraded to Cloudflare v4; addrs=$addrs", addrs.any { it == "https://1.1.1.1/dns-query" })
        assertTrue("no v6 pin/host survives; addrs=$addrs", addrs.none { it.contains("2606:") })
        // Degraded to a preset -> no custom hostname pin remains.
        assertTrue("no hosts pin for the dropped hostname", !dns(out).has("hosts") || !dns(out).getJSONObject("hosts").has("doh.example.com"))
    }

    @Test fun ipv6_on_preserves_v6_custom_resolver() {
        val out = ConfigBuilder.buildRuntimeConfig(
            vlessHostname,
            tuning = TuningSettings(
                dns = DnsSettings(DnsResolver.CUSTOM, "https://[2606:4700:4700::1111]/dns-query", "", DnsQueryStrategy.USE_IP),
                core = com.justme.xtls_core_proxy.config.XrayCoreSettings.DEFAULT, // ipv6 = true
            )
        )
        val addrs = serverAddrs(out)
        assertTrue("v6 resolver preserved when IPv6 on; addrs=$addrs", addrs.any { it == "https://[2606:4700:4700::1111]/dns-query" })
    }

    @Test fun ipv6_off_preserves_v4_custom_resolver() {
        val out = ConfigBuilder.buildRuntimeConfig(
            vlessHostname,
            tuning = TuningSettings(
                dns = DnsSettings(DnsResolver.CUSTOM, "https://1.2.3.4/dns-query", "", DnsQueryStrategy.USE_IP),
                core = ipv6Off,
            )
        )
        val addrs = serverAddrs(out)
        assertTrue("v4 custom must NOT be degraded; addrs=$addrs", addrs.any { it == "https://1.2.3.4/dns-query" })
        assertTrue("no false Cloudflare fallback; addrs=$addrs", addrs.none { it == "https://1.1.1.1/dns-query" })
    }
}
