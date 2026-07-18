package com.justme.xtls_core_proxy.config

/**
 * Global DoH resolver choice + query strategy, overlaid on [ConfigBuilder.makeSecureDns] output by
 * `applyDns`. FROM_CONFIG is a no-op (today's behavior); an explicit resolver overrides. Custom
 * hostname URLs are pinned via Xray `dns.hosts` (see `applyDns`).
 */
data class DnsSettings(
    val resolver: DnsResolver,
    val customUrl: String,
    val customPinnedIp: String,
    val queryStrategy: DnsQueryStrategy,
) {
    companion object {
        val FROM_CONFIG = DnsSettings(
            resolver = DnsResolver.FROM_CONFIG,
            customUrl = "",
            customPinnedIp = "",
            queryStrategy = DnsQueryStrategy.USE_IP,
        )
    }
}

enum class DnsResolver {
    FROM_CONFIG, CLOUDFLARE, GOOGLE, QUAD9, ADGUARD, CUSTOM;

    /** Primary + secondary IP-literal DoH endpoints, or null for FROM_CONFIG / CUSTOM. */
    fun presetPair(): Pair<String, String>? = when (this) {
        CLOUDFLARE -> ConfigBuilder.CLOUDFLARE_DOH to ConfigBuilder.CLOUDFLARE_DOH_SECONDARY
        GOOGLE -> "https://8.8.8.8/dns-query" to "https://8.8.4.4/dns-query"
        QUAD9 -> "https://9.9.9.9/dns-query" to "https://149.112.112.112/dns-query"
        ADGUARD -> "https://94.140.14.14/dns-query" to "https://94.140.15.15/dns-query"
        FROM_CONFIG, CUSTOM -> null
    }
}

enum class DnsQueryStrategy(val wire: String) {
    USE_IP("UseIP"), USE_IPV4("UseIPv4"), USE_IPV6("UseIPv6")
}
