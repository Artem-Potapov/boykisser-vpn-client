package com.justme.xtls_core_proxy.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAgentBuilderTest {

    private fun settings(
        mode: IdentityMode = IdentityMode.ANDROID,
        ua: UserAgentMode = UserAgentMode.HAPP_LIKE,
        customEnabled: Boolean = false,
        customOs: String? = null,
    ) = DeviceIdentitySettings(
        hwid = "a983997074675192",
        identityMode = mode,
        customEnabled = customEnabled,
        customOs = customOs,
        userAgentMode = ua,
    )

    @Test
    fun defaultMode_returnsPassedDefaultVerbatim() {
        assertEquals(
            "XTLSCoreProxy/2.2.1",
            UserAgentBuilder.build(settings(ua = UserAgentMode.DEFAULT), "XTLSCoreProxy/2.2.1")
        )
    }

    @Test
    fun happLike_android_mirrorsOsAndIsDeterministic() {
        val ua = UserAgentBuilder.build(settings(mode = IdentityMode.ANDROID), "XTLSCoreProxy/2.2.1")
        assertTrue(ua.startsWith("Happ/3.26.3/Android/"))
        assertEquals(ua, UserAgentBuilder.build(settings(mode = IdentityMode.ANDROID), "XTLSCoreProxy/2.2.1"))
        val build = ua.substringAfterLast('/')
        assertTrue("build should be all digits", build.isNotEmpty() && build.all { it.isDigit() })
    }

    @Test
    fun happLike_iphone_reportsIos() {
        val ua = UserAgentBuilder.build(settings(mode = IdentityMode.IPHONE), "XTLSCoreProxy/2.2.1")
        assertTrue(ua.startsWith("Happ/3.26.3/iOS/"))
    }

    @Test
    fun happLike_realDeviceAndNone_reportAndroid() {
        assertTrue(UserAgentBuilder.build(settings(mode = IdentityMode.REAL_DEVICE), "d").startsWith("Happ/3.26.3/Android/"))
        assertTrue(UserAgentBuilder.build(settings(mode = IdentityMode.NONE), "d").startsWith("Happ/3.26.3/Android/"))
    }

    @Test
    fun happLike_customIosOs_reportsIos() {
        val ua = UserAgentBuilder.build(
            settings(customEnabled = true, customOs = "iOS"), "d"
        )
        assertTrue(ua.startsWith("Happ/3.26.3/iOS/"))
    }
}
