package com.justme.xtls_core_proxy.config

import android.content.Context

/** Persists global Mux.Cool settings in the shared `xray_prefs` (same store as LogPreferences). */
object MuxPreferences {
    private const val PREFS = "xray_prefs"
    private const val KEY_ENABLED = "mux_enabled"
    private const val KEY_CONCURRENCY = "mux_concurrency"
    private const val KEY_XUDP = "mux_xudp_concurrency"
    private const val KEY_QUIC = "mux_quic_handling"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): MuxSettings {
        val p = prefs(context)
        val d = MuxSettings.OFF
        val quic = p.getString(KEY_QUIC, null)?.let { name ->
            runCatching { QuicHandling.valueOf(name) }.getOrNull()
        } ?: d.quicHandling
        return MuxSettings(
            enabled = p.getBoolean(KEY_ENABLED, d.enabled),
            concurrency = p.getInt(KEY_CONCURRENCY, d.concurrency).coerceIn(1, 1024),
            xudpConcurrency = p.getInt(KEY_XUDP, d.xudpConcurrency).coerceAtLeast(0),
            quicHandling = quic,
        )
    }

    fun save(context: Context, settings: MuxSettings) {
        prefs(context).edit().apply {
            putBoolean(KEY_ENABLED, settings.enabled)
            putInt(KEY_CONCURRENCY, settings.concurrency)
            putInt(KEY_XUDP, settings.xudpConcurrency)
            putString(KEY_QUIC, settings.quicHandling.name)
            apply()
        }
    }
}
