package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.config.ProfileShareLink
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileShareLinkTest {

    @Test
    fun fromStoredConfig_vlessJson_returnsVlessUri() {
        val json = ConfigBuilder.fromVlessUri(
            "vless://11111111-1111-1111-1111-111111111111@demo.example:443?security=none"
        )
        val link = ProfileShareLink.fromStoredConfig(json)
        assertNotNull(link)
        assertTrue(link!!.startsWith("vless://"))
    }

    @Test
    fun fromStoredConfig_hysteriaJson_returnsHy2Uri() {
        val json = ConfigBuilder.fromHysteria2Uri("hy2://secret@example.com:443/?sni=cdn.example.com")
        val link = ProfileShareLink.fromStoredConfig(json)
        assertNotNull(link)
        assertTrue(link!!.startsWith("hy2://"))
    }

    @Test
    fun fromStoredConfig_freedomOnly_returnsNull() {
        assertNull(
            ProfileShareLink.fromStoredConfig("""{"outbounds":[{"protocol":"freedom","tag":"direct"}]}""")
        )
    }

    @Test
    fun fromStoredConfig_malformed_returnsNull() {
        assertNull(ProfileShareLink.fromStoredConfig("not json"))
    }
}
