package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.ConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderDnsTest {

    private val dirty = """
        {"outbounds":[{"protocol":"vless","tag":"proxy"},{"protocol":"freedom","tag":"direct"}],
         "routing":{"rules":[{"type":"field","port":53,"outboundTag":"direct"}]}}
    """.trimIndent()

    private val absent = """
        {"outbounds":[{"protocol":"vless","tag":"proxy"},{"protocol":"freedom","tag":"direct"}],
         "routing":{"rules":[{"type":"field","ip":["geoip:private"],"outboundTag":"direct"}]}}
    """.trimIndent()

    private val secureRouted = """
        {"dns":{"servers":["https://1.1.1.1/dns-query"]},
         "outbounds":[{"protocol":"vless","tag":"proxy"},{"protocol":"dns","tag":"dns-out"}],
         "routing":{"rules":[{"type":"field","port":53,"outboundTag":"dns-out"}]}}
    """.trimIndent()

    // --- dnsDiagnosis ---

    @Test
    fun dnsDiagnosis_dirty_whenPort53RoutedToFreedom() {
        assertEquals(ConfigBuilder.DnsStatus.DIRTY, ConfigBuilder.dnsDiagnosis(dirty))
    }

    @Test
    fun dnsDiagnosis_absent_whenNoDnsConfig() {
        assertEquals(ConfigBuilder.DnsStatus.ABSENT, ConfigBuilder.dnsDiagnosis(absent))
    }

    @Test
    fun dnsDiagnosis_secure_whenPort53RoutedToDnsOut() {
        assertEquals(ConfigBuilder.DnsStatus.SECURE, ConfigBuilder.dnsDiagnosis(secureRouted))
    }

    @Test
    fun dnsDiagnosis_dirty_whenPort53StringRangeRoutedToFreedom() {
        val cfg = """
            {"outbounds":[{"protocol":"freedom","tag":"direct"}],
             "routing":{"rules":[{"type":"field","port":"53-54","outboundTag":"direct"}]}}
        """.trimIndent()
        assertEquals(ConfigBuilder.DnsStatus.DIRTY, ConfigBuilder.dnsDiagnosis(cfg))
    }

    // --- makeSecureDns ---

    @Test
    fun makeSecureDns_makesDirtyConfigSecure() {
        val fixed = ConfigBuilder.makeSecureDns(dirty)
        assertEquals(ConfigBuilder.DnsStatus.SECURE, ConfigBuilder.dnsDiagnosis(fixed))
        val root = JSONObject(fixed)
        assertEquals(
            ConfigBuilder.CLOUDFLARE_DOH,
            root.getJSONObject("dns").getJSONArray("servers").getString(0)
        )
        val firstRule = root.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
        assertEquals("dns-out", firstRule.getString("outboundTag"))
        assertEquals(53, firstRule.getInt("port"))
    }

    @Test
    fun makeSecureDns_stripsPlaintextResolver() {
        val cfg = """
            {"dns":{"servers":["8.8.8.8","1.0.0.1"]},
             "outbounds":[{"protocol":"vless","tag":"proxy"}]}
        """.trimIndent()
        val servers = JSONObject(ConfigBuilder.makeSecureDns(cfg))
            .getJSONObject("dns").getJSONArray("servers")
        for (i in 0 until servers.length()) {
            assertTrue(servers.getString(i).startsWith("https://"))
        }
        assertEquals(ConfigBuilder.CLOUDFLARE_DOH, servers.getString(0))
    }

    @Test
    fun makeSecureDns_keepsExistingDohResolverAndDoesNotForceCloudflare() {
        val cfg = """
            {"dns":{"servers":["https://dns.yandex.com/dns-query","8.8.8.8"]},
             "outbounds":[{"protocol":"vless","tag":"proxy"}]}
        """.trimIndent()
        val servers = JSONObject(ConfigBuilder.makeSecureDns(cfg))
            .getJSONObject("dns").getJSONArray("servers")
        val list = (0 until servers.length()).map { servers.getString(it) }
        assertTrue(list.contains("https://dns.yandex.com/dns-query"))
        assertFalse(list.contains("8.8.8.8"))
        assertFalse(list.contains(ConfigBuilder.CLOUDFLARE_DOH))
    }

    @Test
    fun makeSecureDns_setsForceIpOnProxyOutbound() {
        val cfg = """
            {"outbounds":[{"protocol":"vless","tag":"proxy"},{"protocol":"freedom","tag":"direct"}]}
        """.trimIndent()
        val proxy = JSONObject(ConfigBuilder.makeSecureDns(cfg)).getJSONArray("outbounds").getJSONObject(0)
        assertEquals(
            "ForceIP",
            proxy.getJSONObject("streamSettings").getJSONObject("sockopt").getString("domainStrategy")
        )
    }

    @Test
    fun makeSecureDns_isIdempotent() {
        val twice = ConfigBuilder.makeSecureDns(ConfigBuilder.makeSecureDns(dirty))
        assertEquals(ConfigBuilder.DnsStatus.SECURE, ConfigBuilder.dnsDiagnosis(twice))
        val root = JSONObject(twice)
        val obs = root.getJSONArray("outbounds")
        var dnsOut = 0
        for (i in 0 until obs.length()) if (obs.getJSONObject(i).optString("tag") == "dns-out") dnsOut++
        assertEquals(1, dnsOut)
        val rules = root.getJSONObject("routing").getJSONArray("rules")
        var port53 = 0
        for (i in 0 until rules.length()) if (rules.getJSONObject(i).optInt("port") == 53) port53++
        assertEquals(1, port53)
    }

    // --- buildRuntimeConfig integration ---

    @Test
    fun buildRuntimeConfig_vless_isSecureWithForceIp() {
        val cfg = ConfigBuilder.buildRuntimeConfig(
            "vless://11111111-1111-1111-1111-111111111111@demo.example:443?security=none"
        )
        assertEquals(ConfigBuilder.DnsStatus.SECURE, ConfigBuilder.dnsDiagnosis(cfg))
        val proxy = JSONObject(cfg).getJSONArray("outbounds").getJSONObject(0)
        assertEquals(
            "ForceIP",
            proxy.getJSONObject("streamSettings").getJSONObject("sockopt").getString("domainStrategy")
        )
    }

    @Test
    fun buildRuntimeConfig_absentJson_getsSecured() {
        assertEquals(
            ConfigBuilder.DnsStatus.SECURE,
            ConfigBuilder.dnsDiagnosis(ConfigBuilder.buildRuntimeConfig(absent))
        )
    }

    @Test
    fun buildRuntimeConfig_dirtyJson_isNormalizedSecure() {
        // Decision (spec, Error Handling): the runtime path normalizes, it does NOT throw.
        assertEquals(
            ConfigBuilder.DnsStatus.SECURE,
            ConfigBuilder.dnsDiagnosis(ConfigBuilder.buildRuntimeConfig(dirty))
        )
    }
}
