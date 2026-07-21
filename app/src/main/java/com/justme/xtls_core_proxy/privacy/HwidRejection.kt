package com.justme.xtls_core_proxy.privacy

/** Why a panel rejected a subscription fetch on HWID grounds. */
enum class HwidRejection { MAX_DEVICES, NOT_SUPPORTED }

/**
 * Reads Remnawave's HWID response headers off the error path (they arrive even on the camouflage
 * 404). Mirrors [SubscriptionFetcher.parseIntervalHeader]: case-insensitive names, skips the null
 * status-line key, and treats only a literal `"true"` (any case) as set.
 */
object HwidRejectionDetector {

    fun detect(headers: Map<String, List<String>>, hwidWasSent: Boolean): HwidRejection? {
        // Max-devices wins over not-supported when both are present.
        if (flag(headers, "x-hwid-max-devices-reached") || flag(headers, "x-hwid-limit")) {
            return HwidRejection.MAX_DEVICES
        }
        if (!hwidWasSent && flag(headers, "x-hwid-not-supported")) {
            return HwidRejection.NOT_SUPPORTED
        }
        return null
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun flag(headers: Map<String, List<String>>, name: String): Boolean {
        for ((rawKey, values) in headers) {
            if (rawKey == null) continue // status line under a null key
            if (!rawKey.equals(name, ignoreCase = true)) continue
            if (values.any { it.trim().equals("true", ignoreCase = true) }) return true
        }
        return false
    }
}
