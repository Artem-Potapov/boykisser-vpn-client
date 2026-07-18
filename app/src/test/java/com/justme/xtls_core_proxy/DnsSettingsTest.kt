package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.DnsResolver
import com.justme.xtls_core_proxy.config.DnsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsSettingsTest {
    @Test fun from_config_is_default() {
        assertEquals(DnsResolver.FROM_CONFIG, DnsSettings.FROM_CONFIG.resolver)
    }

    @Test fun preset_pairs_are_ip_literal() {
        assertEquals("https://8.8.8.8/dns-query" to "https://8.8.4.4/dns-query", DnsResolver.GOOGLE.presetPair())
        assertEquals("https://9.9.9.9/dns-query" to "https://149.112.112.112/dns-query", DnsResolver.QUAD9.presetPair())
        assertEquals("https://94.140.14.14/dns-query" to "https://94.140.15.15/dns-query", DnsResolver.ADGUARD.presetPair())
    }

    @Test fun from_config_and_custom_have_no_preset() {
        assertNull(DnsResolver.FROM_CONFIG.presetPair())
        assertNull(DnsResolver.CUSTOM.presetPair())
    }
}
