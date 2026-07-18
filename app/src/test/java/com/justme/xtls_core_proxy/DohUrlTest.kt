package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.DohUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DohUrlTest {
    @Test fun extracts_host() {
        assertEquals("doh.example.com", DohUrl.host("https://doh.example.com/dns-query"))
        assertEquals("1.2.3.4", DohUrl.host("https://1.2.3.4:443/dns-query"))
        assertNull(DohUrl.host("http://x/y"))
    }

    @Test fun accepts_https_rejects_plaintext() {
        assertTrue(DohUrl.isValidHttps("https://8.8.8.8/dns-query"))
        assertFalse(DohUrl.isValidHttps("http://8.8.8.8/dns-query"))
        assertFalse(DohUrl.isValidHttps("8.8.8.8"))
    }

    @Test fun resolve_uses_injected_resolver() {
        assertEquals("9.9.9.9", DohUrl.resolveHostname("doh.example.com") { "9.9.9.9" })
        assertNull(DohUrl.resolveHostname("doh.example.com") { throw java.net.UnknownHostException() })
    }
}
