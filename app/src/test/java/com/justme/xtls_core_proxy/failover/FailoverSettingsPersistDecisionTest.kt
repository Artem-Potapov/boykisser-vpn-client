package com.justme.xtls_core_proxy.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage for [resolveFailoverSettings] — the per-control autosave decision
 * behind FailoverSettingsActivity.persist(). Mirrors the instrumented
 * FailoverSettingsPersistTest scenario (invalid interval must never veto the enable toggle) at
 * the JVM layer, plus the cross-field rule that the timeout is validated against the *effective*
 * (possibly-fallback) interval, not the raw interval input or the last-good interval.
 */
class FailoverSettingsPersistDecisionTest {

    private val lastGood = FailoverPreferences.DEFAULT.copy(
        enabled = false,
        probeIntervalMs = 20_000L,
        probeTimeoutMs = 5_000L,
        failureThreshold = 2,
        maxRotations = 3,
        rotationWindowMs = 300_000L,
    )

    @Test
    fun allFieldsValid_parsesEachField_andPassesThroughRotationWindowFromLastGood() {
        val result = resolveFailoverSettings(
            enabled = true,
            intervalInput = "10000",
            timeoutInput = "4000",
            thresholdInput = "5",
            maxRotationsInput = "7",
            lastGood = lastGood,
        )
        assertEquals(
            FailoverSettings(
                enabled = true,
                probeIntervalMs = 10_000L,
                probeTimeoutMs = 4_000L,
                failureThreshold = 5,
                maxRotations = 7,
                rotationWindowMs = lastGood.rotationWindowMs,
            ),
            result,
        )
    }

    @Test
    fun invalidInterval_fallsBackToLastGoodInterval_andDoesNotVetoEnabled() {
        val result = resolveFailoverSettings(
            enabled = true,
            intervalInput = "3", // below INTERVAL_MIN
            timeoutInput = "4000",
            thresholdInput = "5",
            maxRotationsInput = "7",
            lastGood = lastGood,
        )
        assertTrue("an invalid interval must not veto the enable toggle", result.enabled)
        assertEquals(lastGood.probeIntervalMs, result.probeIntervalMs)
        assertEquals(4_000L, result.probeTimeoutMs)
        assertEquals(5, result.failureThreshold)
        assertEquals(7, result.maxRotations)
    }

    @Test
    fun invalidTimeout_fallsBackToLastGoodTimeout() {
        val result = resolveFailoverSettings(
            enabled = true,
            intervalInput = "10000",
            timeoutInput = "10000", // not strictly below the effective interval
            thresholdInput = "5",
            maxRotationsInput = "7",
            lastGood = lastGood,
        )
        assertEquals(10_000L, result.probeIntervalMs)
        assertEquals(lastGood.probeTimeoutMs, result.probeTimeoutMs)
    }

    @Test
    fun timeoutValidity_usesEffectiveInterval_notLastGoodInterval() {
        // lastGood.interval is 20_000, so 15_000 would validate against IT, but the new
        // effective interval is 6_000 — 15_000 must be rejected against the effective value.
        val result = resolveFailoverSettings(
            enabled = true,
            intervalInput = "6000",
            timeoutInput = "15000",
            thresholdInput = "5",
            maxRotationsInput = "7",
            lastGood = lastGood,
        )
        assertEquals(6_000L, result.probeIntervalMs)
        assertEquals(
            "timeout must be re-validated against the effective interval, not lastGood's",
            lastGood.probeTimeoutMs,
            result.probeTimeoutMs,
        )
    }

    @Test
    fun timeoutValidity_reEvaluatesWhenFallbackIntervalIsUsed() {
        // interval input is invalid -> effective interval falls back to lastGood (20_000), whose
        // real ceiling is 20_000 - TIMEOUT_HEADROOM_MS (1_000) = 19_000. A timeout of 18_500 sits
        // inside that ceiling and must be accepted against the fallback, even though it would not
        // be valid at all against the (rejected) raw interval input.
        val result = resolveFailoverSettings(
            enabled = true,
            intervalInput = "not-a-number",
            timeoutInput = "18500",
            thresholdInput = "5",
            maxRotationsInput = "7",
            lastGood = lastGood,
        )
        assertEquals(lastGood.probeIntervalMs, result.probeIntervalMs)
        assertEquals(18_500L, result.probeTimeoutMs)
    }

    @Test
    fun timeoutJustInsideHeadroom_isAccepted() {
        // coerce()'s real ceiling is interval - TIMEOUT_HEADROOM_MS = 10_000 - 1_000 = 9_000,
        // not interval - 1 (see timeoutInsideCoerceGap_isRejected_fallsBackToLastGood below).
        val result = resolveFailoverSettings(
            enabled = true,
            intervalInput = "10000",
            timeoutInput = "9000",
            thresholdInput = "5",
            maxRotationsInput = "7",
            lastGood = lastGood,
        )
        assertEquals(10_000L, result.probeIntervalMs)
        assertEquals(9_000L, result.probeTimeoutMs)
    }

    @Test
    fun timeoutInsideCoerceGap_isRejected_fallsBackToLastGood() {
        // 9_500 is below the raw interval (10_000) but ABOVE coerce()'s real ceiling of
        // interval - TIMEOUT_HEADROOM_MS (9_000). Accepting it here would let FailoverPreferences
        // .save() -> coerce() silently rewrite the user's typed 9_500 down to 9_000 with no error
        // ever shown on screen — this pins that ~999ms-wide silent-rewrite gap shut.
        val result = resolveFailoverSettings(
            enabled = true,
            intervalInput = "10000",
            timeoutInput = "9500",
            thresholdInput = "5",
            maxRotationsInput = "7",
            lastGood = lastGood,
        )
        assertEquals(10_000L, result.probeIntervalMs)
        assertEquals(
            "9_500 sits inside the coerce() silent-rewrite gap and must be rejected here, " +
                "not silently coerced later",
            lastGood.probeTimeoutMs,
            result.probeTimeoutMs,
        )
    }

    @Test
    fun invalidThreshold_fallsBackToLastGoodThreshold() {
        val result = resolveFailoverSettings(
            enabled = true,
            intervalInput = "10000",
            timeoutInput = "4000",
            thresholdInput = "0", // below THRESHOLD_MIN
            maxRotationsInput = "7",
            lastGood = lastGood,
        )
        assertEquals(lastGood.failureThreshold, result.failureThreshold)
        assertEquals(10_000L, result.probeIntervalMs)
        assertEquals(4_000L, result.probeTimeoutMs)
        assertEquals(7, result.maxRotations)
    }

    @Test
    fun invalidMaxRotations_fallsBackToLastGoodMaxRotations() {
        val result = resolveFailoverSettings(
            enabled = true,
            intervalInput = "10000",
            timeoutInput = "4000",
            thresholdInput = "5",
            maxRotationsInput = "11", // above ROTATIONS_MAX
            lastGood = lastGood,
        )
        assertEquals(lastGood.maxRotations, result.maxRotations)
    }

    @Test
    fun blankNumericInputs_fallBackToLastGoodForEveryField() {
        val result = resolveFailoverSettings(
            enabled = true,
            intervalInput = "",
            timeoutInput = "  ",
            thresholdInput = "",
            maxRotationsInput = "",
            lastGood = lastGood,
        )
        assertEquals(lastGood.probeIntervalMs, result.probeIntervalMs)
        assertEquals(lastGood.probeTimeoutMs, result.probeTimeoutMs)
        assertEquals(lastGood.failureThreshold, result.failureThreshold)
        assertEquals(lastGood.maxRotations, result.maxRotations)
    }

    @Test
    fun disabling_isPassedThrough_evenWhenEveryNumericFieldIsInvalid() {
        val result = resolveFailoverSettings(
            enabled = false,
            intervalInput = "garbage",
            timeoutInput = "garbage",
            thresholdInput = "garbage",
            maxRotationsInput = "garbage",
            lastGood = lastGood.copy(enabled = true),
        )
        assertFalse("the enable toggle flip must never be dropped", result.enabled)
    }

    @Test
    fun whitespacePaddedValidInput_parsesTrimmed() {
        val result = resolveFailoverSettings(
            enabled = true,
            intervalInput = " 10000 ",
            timeoutInput = " 4000 ",
            thresholdInput = " 5 ",
            maxRotationsInput = " 7 ",
            lastGood = lastGood,
        )
        assertEquals(10_000L, result.probeIntervalMs)
        assertEquals(4_000L, result.probeTimeoutMs)
        assertEquals(5, result.failureThreshold)
        assertEquals(7, result.maxRotations)
    }
}
