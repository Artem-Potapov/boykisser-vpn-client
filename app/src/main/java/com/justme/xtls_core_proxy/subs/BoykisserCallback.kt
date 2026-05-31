package com.justme.xtls_core_proxy.subs

/**
 * Parses/validates the deep-link & App Link callback payload that adds a Boykisser VPN
 * subscription. Pure (no Android deps): the handler activity extracts the raw `sub` query
 * value and passes it here. Keep these constants in sync with AndroidManifest.xml.
 */
object BoykisserCallback {
    const val SCHEME = "bkvpn"                // bkvpn://add?sub=<encoded>
    const val APPLINK_HOST = "boykiss3r.site" // https://boykiss3r.site/app/add?sub=<encoded>
    const val APPLINK_PATH = "/app/add"
    const val PARAM_SUB = "sub"

    /**
     * Returns [sub] (trimmed) when it is a non-blank, approved subscription link; else null.
     * [sub] is the already-URL-decoded query value supplied by the callback activity.
     */
    fun validate(sub: String?): String? {
        val candidate = sub?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        return if (PromotedSubscription.isApprovedLink(candidate)) candidate else null
    }
}
