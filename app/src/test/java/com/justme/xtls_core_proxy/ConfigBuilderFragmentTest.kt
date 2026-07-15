package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.config.FragmentationSettings
import com.justme.xtls_core_proxy.config.TuningSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigBuilderFragmentTest {

    private val fragOn = TuningSettings(
        FragmentationSettings(enabled = true, packets = "tlshello", length = "100-200", interval = "10-20")
    )

    // Returns the first outbound's sockopt.fragment, or null if no outbound carries one.
    private fun fragmentOf(configJson: String): JSONObject? {
        val obs = JSONObject(configJson).optJSONArray("outbounds") ?: return null
        for (i in 0 until obs.length()) {
            val f = obs.optJSONObject(i)
                ?.optJSONObject("streamSettings")
                ?.optJSONObject("sockopt")
                ?.optJSONObject("fragment")
            if (f != null) return f
        }
        return null
    }

    @Test
    fun appliesFragmentToTcpVlessOutbound() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@demo.example:443?security=none&type=tcp"
        val out = ConfigBuilder.buildRuntimeConfig(input = uri, tuning = fragOn)

        val fragment = fragmentOf(out)
        assertNotNull("expected sockopt.fragment on the tcp vless outbound", fragment)
        assertEquals("tlshello", fragment!!.getString("packets"))
        assertEquals("100-200", fragment.getString("length"))
        assertEquals("10-20", fragment.getString("interval"))

        // Must not clobber the secure-DNS ForceIP that makeSecureDns wrote into the same sockopt.
        val sockopt = JSONObject(out).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("sockopt")
        assertEquals("ForceIP", sockopt.getString("domainStrategy"))
    }

    @Test
    fun skipsFragmentForHysteria2() {
        val uri = "hy2://secret@example.com:443/?sni=cdn.example.com#HY2"
        val out = ConfigBuilder.buildRuntimeConfig(input = uri, tuning = fragOn)
        assertNull("Hysteria2 (QUIC) must not get sockopt.fragment", fragmentOf(out))
    }

    @Test
    fun skipsFragmentForQuicNetwork() {
        val json = """
            {"outbounds":[{"protocol":"vless","tag":"proxy",
              "settings":{"vnext":[{"address":"h.example","port":443,
                "users":[{"id":"11111111-1111-1111-1111-111111111111","encryption":"none"}]}]},
              "streamSettings":{"network":"quic","security":"none"}}]}
        """.trimIndent()
        val out = ConfigBuilder.buildRuntimeConfig(input = json, tuning = fragOn)
        assertNull("quic transport must not get sockopt.fragment", fragmentOf(out))
    }

    @Test
    fun disabledTuningIsNoop() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@demo.example:443?security=none&type=tcp"
        val out = ConfigBuilder.buildRuntimeConfig(input = uri, tuning = TuningSettings.NONE)
        assertNull("disabled fragmentation must not write a fragment block", fragmentOf(out))
    }

    @Test
    fun pingTestConfigCarriesNoFragment() {
        // The ping path builds without tuning, so probes stay clean/comparable.
        val uri = "vless://11111111-1111-1111-1111-111111111111@demo.example:443?security=none&type=tcp"
        val stored = ConfigBuilder.toProfileStorageConfig(uri)
        val probe = ConfigBuilder.toPingTestConfig(stored)
        assertNull("probe config must never carry fragmentation", fragmentOf(probe))
    }
}
