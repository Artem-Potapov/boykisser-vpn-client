package com.justme.xtls_core_proxy.privacy

/**
 * Builds the effective default User-Agent. `DEFAULT` passes through the app's own UA; `HAPP_LIKE`
 * emits `Happ/<HAPP_VERSION>/<os>/<build>` where `<os>` mirrors the identity's `x-device-os` (so
 * UA and headers can never disagree) and `<build>` is the unsigned-decimal HWID hash (stable per
 * install, re-rolls on HWID reset). Independent of `sendHwid`: a panel can filter by app without
 * enforcing a device limit.
 */
object UserAgentBuilder {

    const val HAPP_VERSION = "3.26.3"

    fun build(settings: DeviceIdentitySettings, defaultUserAgent: String): String =
        when (settings.userAgentMode) {
            UserAgentMode.DEFAULT -> defaultUserAgent
            UserAgentMode.HAPP_LIKE -> {
                val os = resolveOs(settings)
                val build = java.lang.Long.toUnsignedString(SpoofIdentities.seed(settings.hwid))
                "Happ/$HAPP_VERSION/$os/$build"
            }
        }

    private fun resolveOs(settings: DeviceIdentitySettings): String {
        if (settings.customEnabled) {
            val os = settings.customOs?.trim().orEmpty()
            return when {
                os.isBlank() -> "Android"
                os.contains("ios", ignoreCase = true) || os.contains("iphone", ignoreCase = true) -> "iOS"
                else -> "Android"
            }
        }
        return when (settings.identityMode) {
            IdentityMode.IPHONE -> "iOS"
            IdentityMode.ANDROID, IdentityMode.REAL_DEVICE, IdentityMode.NONE -> "Android"
        }
    }
}
