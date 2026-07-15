package com.justme.xtls_core_proxy.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists appearance settings in the shared `xray_prefs` and exposes them as a process-wide
 * StateFlow so [XTLS_CORE_PROXYTheme] recomposes live when the user changes a setting.
 * Mirrors the KillSwitchRepository pattern. Call [load] once at app startup before first UI.
 */
object AppearanceRepository {
    private const val PREFS = "xray_prefs"
    private const val KEY_MODE = "appearance_theme_mode"
    private const val KEY_DYNAMIC = "appearance_dynamic_color"

    private val _state = MutableStateFlow(AppearancePrefs(ThemeMode.SYSTEM, dynamicColor = true))
    val state: StateFlow<AppearancePrefs> = _state.asStateFlow()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): AppearancePrefs {
        val p = prefs(context)
        val loaded = AppearancePrefs(
            themeMode = ThemeMode.fromName(p.getString(KEY_MODE, null)),
            dynamicColor = p.getBoolean(KEY_DYNAMIC, true),
        )
        _state.value = loaded
        return loaded
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
        _state.value = _state.value.copy(themeMode = mode)
    }

    fun setDynamicColor(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DYNAMIC, enabled).apply()
        _state.value = _state.value.copy(dynamicColor = enabled)
    }
}
