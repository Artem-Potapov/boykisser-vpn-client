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

    /**
     * Pins the deliberately permissive boundary of the scheme-only gate (Plan-3 R7, Option A):
     * these inputs a stricter validator would reject, but [PingPreferences.isValidTarget] accepts
     * them BY DESIGN because it only checks the `http://` scheme and a non-empty remainder — never
     * host, port range, embedded credentials, or path. If this test goes red, the gate's contract
     * changed and the KDoc on isValidTarget needs a matching update (or the tightening needs a new
     * maintainer decision).
     */
    @Test
    fun accepts_malformed_targets_scheme_gate_is_deliberately_minimal() {
        assertTrue(PingPreferences.isValidTarget("http://user:pass@example.com/"))
        assertTrue(PingPreferences.isValidTarget("http://example.com:99999/"))
        assertTrue(PingPreferences.isValidTarget("http://exa mple.com"))
        assertTrue(PingPreferences.isValidTarget("http://???"))
    }
}
