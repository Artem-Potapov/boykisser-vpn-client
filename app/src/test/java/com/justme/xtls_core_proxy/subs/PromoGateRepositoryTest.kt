package com.justme.xtls_core_proxy.subs

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

class PromoGateRepositoryTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var context: Context

    @Before
    fun setUp() {
        editor = mock {
            on { putBoolean(any(), any()) } doReturn it
        }
        prefs = mock {
            on { edit() } doReturn editor
        }
        context = mock {
            on { getSharedPreferences(eq("xray_prefs"), eq(Context.MODE_PRIVATE)) } doReturn prefs
        }
    }

    @Test
    fun isDisarmed_false_byDefault() {
        whenever(prefs.getBoolean(eq("promote_disarmed"), eq(false))).thenReturn(false)
        assertFalse(PromoGateRepository.isDisarmed(context))
    }

    @Test
    fun isDisarmed_true_whenStored() {
        whenever(prefs.getBoolean(eq("promote_disarmed"), eq(false))).thenReturn(true)
        assertTrue(PromoGateRepository.isDisarmed(context))
    }

    @Test
    fun setDisarmed_true_persists() {
        PromoGateRepository.setDisarmed(context, true)
        verify(editor).putBoolean(eq("promote_disarmed"), eq(true))
        verify(editor).apply()
    }

    @Test
    fun setDisarmed_false_persists() {
        PromoGateRepository.setDisarmed(context, false)
        verify(editor).putBoolean(eq("promote_disarmed"), eq(false))
        verify(editor).apply()
    }
}
