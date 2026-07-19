package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.state.PingPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PingTargetValidationTest {
    @Test
    fun accepts_http_rejects_https_and_junk() {
        assertTrue(PingPreferences.isValidTarget("http://cp.cloudflare.com/generate_204"))
        assertFalse(PingPreferences.isValidTarget("https://cp.cloudflare.com/generate_204"))
        assertFalse(PingPreferences.isValidTarget("cp.cloudflare.com"))
    }

    @Test
    fun rejects_scheme_only_http_accepts_nonempty_after_scheme() {
        assertFalse(PingPreferences.isValidTarget("http://"))
        assertFalse(PingPreferences.isValidTarget("http://   "))
        assertTrue(PingPreferences.isValidTarget("  HTTP://example.com  "))
    }
}
