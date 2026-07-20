package com.justme.xtls_core_proxy.config

/** Pure helpers for custom DoH URLs (shared by ConfigBuilder.applyDns and the DNS screen). */
object DohUrl {
    /**
     * Host of a `https://host[:port]/path` URL, or null if not https / unparseable. Bracket-aware:
     * a bracketed IPv6 authority `[2606:4700:4700::1111]:443` yields the bare `2606:4700:4700::1111`
     * (so `isIpLiteral` classifies it correctly and no spurious hosts-pin is demanded). Callers that
     * compose the host back into a URL must re-bracket an IPv6 literal (see `customBootstrapUrl`).
     */
    fun host(url: String): String? {
        val u = url.trim()
        if (!u.startsWith("https://", ignoreCase = true)) return null
        val authority = u.substringAfter("://").substringBefore("/").trim()
        val host = if (authority.startsWith("[")) {
            authority.substring(1).substringBefore("]")   // bracketed IPv6: drop [ ] and any :port after ]
        } else {
            authority.substringBefore(":")                // hostname / IPv4: strip :port
        }.trim()
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
