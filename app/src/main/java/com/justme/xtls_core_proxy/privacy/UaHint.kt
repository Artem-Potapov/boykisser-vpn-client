package com.justme.xtls_core_proxy.privacy

/**
 * Decides whether to append the "try a Happ User-Agent" hint. App-filtering has no clean signal,
 * so we suggest on its symptoms — a 403, or a 2xx that parsed to zero servers — but never when the
 * UA is already Happ-like (the advice would be wrong).
 */
object UaHint {

    fun shouldSuggest(httpStatus: Int?, parsedCount: Int, uaIsHappLike: Boolean): Boolean {
        if (uaIsHappLike) return false
        if (httpStatus == 403) return true
        return httpStatus != null && httpStatus in 200..299 && parsedCount == 0
    }
}
