package com.justme.xtls_core_proxy.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityHeadersTest {

    private fun base(
        sendHwid: Boolean = true,
        mode: IdentityMode = IdentityMode.REAL_DEVICE,
        customEnabled: Boolean = false,
        customOs: String? = null,
        customOsVersion: String? = null,
        customModel: String? = null,
        customLocale: String? = null,
    ) = DeviceIdentitySettings(
        sendHwid = sendHwid,
        hwid = "a983997074675192",
        identityMode = mode,
        customEnabled = customEnabled,
        customOs = customOs,
        customOsVersion = customOsVersion,
        customModel = customModel,
        customLocale = customLocale,
    )

    @Test
    fun sendHwidOff_yieldsEmptyMap() {
        assertTrue(DeviceIdentityHeaders.build(base(sendHwid = false), "16", "SM-S942U", "en").isEmpty())
    }

    @Test
    fun noneMode_isHwidOnly_neverAppVersion() {
        val h = DeviceIdentityHeaders.build(base(mode = IdentityMode.NONE), "16", "SM-S942U", "en")
        assertEquals(mapOf("x-hwid" to "a983997074675192"), h)
        assertFalse(h.containsKey("x-app-version"))
    }

    @Test
    fun realDevice_emitsFiveHeaders_lowercaseLanguage() {
        val h = DeviceIdentityHeaders.build(base(mode = IdentityMode.REAL_DEVICE), "16", "SM-S942U", "en")
        assertEquals("a983997074675192", h["x-hwid"])
        assertEquals("Android", h["x-device-os"])
        assertEquals("16", h["x-ver-os"])
        assertEquals("SM-S942U", h["x-device-model"])
        assertEquals("en", h["x-device-locale"])
        assertFalse(h.containsKey("x-app-version"))
    }

    @Test
    fun androidMode_usesSpoofOsAndPlausibleValues() {
        val h = DeviceIdentityHeaders.build(base(mode = IdentityMode.ANDROID), "16", "SM-S942U", "ru")
        assertEquals("Android", h["x-device-os"])
        assertTrue(h["x-ver-os"] in SpoofIdentities.ANDROID_VERSIONS)
        assertEquals("ru", h["x-device-locale"])
    }

    @Test
    fun iphoneMode_reportsIos() {
        val h = DeviceIdentityHeaders.build(base(mode = IdentityMode.IPHONE), "16", "SM-S942U", "en")
        assertEquals("iOS", h["x-device-os"])
        assertTrue(h["x-ver-os"] in SpoofIdentities.IOS_VERSIONS)
    }

    @Test
    fun customMode_blankFieldsAreOmitted() {
        val h = DeviceIdentityHeaders.build(
            base(customEnabled = true, customOs = "Android", customOsVersion = "15", customModel = "", customLocale = null),
            "16", "SM-S942U", "en"
        )
        assertEquals("Android", h["x-device-os"])
        assertEquals("15", h["x-ver-os"])
        assertFalse(h.containsKey("x-device-model")) // blank -> omitted
        assertFalse(h.containsKey("x-device-locale")) // null -> omitted
        assertTrue(h.containsKey("x-hwid"))
    }

    @Test
    fun customLocale_mixedCase_isEmittedLowercase() {
        val h = DeviceIdentityHeaders.build(
            base(customEnabled = true, customOs = "Android", customLocale = "En"),
            "16", "SM-S942U", "EN",
        )
        assertEquals("en", h["x-device-locale"])
    }

    @Test
    fun sanitize_stripsCrlf_preventingHeaderInjection() {
        val h = DeviceIdentityHeaders.build(
            base(customEnabled = true, customModel = "Pixel\r\nX-Evil: 1"),
            "16", "SM-S942U", "en"
        )
        val model = h["x-device-model"]!!
        assertFalse(model.contains("\r"))
        assertFalse(model.contains("\n"))
        // No second header smuggled in.
        assertFalse(h.containsKey("x-evil"))
        assertFalse(h.containsKey("X-Evil"))
    }

    @Test
    fun sanitize_capsLength() {
        assertTrue(DeviceIdentityHeaders.sanitize("x".repeat(500)).length <= 128)
    }
}
