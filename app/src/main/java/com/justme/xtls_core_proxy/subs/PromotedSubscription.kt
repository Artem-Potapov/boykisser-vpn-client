package com.justme.xtls_core_proxy.subs

import com.justme.xtls_core_proxy.db.Subscription
import java.net.URI

/**
 * Policy for the promoted "Boykisser VPN" subscription. Pure (no Android deps) so it is
 * unit-testable. Drives banner/row visibility and validates inbound deep-link payloads.
 */
object PromotedSubscription {

    private val approvedDomains = listOf(
        "somenewsteps.space",
        "boykisser-keys.top",
        "boykiss3r.site",
    )

    /**
     * True when [url] is an http/https URL whose host equals an approved domain or is a
     * dot-suffix subdomain of one. Rejects suffix/prefix spoofing and malformed URLs.
     */
    fun isApprovedLink(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        return approvedDomains.any { domain -> host == domain || host.endsWith(".$domain") }
    }

    /** True when any approved-domain subscription has been fetched over HTTP at least once. */
    fun hasValidSubscription(subs: List<Subscription>): Boolean =
        subs.any { isApprovedLink(it.url) && it.lastFetchedAt != null }
}
