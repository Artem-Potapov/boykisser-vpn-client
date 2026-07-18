package com.justme.xtls_core_proxy.config

import android.content.Context

/** Persists global DNS settings in the shared `xray_prefs`. */
object DnsPreferences {
    private const val PREFS = "xray_prefs"
    private const val KEY_RESOLVER = "dns_resolver"
    private const val KEY_CUSTOM_URL = "dns_custom_url"
    private const val KEY_CUSTOM_IP = "dns_custom_pinned_ip"
    private const val KEY_STRATEGY = "dns_query_strategy"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): DnsSettings {
        val p = prefs(context)
        val d = DnsSettings.FROM_CONFIG
        val resolver = p.getString(KEY_RESOLVER, null)?.let { runCatching { DnsResolver.valueOf(it) }.getOrNull() } ?: d.resolver
        val strategy = p.getString(KEY_STRATEGY, null)?.let { runCatching { DnsQueryStrategy.valueOf(it) }.getOrNull() } ?: d.queryStrategy
        return DnsSettings(
            resolver = resolver,
            customUrl = p.getString(KEY_CUSTOM_URL, d.customUrl) ?: d.customUrl,
            customPinnedIp = p.getString(KEY_CUSTOM_IP, d.customPinnedIp) ?: d.customPinnedIp,
            queryStrategy = strategy,
        )
    }

    fun save(context: Context, settings: DnsSettings) {
        prefs(context).edit().apply {
            putString(KEY_RESOLVER, settings.resolver.name)
            putString(KEY_CUSTOM_URL, settings.customUrl)
            putString(KEY_CUSTOM_IP, settings.customPinnedIp)
            putString(KEY_STRATEGY, settings.queryStrategy.name)
            apply()
        }
    }
}
