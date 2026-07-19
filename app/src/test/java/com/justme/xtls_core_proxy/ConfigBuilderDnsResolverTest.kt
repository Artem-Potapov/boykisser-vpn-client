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
}
