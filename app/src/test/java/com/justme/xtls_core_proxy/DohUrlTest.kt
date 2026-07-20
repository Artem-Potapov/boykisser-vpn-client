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

    @Test fun extracts_bracketed_ipv6_host() {
        // WB-NEW-2: a bracketed IPv6 authority yields the bare v6 literal (brackets + :port stripped),
        // so isIpLiteral classifies it correctly instead of demanding a spurious hosts-pin.
        assertEquals("2606:4700:4700::1111", DohUrl.host("https://[2606:4700:4700::1111]/dns-query"))
        assertEquals("2606:4700:4700::1111", DohUrl.host("https://[2606:4700:4700::1111]:8443/resolve"))
        assertTrue(DohUrl.isValidHttps("https://[2606:4700:4700::1111]/dns-query"))
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
