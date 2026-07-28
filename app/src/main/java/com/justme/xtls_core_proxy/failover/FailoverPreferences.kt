package com.justme.xtls_core_proxy.failover

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Global auto-failover settings. Probe target is deliberately shared with PingPreferences. */
data class FailoverSettings(
    val enabled: Boolean,
    val probeIntervalMs: Long,
    val probeTimeoutMs: Long,
    val failureThreshold: Int,
    val maxRotations: Int,
    val rotationWindowMs: Long,
)

/**
 * Persists failover settings in the shared `xray_prefs` store and exposes a process-wide
 * StateFlow so XrayVpnService reacts to live edits, mirroring KillSwitchRepository.state.
 */
object FailoverPreferences {
    private const val PREFS = "xray_prefs"
    private const val KEY_ENABLED = "failover_enabled"
    private const val KEY_INTERVAL = "failover_probe_interval_ms"
    private const val KEY_TIMEOUT = "failover_probe_timeout_ms"
    private const val KEY_THRESHOLD = "failover_failure_threshold"
    private const val KEY_MAX_ROTATIONS = "failover_max_rotations"
    private const val KEY_WINDOW = "failover_rotation_window_ms"

    const val INTERVAL_MIN = 5_000L
    const val INTERVAL_MAX = 300_000L
    const val TIMEOUT_MIN = 1_000L
    const val THRESHOLD_MIN = 1
    const val THRESHOLD_MAX = 10
    const val ROTATIONS_MIN = 1
    const val ROTATIONS_MAX = 10
    const val WINDOW_MIN = 60_000L
    const val WINDOW_MAX = 3_600_000L

    /** Minimum gap kept between probe timeout and probe interval. */
    const val TIMEOUT_HEADROOM_MS = 1_000L

    val DEFAULT = FailoverSettings(
        enabled = false,
        probeIntervalMs = 15_000L,
        probeTimeoutMs = 5_000L,
        failureThreshold = 2,
        maxRotations = 3,
        rotationWindowMs = 600_000L,
    )

    private val _state = MutableStateFlow(DEFAULT)
    val state: StateFlow<FailoverSettings> = _state.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Clamps every field into bounds, then enforces probeTimeout < probeInterval. The pair rule
     * runs LAST so it sees already-clamped values and cannot be undone by a later clamp.
     */
    fun coerce(settings: FailoverSettings): FailoverSettings {
        val interval = settings.probeIntervalMs.coerceIn(INTERVAL_MIN, INTERVAL_MAX)
        val timeoutCeiling = (interval - TIMEOUT_HEADROOM_MS).coerceAtLeast(TIMEOUT_MIN)
        return settings.copy(
            probeIntervalMs = interval,
            probeTimeoutMs = settings.probeTimeoutMs.coerceIn(TIMEOUT_MIN, timeoutCeiling),
            failureThreshold = settings.failureThreshold.coerceIn(THRESHOLD_MIN, THRESHOLD_MAX),
            maxRotations = settings.maxRotations.coerceIn(ROTATIONS_MIN, ROTATIONS_MAX),
            rotationWindowMs = settings.rotationWindowMs.coerceIn(WINDOW_MIN, WINDOW_MAX),
        )
    }

    fun load(context: Context): FailoverSettings {
        val p = prefs(context)
        val loaded = coerce(
            FailoverSettings(
                enabled = p.getBoolean(KEY_ENABLED, DEFAULT.enabled),
                probeIntervalMs = p.getLong(KEY_INTERVAL, DEFAULT.probeIntervalMs),
                probeTimeoutMs = p.getLong(KEY_TIMEOUT, DEFAULT.probeTimeoutMs),
                failureThreshold = p.getInt(KEY_THRESHOLD, DEFAULT.failureThreshold),
                maxRotations = p.getInt(KEY_MAX_ROTATIONS, DEFAULT.maxRotations),
                rotationWindowMs = p.getLong(KEY_WINDOW, DEFAULT.rotationWindowMs),
            )
        )
        _state.value = loaded
        return loaded
    }

    fun save(context: Context, settings: FailoverSettings) {
        val safe = coerce(settings)
        prefs(context).edit().apply {
            putBoolean(KEY_ENABLED, safe.enabled)
            putLong(KEY_INTERVAL, safe.probeIntervalMs)
            putLong(KEY_TIMEOUT, safe.probeTimeoutMs)
            putInt(KEY_THRESHOLD, safe.failureThreshold)
            putInt(KEY_MAX_ROTATIONS, safe.maxRotations)
            putLong(KEY_WINDOW, safe.rotationWindowMs)
            apply()
        }
        _state.value = safe
    }
}
