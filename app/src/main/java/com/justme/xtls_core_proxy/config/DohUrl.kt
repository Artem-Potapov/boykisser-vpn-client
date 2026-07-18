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
}
