package com.justme.xtls_core_proxy.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WB-NEW-1: the DNS screen blocks saving an IPv6-literal custom resolver/pin while IPv6 is off (the
 * in-tunnel `::/0 → block` rule would otherwise swallow its dial and kill all DNS). This pins the
 * v6-vs-v4-vs-hostname classifier the gate depends on.
 */
class DnsSettingsGateTest {
    @Test fun v6_literals_detected_v4_and_hostnames_not() {
        assertTrue(isIpv6Literal("2606:4700:4700::1111"))
        assertTrue(isIpv6Literal("::1"))
        assertTrue(isIpv6Literal("  2606:4700:4700::1111  "))
        assertFalse(isIpv6Literal("1.2.3.4"))
        assertFalse(isIpv6Literal("doh.example.com"))
        assertFalse(isIpv6Literal(""))
    }
}
