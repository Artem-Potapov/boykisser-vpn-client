package com.justme.xtls_core_proxy.failover

/**
 * Pure per-control autosave decision behind FailoverSettingsActivity.persist(). Each raw text
 * field independently resolves to its parsed-and-in-range value, or falls back to the tuple's
 * own last-good *persisted* value — an invalid field never vetoes the rest of the write (that
 * would drop, e.g., an enable-toggle flip made in the same recomposition).
 *
 * The timeout is validated against the *effective* interval `i` (the just-resolved value, which
 * may itself be a fallback), not the raw interval input and not lastGood.probeIntervalMs — so
 * editing either field re-validates the pair. The accept ceiling is derived exactly the way
 * [FailoverPreferences.coerce] derives it (`i - TIMEOUT_HEADROOM_MS`, floored at `TIMEOUT_MIN`) —
 * NOT `i - 1` — so a value this function accepts can never be silently rewritten downward by
 * `coerce()` inside [FailoverPreferences.save] with no error ever shown on screen. `coerce()`
 * remains the final backstop for the invariant; this mirrors it rather than replacing it.
 */
internal fun resolveFailoverSettings(
    enabled: Boolean,
    intervalInput: String,
    timeoutInput: String,
    thresholdInput: String,
    maxRotationsInput: String,
    lastGood: FailoverSettings,
): FailoverSettings {
    val i = intervalInput.trim().toLongOrNull()
        ?.takeIf { it in FailoverPreferences.INTERVAL_MIN..FailoverPreferences.INTERVAL_MAX }
        ?: lastGood.probeIntervalMs
    val timeoutCeiling = (i - FailoverPreferences.TIMEOUT_HEADROOM_MS)
        .coerceAtLeast(FailoverPreferences.TIMEOUT_MIN)
    val t = timeoutInput.trim().toLongOrNull()
        ?.takeIf { it in FailoverPreferences.TIMEOUT_MIN..timeoutCeiling }
        ?: lastGood.probeTimeoutMs
    val th = thresholdInput.trim().toIntOrNull()
        ?.takeIf { it in FailoverPreferences.THRESHOLD_MIN..FailoverPreferences.THRESHOLD_MAX }
        ?: lastGood.failureThreshold
    val mr = maxRotationsInput.trim().toIntOrNull()
        ?.takeIf { it in FailoverPreferences.ROTATIONS_MIN..FailoverPreferences.ROTATIONS_MAX }
        ?: lastGood.maxRotations
    return FailoverSettings(enabled, i, t, th, mr, lastGood.rotationWindowMs)
}
