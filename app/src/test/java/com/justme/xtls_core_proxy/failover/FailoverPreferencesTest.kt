package com.justme.xtls_core_proxy.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FailoverPreferencesTest {

    @Test
    fun defaults_matchSpec() {
        val d = FailoverPreferences.DEFAULT
        assertFalse("failover must be opt-in", d.enabled)
        assertEquals(15_000L, d.probeIntervalMs)
        assertEquals(5_000L, d.probeTimeoutMs)
        assertEquals(2, d.failureThreshold)
        assertEquals(3, d.maxRotations)
        assertEquals(600_000L, d.rotationWindowMs)
    }

    @Test
    fun coerce_clampsEachFieldIntoBounds() {
        val wild = FailoverPreferences.DEFAULT.copy(
            probeIntervalMs = 1L,
            probeTimeoutMs = 999_999L,
            failureThreshold = 0,
            maxRotations = 99,
        )
        val c = FailoverPreferences.coerce(wild)
        assertEquals(FailoverPreferences.INTERVAL_MIN, c.probeIntervalMs)
        assertEquals(FailoverPreferences.THRESHOLD_MIN, c.failureThreshold)
        assertEquals(FailoverPreferences.ROTATIONS_MAX, c.maxRotations)
    }

    @Test
    fun coerce_forcesTimeoutStrictlyBelowInterval() {
        // The load-bearing invariant: a probe that outlives its tick would let the failure
        // counter advance on stale, overlapping work and rotate a healthy tunnel.
        val bad = FailoverPreferences.DEFAULT.copy(probeIntervalMs = 15_000L, probeTimeoutMs = 20_000L)
        val c = FailoverPreferences.coerce(bad)
        assertEquals(15_000L, c.probeIntervalMs)
        assertEquals(15_000L - FailoverPreferences.TIMEOUT_HEADROOM_MS, c.probeTimeoutMs)
    }

    @Test
    fun coerce_leavesValidPairUntouched() {
        val ok = FailoverPreferences.DEFAULT.copy(probeIntervalMs = 20_000L, probeTimeoutMs = 5_000L)
        assertEquals(ok, FailoverPreferences.coerce(ok))
    }
}
