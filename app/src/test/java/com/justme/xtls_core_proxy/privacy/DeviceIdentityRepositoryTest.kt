package com.justme.xtls_core_proxy.privacy

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DeviceIdentityRepositoryTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var context: Context

    @Before
    fun setUp() {
        editor = mock {
            on { putBoolean(any(), any()) } doReturn it
            on { putString(any(), any()) } doReturn it
        }
        prefs = mock {
            on { edit() } doReturn editor
        }
        context = mock {
            on { getSharedPreferences(eq("xray_prefs"), eq(Context.MODE_PRIVATE)) } doReturn prefs
        }
    }

    @Test
    fun formatHwid_isSixteenLowercaseHex() {
        val re = Regex("^[0-9a-f]{16}$")
        assertTrue(re.matches(DeviceIdentityRepository.formatHwid(0L)))
        assertTrue(re.matches(DeviceIdentityRepository.formatHwid(-1L)))
        assertTrue(re.matches(DeviceIdentityRepository.formatHwid(1234567890123456789L)))
        assertEquals("0000000000000000", DeviceIdentityRepository.formatHwid(0L))
        assertEquals("ffffffffffffffff", DeviceIdentityRepository.formatHwid(-1L))
    }

    @Test
    fun load_mintsAndPersistsHwid_whenAbsent() {
        // No stored hwid -> repository mints one and writes it back.
        whenever(prefs.getString(eq("hwid_value"), eq(null))).thenReturn(null)
        whenever(prefs.getBoolean(eq("hwid_send"), any())).thenReturn(true)
        whenever(prefs.getString(eq("hwid_mode"), eq(null))).thenReturn("REAL_DEVICE")
        whenever(prefs.getBoolean(eq("hwid_custom_enabled"), any())).thenReturn(false)
        whenever(prefs.getString(eq("hwid_ua_mode"), eq(null))).thenReturn("DEFAULT")

        val settings = DeviceIdentityRepository.load(context)

        assertTrue(Regex("^[0-9a-f]{16}$").matches(settings.hwid))
        verify(editor).putString(eq("hwid_value"), eq(settings.hwid))
        verify(editor).apply()
    }

    @Test
    fun load_returnsStoredHwid_whenPresent() {
        whenever(prefs.getString(eq("hwid_value"), eq(null))).thenReturn("a983997074675192")
        whenever(prefs.getBoolean(eq("hwid_send"), any())).thenReturn(true)
        whenever(prefs.getString(eq("hwid_mode"), eq(null))).thenReturn("ANDROID")
        whenever(prefs.getString(eq("hwid_android_version"), eq(null))).thenReturn("16")
        whenever(prefs.getString(eq("hwid_android_model"), eq(null))).thenReturn(null)
        whenever(prefs.getBoolean(eq("hwid_custom_enabled"), any())).thenReturn(false)
        whenever(prefs.getString(eq("hwid_ua_mode"), eq(null))).thenReturn("HAPP_LIKE")

        val settings = DeviceIdentityRepository.load(context)

        assertEquals("a983997074675192", settings.hwid)
        assertEquals(IdentityMode.ANDROID, settings.identityMode)
        assertEquals("16", settings.androidVersionPin)
        assertEquals(UserAgentMode.HAPP_LIKE, settings.userAgentMode)
    }

    @Test
    fun resetHwid_writesFreshSixteenHex() {
        val fresh = DeviceIdentityRepository.resetHwid(context)
        assertTrue(Regex("^[0-9a-f]{16}$").matches(fresh))
        verify(editor).putString(eq("hwid_value"), eq(fresh))
        verify(editor).apply()
    }
}
