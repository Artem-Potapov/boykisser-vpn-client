package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.ConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderPingTest {

    @Test
    fun toPingTestConfig_fromVless_hasOutboundsAndNoInbounds() {
        val cfg = ConfigBuilder.toPingTestConfig(
            ConfigBuilder.toProfileStorageConfig(
                "vless://11111111-1111-1111-1111-111111111111@demo.example:443?security=none"
            )
        )
        val root = JSONObject(cfg)
        assertFalse("probe config must have no inbounds", root.has("inbounds"))
        assertTrue("probe config must keep outbounds", root.getJSONArray("outbounds").length() > 0)
        assertTrue("secure DNS must be preserved", root.has("dns"))
    }

    @Test
    fun toPingTestConfig_fromJson_stripsInbounds() {
        val cfg = ConfigBuilder.toPingTestConfig(
            ConfigBuilder.toProfileStorageConfig(
                """{"outbounds":[{"protocol":"freedom","tag":"direct"}]}"""
            )
        )
        val root = JSONObject(cfg)
        assertFalse(root.has("inbounds"))
        assertTrue(root.getJSONArray("outbounds").length() > 0)
    }
}
