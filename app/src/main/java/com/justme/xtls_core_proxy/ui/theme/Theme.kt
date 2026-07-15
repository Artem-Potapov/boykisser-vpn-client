package com.justme.xtls_core_proxy.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val BrandLightColorScheme = lightColorScheme(
    primary = BrandMagenta,
    secondary = BrandMauve,
    tertiary = BrandMagentaDeep,
)

private val BrandDarkColorScheme = darkColorScheme(
    primary = BrandMagentaLight,
    secondary = BrandPink,
    tertiary = BrandMagentaLight,
)

// True Dark = the dark scheme with backgrounds/surfaces forced to pure black (OLED).
private val BrandTrueDarkColorScheme = BrandDarkColorScheme.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF121212),
)

@Composable
fun XTLS_CORE_PROXYTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val prefs by AppearanceRepository.state.collectAsState()
    val resolved = resolveScheme(prefs.themeMode, isSystemInDarkTheme())
    // Keep `Build.VERSION.SDK_INT >= S` textually inline in each Material You branch: lint's NewApi
    // detector recognizes an inline SDK guard but cannot see through the useDynamic() helper, so
    // hoisting the check into a val/function reintroduces a lint-vital NewApi failure that breaks
    // release builds. useDynamic() stays the source of truth for the *preference* (screen + tests).
    val colorScheme = when {
        prefs.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && resolved == ResolvedScheme.LIGHT ->
            dynamicLightColorScheme(context)
        prefs.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && resolved == ResolvedScheme.DARK ->
            dynamicDarkColorScheme(context)
        prefs.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && resolved == ResolvedScheme.TRUE_DARK ->
            dynamicDarkColorScheme(context).copy(
                background = Color(0xFF000000),
                surface = Color(0xFF000000),
                surfaceVariant = Color(0xFF121212),
            )
        resolved == ResolvedScheme.LIGHT -> BrandLightColorScheme
        resolved == ResolvedScheme.DARK -> BrandDarkColorScheme
        else -> BrandTrueDarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
