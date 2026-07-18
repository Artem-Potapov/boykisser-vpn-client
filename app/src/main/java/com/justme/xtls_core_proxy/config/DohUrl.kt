package com.justme.xtls_core_proxy.config

/** Pure helpers for custom DoH URLs (shared by ConfigBuilder.applyDns and the DNS screen). */
object DohUrl {
    /** Host of a `https://host[:port]/path` URL, or null if not https / unparseable. */
    fun host(url: String): String? {
        val u = url.trim()
        if (!u.startsWith("https://", ignoreCase = true)) return null
        val host = u.substringAfter("://").substringBefore("/").substringBefore(":").trim()
        return host.ifBlank { null }
    }

    fun isValidHttps(url: String): Boolean = host(url) != null

    /**
     * Resolves [host] to an IP via [resolver] (default: system DNS). Returns null on failure — a
     * convenience for the settings UI, never a gate. Must run off the main thread.
     */
    fun resolveHostname(host: String, resolver: (String) -> String = { java.net.InetAddress.getByName(it).hostAddress ?: "" }): String? =
        runCatching { resolver(host).ifBlank { null } }.getOrNull()
}
