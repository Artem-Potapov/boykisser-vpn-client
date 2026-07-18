package com.justme.xtls_core_proxy.config

import android.content.Context

/** Persists global routing settings in `xray_prefs`; load() sanitizes against bundled geo files. */
object RoutingPreferences {
    private const val PREFS = "xray_prefs"
    private const val KEY_MODE = "route_mode"
    private const val KEY_COUNTRY = "route_country"
    private const val KEY_LAN = "route_bypass_lan"
    private const val KEY_ADS = "route_block_ads"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun availableGeoFiles(context: Context): Set<String> =
        runCatching { context.assets.list("")?.toSet() }.getOrNull().orEmpty()

    fun load(context: Context): RoutingSettings {
        val p = prefs(context)
        val d = RoutingSettings.USER_DEFAULT
        val raw = RoutingSettings(
            mode = p.getString(KEY_MODE, null)?.let { runCatching { RoutingMode.valueOf(it) }.getOrNull() } ?: d.mode,
            country = p.getString(KEY_COUNTRY, null)?.let { runCatching { RoutingCountry.valueOf(it) }.getOrNull() } ?: d.country,
            bypassLan = p.getBoolean(KEY_LAN, d.bypassLan),
            blockAds = p.getBoolean(KEY_ADS, d.blockAds),
        )
        return sanitizeForAvailability(raw, availableGeoFiles(context))
    }

    fun save(context: Context, settings: RoutingSettings) {
        prefs(context).edit().apply {
            putString(KEY_MODE, settings.mode.name)
            putString(KEY_COUNTRY, settings.country.name)
            putBoolean(KEY_LAN, settings.bypassLan)
            putBoolean(KEY_ADS, settings.blockAds)
            apply()
        }
    }
}
