package com.justme.xtls_core_proxy.privacy

/**
 * Pure builder of the `x-*` request headers Android Happ sends. Single source of truth for both
 * the wire headers (via [SubscriptionRefreshCoordinator]) and the settings-screen live preview,
 * so the two can never drift. Never emits `x-app-version` (Android Happ omits it).
 *
 * Header-injection defense lives here at the trust boundary: [sanitize] strips CR/LF and control
 * chars and caps length, so no prefs value (however it got there) can smuggle a second header.
 */
object DeviceIdentityHeaders {

    private const val MAX_VALUE_LEN = 128

    fun build(
        settings: DeviceIdentitySettings,
        realOsVersion: String,
        realModel: String,
        realLanguage: String,
    ): Map<String, String> {
        if (!settings.sendHwid) return emptyMap()

        val out = LinkedHashMap<String, String>()
        out["x-hwid"] = sanitize(settings.hwid)

        if (settings.customEnabled) {
            putIfPresent(out, "x-device-os", settings.customOs)
            putIfPresent(out, "x-ver-os", settings.customOsVersion)
            putIfPresent(out, "x-device-model", settings.customModel)
            putLocale(out, settings.customLocale)
            return out
        }

        when (settings.identityMode) {
            IdentityMode.NONE -> Unit // x-hwid only
            IdentityMode.REAL_DEVICE -> putDevice(out, "Android", realOsVersion, realModel, realLanguage)
            IdentityMode.ANDROID -> {
                val s = SpoofIdentities.resolveAndroid(
                    settings.hwid, settings.androidVersionPin, settings.androidModelPin
                )
                putDevice(out, s.os, s.verOs, s.model, realLanguage)
            }
            IdentityMode.IPHONE -> {
                val s = SpoofIdentities.resolveIphone(
                    settings.hwid, settings.iosVersionPin, settings.iosModelPin
                )
                putDevice(out, s.os, s.verOs, s.model, realLanguage)
            }
        }
        return out
    }

    private fun putDevice(out: MutableMap<String, String>, os: String, ver: String, model: String, lang: String) {
        putIfPresent(out, "x-device-os", os)
        putIfPresent(out, "x-ver-os", ver)
        putIfPresent(out, "x-device-model", model)
        putLocale(out, lang)
    }

    /** Sanitize then lowercase — Android Happ requires a lowercase language code. */
    private fun putLocale(out: MutableMap<String, String>, raw: String?) {
        val v = raw?.let { sanitize(it).lowercase() }.orEmpty()
        if (v.isNotEmpty()) out["x-device-locale"] = v
    }

    private fun putIfPresent(out: MutableMap<String, String>, key: String, raw: String?) {
        val v = raw?.let { sanitize(it) }.orEmpty()
        if (v.isNotEmpty()) out[key] = v
    }

    /** Trim, strip CR/LF + control chars (defeats header injection), cap length. */
    fun sanitize(raw: String): String =
        raw.filter { it.code >= 0x20 && it.code != 0x7F }
            .trim()
            .take(MAX_VALUE_LEN)
}
