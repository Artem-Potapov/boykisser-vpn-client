package com.justme.xtls_core_proxy.config

import android.content.Context

/** Persists global fragmentation settings in the shared `xray_prefs` (same store as LogPreferences). */
object FragmentationPreferences {
    private const val PREFS = "xray_prefs"
    private const val KEY_ENABLED = "frag_enabled"
    private const val KEY_PACKETS = "frag_packets"
    private const val KEY_LENGTH = "frag_length"
    private const val KEY_INTERVAL = "frag_interval"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): FragmentationSettings {
        val p = prefs(context)
        val d = FragmentationSettings.DISABLED
        return FragmentationSettings(
            enabled = p.getBoolean(KEY_ENABLED, false),
            packets = p.getString(KEY_PACKETS, null) ?: d.packets,
            length = p.getString(KEY_LENGTH, null) ?: d.length,
            interval = p.getString(KEY_INTERVAL, null) ?: d.interval,
        )
    }

    fun save(context: Context, settings: FragmentationSettings) {
        prefs(context).edit().apply {
            putBoolean(KEY_ENABLED, settings.enabled)
            putString(KEY_PACKETS, settings.packets)
            putString(KEY_LENGTH, settings.length)
            putString(KEY_INTERVAL, settings.interval)
            apply()
        }
    }
}
