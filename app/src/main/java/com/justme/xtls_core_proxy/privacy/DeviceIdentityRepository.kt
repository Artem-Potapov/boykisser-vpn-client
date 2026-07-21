package com.justme.xtls_core_proxy.privacy

import android.content.Context
import java.security.SecureRandom

/**
 * Persists device-identity settings in the shared `xray_prefs` store (same store as
 * FragmentationPreferences / LogPreferences). Mints a random 16-char lowercase hex HWID
 * (Android-ID shape) lazily on first read and persists it; never reads the real
 * Settings.Secure.ANDROID_ID.
 */
object DeviceIdentityRepository {
    private const val PREFS = "xray_prefs"
    private const val KEY_SEND = "hwid_send"
    private const val KEY_HWID = "hwid_value"
    private const val KEY_MODE = "hwid_mode"
    private const val KEY_ANDROID_VERSION = "hwid_android_version"
    private const val KEY_ANDROID_MODEL = "hwid_android_model"
    private const val KEY_IOS_VERSION = "hwid_ios_version"
    private const val KEY_IOS_MODEL = "hwid_ios_model"
    private const val KEY_CUSTOM_ENABLED = "hwid_custom_enabled"
    private const val KEY_CUSTOM_OS = "hwid_custom_os"
    private const val KEY_CUSTOM_OS_VERSION = "hwid_custom_os_version"
    private const val KEY_CUSTOM_MODEL = "hwid_custom_model"
    private const val KEY_CUSTOM_LOCALE = "hwid_custom_locale"
    private const val KEY_UA_MODE = "hwid_ua_mode"

    private val random = SecureRandom()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 64 random bits rendered as exactly 16 lowercase hex chars (two's-complement for negatives). */
    internal fun formatHwid(bits: Long): String = String.format("%016x", bits)

    fun load(context: Context): DeviceIdentitySettings {
        val p = prefs(context)
        val hwid = p.getString(KEY_HWID, null) ?: mintAndStore(context)
        return DeviceIdentitySettings(
            sendHwid = p.getBoolean(KEY_SEND, true),
            hwid = hwid,
            identityMode = parseMode(p.getString(KEY_MODE, null)),
            androidVersionPin = p.getString(KEY_ANDROID_VERSION, null),
            androidModelPin = p.getString(KEY_ANDROID_MODEL, null),
            iosVersionPin = p.getString(KEY_IOS_VERSION, null),
            iosModelPin = p.getString(KEY_IOS_MODEL, null),
            customEnabled = p.getBoolean(KEY_CUSTOM_ENABLED, false),
            customOs = p.getString(KEY_CUSTOM_OS, null),
            customOsVersion = p.getString(KEY_CUSTOM_OS_VERSION, null),
            customModel = p.getString(KEY_CUSTOM_MODEL, null),
            customLocale = p.getString(KEY_CUSTOM_LOCALE, null),
            userAgentMode = parseUaMode(p.getString(KEY_UA_MODE, null)),
        )
    }

    fun save(context: Context, settings: DeviceIdentitySettings) {
        prefs(context).edit().apply {
            putBoolean(KEY_SEND, settings.sendHwid)
            putString(KEY_HWID, settings.hwid)
            putString(KEY_MODE, settings.identityMode.name)
            putString(KEY_ANDROID_VERSION, settings.androidVersionPin)
            putString(KEY_ANDROID_MODEL, settings.androidModelPin)
            putString(KEY_IOS_VERSION, settings.iosVersionPin)
            putString(KEY_IOS_MODEL, settings.iosModelPin)
            putBoolean(KEY_CUSTOM_ENABLED, settings.customEnabled)
            putString(KEY_CUSTOM_OS, settings.customOs)
            putString(KEY_CUSTOM_OS_VERSION, settings.customOsVersion)
            putString(KEY_CUSTOM_MODEL, settings.customModel)
            putString(KEY_CUSTOM_LOCALE, settings.customLocale)
            putString(KEY_UA_MODE, settings.userAgentMode.name)
            apply()
        }
    }

    /** Mints a fresh 16-hex HWID, persists it, and returns it. Re-rolls Auto identity + UA build. */
    fun resetHwid(context: Context): String = mintAndStore(context)

    private fun mintAndStore(context: Context): String {
        val hwid = formatHwid(random.nextLong())
        prefs(context).edit().putString(KEY_HWID, hwid).apply()
        return hwid
    }

    private fun parseMode(raw: String?): IdentityMode =
        IdentityMode.entries.firstOrNull { it.name == raw } ?: IdentityMode.REAL_DEVICE

    private fun parseUaMode(raw: String?): UserAgentMode =
        UserAgentMode.entries.firstOrNull { it.name == raw } ?: UserAgentMode.DEFAULT
}
