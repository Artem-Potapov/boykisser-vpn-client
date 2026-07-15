package com.justme.xtls_core_proxy.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// Fixed brand color for the promoted "Boykisser VPN" surfaces. Used directly (NOT via
// MaterialTheme.colorScheme) so device dynamic-color theming cannot override it.
// Content (text/icons) on top of it should be white.
val BoykisserMagenta = Color(0xFFD81B60)

// Brand palette seeded from BoykisserMagenta (#D81B60) so app chrome shares the brand identity.
// Used only when Material You / dynamic color is OFF (or unavailable on API < 31).
val BrandMagenta = Color(0xFFD81B60)
val BrandMagentaLight = Color(0xFFFF5C8D)
val BrandMagentaDeep = Color(0xFFA00037)
val BrandMauve = Color(0xFF7D5260)
val BrandPink = Color(0xFFEFB8C8)