package com.justme.xtls_core_proxy.privacy

/** Which device identity is presented to subscription panels. */
enum class IdentityMode { REAL_DEVICE, ANDROID, IPHONE, NONE }

/** Which User-Agent the subscription fetch presents. */
enum class UserAgentMode { DEFAULT, HAPP_LIKE }

/**
 * Immutable snapshot of the user's device-identity (HWID) settings. Loaded once per
 * subscription fetch by [DeviceIdentityRepository] and passed into the pure builders. Pins are
 * null when "Auto" (derive from the HWID hash). Custom fields are null/blank when that header
 * should be omitted.
 */
data class DeviceIdentitySettings(
    val sendHwid: Boolean = true,
    val hwid: String,
    val identityMode: IdentityMode = IdentityMode.REAL_DEVICE,
    val androidVersionPin: String? = null,
    val androidModelPin: String? = null,
    val iosVersionPin: String? = null,
    val iosModelPin: String? = null,
    val customEnabled: Boolean = false,
    val customOs: String? = null,
    val customOsVersion: String? = null,
    val customModel: String? = null,
    val customLocale: String? = null,
    val userAgentMode: UserAgentMode = UserAgentMode.DEFAULT,
)
