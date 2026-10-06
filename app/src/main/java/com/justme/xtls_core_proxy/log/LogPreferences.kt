package com.justme.xtls_core_proxy.log

import android.content.Context
import com.justme.xtls_core_proxy.config.XrayLogLevel

/** Persists log settings in the shared `xray_prefs` (same store as PromoGate). */
object LogPreferences {
    private const val PREFS = "xray_prefs"
    private const val KEY_LEVEL = "xray_log_level"
    private const val KEY_BUFFER = "xray_log_buffer_lines"
    // TEMPORARY_DEBUG_ONLY_1M_LOG_BUFFER — revert DEFAULT_BUFFER to 5000 and drop 1_000_000 from presets.
    const val DEFAULT_BUFFER = 1_000_000
    val BUFFER_PRESETS = listOf(1000, 2000, 5000, 10000, 1_000_000)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getLogLevel(context: Context): XrayLogLevel =
        XrayLogLevel.fromName(prefs(context).getString(KEY_LEVEL, null))

    fun setLogLevel(context: Context, level: XrayLogLevel) {
        prefs(context).edit().putString(KEY_LEVEL, level.name).apply()
    }

    fun getBufferLines(context: Context): Int =
        prefs(context).getInt(KEY_BUFFER, DEFAULT_BUFFER)

    fun setBufferLines(context: Context, lines: Int) {
        prefs(context).edit().putInt(KEY_BUFFER, lines).apply()
    }
}
