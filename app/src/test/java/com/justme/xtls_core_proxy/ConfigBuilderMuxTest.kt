package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.config.FragmentationSettings
import com.justme.xtls_core_proxy.config.MuxSettings
import com.justme.xtls_core_proxy.config.QuicHandling
import com.justme.xtls_core_proxy.config.TuningSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderMuxTest {
    private val muxOn = TuningSettings(mux = MuxSettings(true, 8, 16, QuicHandling.BLOCK))

    private fun vlessTcp(flow: String = "", network: String = "tcp") = """
        {"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"ex.com","port":443,
        "users":[{"id":"u"${if (flow.isBlank()) "" else ",\"flow\":\"$flow\""}}]}]},
        "streamSettings":{"network":"$network","security":"reality"}}]}
    """.trimIndent()

    private fun mux(config: String): JSONObject? =
        JSONObject(config).getJSONArray("outbounds").getJSONObject(0).optJSONObject("mux")

    @Test fun applies_to_vless_tcp() {
        val out = ConfigBuilder.buildRuntimeConfig(vlessTcp(), tuning = muxOn)
        val m = mux(out)!!
        assertTrue(m.getBoolean("enabled"))
        assertEquals(8, m.getInt("concurrency"))
        assertEquals(16, m.getInt("xudpConcurrency"))
        assertEquals("reject", m.getString("xudpProxyUDP443"))
    }

    @Test fun skips_vision_flow() {
        val out = ConfigBuilder.buildRuntimeConfig(vlessTcp(flow = "xtls-rprx-vision"), tuning = muxOn)
        assertFalse(JSONObject(out).getJSONArray("outbounds").getJSONObject(0).has("mux"))
    }

    @Test fun skips_xhttp_and_quic_and_kcp() {
        listOf("xhttp", "quic", "kcp").forEach { net ->
            val out = ConfigBuilder.buildRuntimeConfig(vlessTcp(network = net), tuning = muxOn)
            assertFalse("net=$net", JSONObject(out).getJSONArray("outbounds").getJSONObject(0).has("mux"))
        }
    }

    @Test fun disabled_is_noop_and_preserves_existing_mux() {
        val withMux = """
            {"outbounds":[{"tag":"proxy","protocol":"vless","mux":{"enabled":true,"concurrency":2},
            "settings":{"vnext":[{"address":"ex.com","port":443,"users":[{"id":"u"}]}]},
            "streamSettings":{"network":"tcp","security":"reality"}}]}
        """.trimIndent()
        val out = ConfigBuilder.buildRuntimeConfig(withMux, tuning = TuningSettings.NONE)
        assertEquals(2, mux(out)!!.getInt("concurrency"))
    }

    @Test fun quic_handling_allow_maps_to_allow() {
        val out = ConfigBuilder.buildRuntimeConfig(
            vlessTcp(), tuning = TuningSettings(mux = MuxSettings(true, 8, 16, QuicHandling.ALLOW))
        )
        assertEquals("allow", mux(out)!!.getString("xudpProxyUDP443"))
    }

    @Test fun mux_and_fragmentation_both_overlay_proxy_without_clobbering() {
        val out = ConfigBuilder.buildRuntimeConfig(
            vlessTcp(),
            tuning = TuningSettings(
                fragmentation = FragmentationSettings(
                    enabled = true, packets = "tlshello", length = "100-200", interval = "10-20"
                ),
                mux = MuxSettings(true, 8, 16, QuicHandling.BLOCK),
            )
        )
        val outbound = JSONObject(out).getJSONArray("outbounds").getJSONObject(0)
        // Mux overlay landed on the proxy outbound.
        assertTrue(outbound.getJSONObject("mux").getBoolean("enabled"))
        // Fragmentation overlay landed in the same outbound's sockopt.
        val sockopt = outbound.getJSONObject("streamSettings").getJSONObject("sockopt")
        assertEquals("tlshello", sockopt.getJSONObject("fragment").getString("packets"))
        // makeSecureDns's ForceIP in that same sockopt survives both overlays.
        assertEquals("ForceIP", sockopt.getString("domainStrategy"))
    }
}
