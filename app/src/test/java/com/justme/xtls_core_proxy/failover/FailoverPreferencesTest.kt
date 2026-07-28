package com.justme.xtls_core_proxy.failover

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

private const val PREFS_NAME = "xray_prefs"
private const val KEY_ENABLED = "failover_enabled"
private const val KEY_INTERVAL = "failover_probe_interval_ms"
private const val KEY_TIMEOUT = "failover_probe_timeout_ms"
private const val KEY_THRESHOLD = "failover_failure_threshold"
private const val KEY_MAX_ROTATIONS = "failover_max_rotations"
private const val KEY_WINDOW = "failover_rotation_window_ms"

class FailoverPreferencesTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var context: Context

    @Before
    fun setUp() {
        editor = mock {
            on { putBoolean(any(), any()) } doReturn it
            on { putLong(any(), any()) } doReturn it
            on { putInt(any(), any()) } doReturn it
        }
        prefs = mock {
            on { edit() } doReturn editor
        }
        context = mock {
            on { getSharedPreferences(eq(PREFS_NAME), eq(Context.MODE_PRIVATE)) } doReturn prefs
        }
    }

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

    @Test
    fun load_returnsDefaults_whenPrefsEmpty() {
        whenever(prefs.getBoolean(eq(KEY_ENABLED), eq(false))).thenReturn(false)
        whenever(prefs.getLong(eq(KEY_INTERVAL), eq(15_000L))).thenReturn(15_000L)
        whenever(prefs.getLong(eq(KEY_TIMEOUT), eq(5_000L))).thenReturn(5_000L)
        whenever(prefs.getInt(eq(KEY_THRESHOLD), eq(2))).thenReturn(2)
        whenever(prefs.getInt(eq(KEY_MAX_ROTATIONS), eq(3))).thenReturn(3)
        whenever(prefs.getLong(eq(KEY_WINDOW), eq(600_000L))).thenReturn(600_000L)

        val result = FailoverPreferences.load(context)

        assertEquals(FailoverPreferences.DEFAULT, result)
    }

    @Test
    fun load_returnsStoredValues() {
        whenever(prefs.getBoolean(eq(KEY_ENABLED), eq(false))).thenReturn(true)
        whenever(prefs.getLong(eq(KEY_INTERVAL), eq(15_000L))).thenReturn(30_000L)
        whenever(prefs.getLong(eq(KEY_TIMEOUT), eq(5_000L))).thenReturn(4_000L)
        whenever(prefs.getInt(eq(KEY_THRESHOLD), eq(2))).thenReturn(4)
        whenever(prefs.getInt(eq(KEY_MAX_ROTATIONS), eq(3))).thenReturn(5)
        whenever(prefs.getLong(eq(KEY_WINDOW), eq(600_000L))).thenReturn(120_000L)

        val result = FailoverPreferences.load(context)

        assertEquals(
            FailoverSettings(
                enabled = true,
                probeIntervalMs = 30_000L,
                probeTimeoutMs = 4_000L,
                failureThreshold = 4,
                maxRotations = 5,
                rotationWindowMs = 120_000L,
            ),
            result,
        )
    }

    @Test
    fun load_appliesCoerce_toOutOfBoundsStoredPair() {
        whenever(prefs.getBoolean(eq(KEY_ENABLED), eq(false))).thenReturn(false)
        whenever(prefs.getLong(eq(KEY_INTERVAL), eq(15_000L))).thenReturn(15_000L)
        whenever(prefs.getLong(eq(KEY_TIMEOUT), eq(5_000L))).thenReturn(20_000L)
        whenever(prefs.getInt(eq(KEY_THRESHOLD), eq(2))).thenReturn(2)
        whenever(prefs.getInt(eq(KEY_MAX_ROTATIONS), eq(3))).thenReturn(3)
        whenever(prefs.getLong(eq(KEY_WINDOW), eq(600_000L))).thenReturn(600_000L)

        val result = FailoverPreferences.load(context)

        assertTrue(
            "loaded timeout must stay below loaded interval",
            result.probeTimeoutMs < result.probeIntervalMs,
        )
        assertEquals(15_000L, result.probeIntervalMs)
        assertEquals(15_000L - FailoverPreferences.TIMEOUT_HEADROOM_MS, result.probeTimeoutMs)
    }

    @Test
    fun save_writesCoercedValues_forEveryKey() {
        val wild = FailoverSettings(
            enabled = true,
            probeIntervalMs = 15_000L,
            probeTimeoutMs = 20_000L,
            failureThreshold = 2,
            maxRotations = 3,
            rotationWindowMs = 600_000L,
        )

        FailoverPreferences.save(context, wild)

        verify(editor).putBoolean(eq(KEY_ENABLED), eq(true))
        verify(editor).putLong(eq(KEY_INTERVAL), eq(15_000L))
        verify(editor).putLong(eq(KEY_TIMEOUT), eq(15_000L - FailoverPreferences.TIMEOUT_HEADROOM_MS))
        verify(editor).putInt(eq(KEY_THRESHOLD), eq(2))
        verify(editor).putInt(eq(KEY_MAX_ROTATIONS), eq(3))
        verify(editor).putLong(eq(KEY_WINDOW), eq(600_000L))
        verify(editor).apply()
    }
}
