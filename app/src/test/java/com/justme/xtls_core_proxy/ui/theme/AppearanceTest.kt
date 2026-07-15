package com.justme.xtls_core_proxy.ui.theme

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
    @Test
    fun resolveScheme_systemFollowsDeviceDarkness() {
        assertEquals(ResolvedScheme.DARK, resolveScheme(ThemeMode.SYSTEM, systemInDark = true))
        assertEquals(ResolvedScheme.LIGHT, resolveScheme(ThemeMode.SYSTEM, systemInDark = false))
    }

    @Test
    fun resolveScheme_explicitModesIgnoreDeviceDarkness() {
        assertEquals(ResolvedScheme.LIGHT, resolveScheme(ThemeMode.LIGHT, systemInDark = true))
        assertEquals(ResolvedScheme.DARK, resolveScheme(ThemeMode.DARK, systemInDark = false))
        assertEquals(ResolvedScheme.TRUE_DARK, resolveScheme(ThemeMode.TRUE_DARK, systemInDark = false))
    }

    @Test
    fun useDynamic_onlyWhenPrefOnAndApi31Plus() {
        assertTrue(useDynamic(dynamicColorPref = true, sdkInt = Build.VERSION_CODES.S))
        assertTrue(useDynamic(dynamicColorPref = true, sdkInt = Build.VERSION_CODES.TIRAMISU))
        assertFalse(useDynamic(dynamicColorPref = true, sdkInt = Build.VERSION_CODES.R))
        assertFalse(useDynamic(dynamicColorPref = false, sdkInt = Build.VERSION_CODES.TIRAMISU))
    }

    @Test
    fun fromName_unknownFallsBackToSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName("bogus"))
        assertEquals(ThemeMode.TRUE_DARK, ThemeMode.fromName("TRUE_DARK"))
    }
}
