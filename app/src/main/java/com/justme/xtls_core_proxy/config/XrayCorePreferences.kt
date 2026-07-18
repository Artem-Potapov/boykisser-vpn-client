package com.justme.xtls_core_proxy.config

import android.content.Context

/** Persists global XRAY-core settings in `xray_prefs`. */
object XrayCorePreferences {
    private const val PREFS = "xray_prefs"
    private const val KEY_MTU = "core_mtu"
    private const val KEY_IPV6 = "core_ipv6"
    private const val KEY_SNIFFING = "core_sniffing"
    private const val KEY_STRATEGY = "core_domain_strategy"
    const val MTU_MIN = 1280
    const val MTU_MAX = 1500

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): XrayCoreSettings {
        val p = prefs(context)
        val d = XrayCoreSettings.DEFAULT
        val strategy = p.getString(KEY_STRATEGY, null)?.let { runCatching { XrayDomainStrategy.valueOf(it) }.getOrNull() } ?: d.domainStrategy
        return XrayCoreSettings(
            mtu = p.getInt(KEY_MTU, d.mtu).coerceIn(MTU_MIN, MTU_MAX),
            ipv6 = p.getBoolean(KEY_IPV6, d.ipv6),
            sniffing = p.getBoolean(KEY_SNIFFING, d.sniffing),
            domainStrategy = strategy,
        )
    }

    fun save(context: Context, settings: XrayCoreSettings) {
        prefs(context).edit().apply {
            putInt(KEY_MTU, settings.mtu.coerceIn(MTU_MIN, MTU_MAX))
            putBoolean(KEY_IPV6, settings.ipv6)
            putBoolean(KEY_SNIFFING, settings.sniffing)
            putString(KEY_STRATEGY, settings.domainStrategy.name)
            apply()
        }
    }
}
