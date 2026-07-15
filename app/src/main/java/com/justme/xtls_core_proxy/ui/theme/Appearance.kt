package com.justme.xtls_core_proxy.ui.theme

import android.os.Build

/** User-selectable theme mode. Persisted by name via [AppearanceRepository]. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    TRUE_DARK;

    companion object {
        /** Parse a persisted enum name; unknown/legacy/null falls back to SYSTEM (today's behavior). */
        fun fromName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** Concrete scheme the theme should build, after resolving SYSTEM against device darkness. */
enum class ResolvedScheme { LIGHT, DARK, TRUE_DARK }

/** The two orthogonal appearance knobs. */
data class AppearancePrefs(val themeMode: ThemeMode, val dynamicColor: Boolean)

/** Pure: which scheme to build. SYSTEM follows the device; the rest are explicit. */
fun resolveScheme(mode: ThemeMode, systemInDark: Boolean): ResolvedScheme = when (mode) {
    ThemeMode.SYSTEM -> if (systemInDark) ResolvedScheme.DARK else ResolvedScheme.LIGHT
    ThemeMode.LIGHT -> ResolvedScheme.LIGHT
    ThemeMode.DARK -> ResolvedScheme.DARK
    ThemeMode.TRUE_DARK -> ResolvedScheme.TRUE_DARK
}

/** Pure: Material You is only usable when the user opted in AND the OS is Android 12+ (API 31). */
fun useDynamic(dynamicColorPref: Boolean, sdkInt: Int): Boolean =
    dynamicColorPref && sdkInt >= Build.VERSION_CODES.S
