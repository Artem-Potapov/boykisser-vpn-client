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
}
