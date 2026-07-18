package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.DohUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DohUrlTest {
    @Test fun extracts_host() {
        assertEquals("doh.example.com", DohUrl.host("https://doh.example.com/dns-query"))
        assertEquals("1.2.3.4", DohUrl.host("https://1.2.3.4:443/dns-query"))
        assertNull(DohUrl.host("http://x/y"))
    }
}
