package com.justme.xtls_core_proxy.sideload

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SideloadWarningRepositoryTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var context: Context

    @Before
    fun setUp() {
        editor = mock {
            on { putInt(any(), any()) } doReturn it
        }
        prefs = mock {
            on { edit() } doReturn editor
        }
        context = mock {
            on { getSharedPreferences(eq("xray_prefs"), eq(Context.MODE_PRIVATE)) } doReturn prefs
        }
    }

    @Test
    fun shouldShow_true_whenStoredVersionLowerThanCurrent() {
        whenever(prefs.getInt(eq("sideload_warning_last_version"), eq(0))).thenReturn(0)

        assertTrue(SideloadWarningRepository.shouldShow(context, currentVersionCode = 1))
    }

    @Test
    fun shouldShow_false_whenStoredVersionEqualsCurrent() {
        whenever(prefs.getInt(eq("sideload_warning_last_version"), eq(0))).thenReturn(5)

        assertFalse(SideloadWarningRepository.shouldShow(context, currentVersionCode = 5))
    }

    @Test
    fun shouldShow_false_whenStoredVersionGreaterThanCurrent() {
        whenever(prefs.getInt(eq("sideload_warning_last_version"), eq(0))).thenReturn(7)

        assertFalse(SideloadWarningRepository.shouldShow(context, currentVersionCode = 6))
    }

    @Test
    fun markShown_persistsVersionCode() {
        SideloadWarningRepository.markShown(context, versionCode = 3)

        verify(editor).putInt(eq("sideload_warning_last_version"), eq(3))
        verify(editor).apply()
    }
}
