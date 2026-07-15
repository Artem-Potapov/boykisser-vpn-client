# App Appearance: Theme Modes, True Dark, Material You

Maintainer reference for the user-selectable theme: the four theme modes, how True Dark is derived,
the live repository-driven recompose path, and a lint gotcha in `Theme.kt` that has already broken a
release build once — don't "clean it up."

## Why this exists

Before this feature the app had a single hard-coded Material You/brand color scheme with no user
control. This feature adds a `Settings → UI → App appearance` screen with four theme modes (including
an OLED-friendly True Dark) and an optional Material You (dynamic color) toggle, both applied live to
the whole running app without a restart.

## The two knobs: `ThemeMode` and dynamic color

[`ui/theme/Appearance.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/ui/theme/Appearance.kt)
defines the pure domain model, independent of Compose/Android so it's directly JVM-testable:

```kotlin
enum class ThemeMode { SYSTEM, LIGHT, DARK, TRUE_DARK }
enum class ResolvedScheme { LIGHT, DARK, TRUE_DARK }
data class AppearancePrefs(val themeMode: ThemeMode, val dynamicColor: Boolean)

fun resolveScheme(mode: ThemeMode, systemInDark: Boolean): ResolvedScheme
fun useDynamic(dynamicColorPref: Boolean, sdkInt: Int): Boolean
```

- `resolveScheme` is the only place `SYSTEM` gets resolved against the device's actual dark/light
  state (`isSystemInDarkTheme()`); `LIGHT`/`DARK`/`TRUE_DARK` are explicit and ignore the device.
- `useDynamic` gates Material You on **both** the user's preference **and** `sdkInt >=
  Build.VERSION_CODES.S` (Android 12) — dynamic color APIs don't exist below API 31.
- `ThemeMode.fromName(name: String?)` is the persisted-enum fallback: unknown/legacy/`null` names fall
  back to `SYSTEM`, mirroring `XrayLogLevel.fromName`'s fallback-to-default convention (see
  [`logs-screen.md`](logs-screen.md)).

## `AppearanceRepository` — process-wide live state

[`ui/theme/AppearanceRepository.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/ui/theme/AppearanceRepository.kt)
persists both knobs in the shared `xray_prefs` `SharedPreferences` store (same store `LogPreferences`,
`FragmentationPreferences`, and `PromoGate` use) and exposes them as a process-wide `StateFlow`:

| Key | Type | Default |
|---|---|---|
| `appearance_theme_mode` | `String` (enum name) | none stored → `ThemeMode.fromName(null)` → `SYSTEM` |
| `appearance_dynamic_color` | `Boolean` | `true` |

`load(context)` reads both keys, publishes the result to `_state`, and returns it; `setThemeMode` /
`setDynamicColor` write through to `SharedPreferences` **and** update `_state` in the same call. This
mirrors the `KillSwitchRepository` pattern: a singleton `object` backing a `StateFlow<AppearancePrefs>`
that any composable can `collectAsState()` against.

**`load` must run once at app startup, before first UI.**
[`XtlsApplication.onCreate`](../../app/src/main/java/com/justme/xtls_core_proxy/XtlsApplication.kt)
calls `AppearanceRepository.load(this)` before `LogRepository.setMaxLines(...)`. Skipping this leaves
`_state` at its hard-coded default (`SYSTEM`, dynamic color on) until the first screen that happens to
call `load()` itself — there isn't one, so this is the single required call site.

Because `XTLS_CORE_PROXYTheme` (below) collects `AppearanceRepository.state`, **any** screen that calls
`setThemeMode`/`setDynamicColor` — today just `AppearanceSettingsActivity` — repaints every other
composable in the process immediately, with no activity recreation and no "restart to apply."

## `Theme.kt`: brand schemes, True Dark, and the inline-SDK-guard gotcha

[`ui/theme/Theme.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/ui/theme/Theme.kt) defines two
static brand `ColorScheme`s built from
[`ui/theme/Color.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/ui/theme/Color.kt)'s palette
(`BrandMagenta` / `BrandMagentaLight` / `BrandMagentaDeep` / `BrandMauve` / `BrandPink`, all seeded from
`BoykisserMagenta` so app chrome shares the promoted-subscription brand identity — used only when
Material You is off or unavailable):

```kotlin
private val BrandLightColorScheme = lightColorScheme(primary = BrandMagenta, ...)
private val BrandDarkColorScheme = darkColorScheme(primary = BrandMagentaLight, ...)
```

**True Dark is the dark scheme with `background`/`surface` forced to pure black** (`0xFF000000`,
`surfaceVariant` to `0xFF121212`), for OLED power savings and maximum contrast:

```kotlin
private val BrandTrueDarkColorScheme = BrandDarkColorScheme.copy(
    background = Color(0xFF000000), surface = Color(0xFF000000), surfaceVariant = Color(0xFF121212),
)
```

The same "force background/surface to pure black" transform is applied to the **dynamic** dark scheme
too (`dynamicDarkColorScheme(context).copy(background = ..., surface = ..., surfaceVariant = ...)`), so
True Dark composes with Material You rather than being mutually exclusive with it.

`XTLS_CORE_PROXYTheme` picks the actual `ColorScheme` with a `when` over three inputs — the dynamic
color preference, the SDK level, and `resolveScheme(prefs.themeMode, isSystemInDarkTheme())`:

```kotlin
val colorScheme = when {
    prefs.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && resolved == ResolvedScheme.LIGHT ->
        dynamicLightColorScheme(context)
    prefs.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && resolved == ResolvedScheme.DARK ->
        dynamicDarkColorScheme(context)
    prefs.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && resolved == ResolvedScheme.TRUE_DARK ->
        dynamicDarkColorScheme(context).copy(background = ..., surface = ..., surfaceVariant = ...)
    resolved == ResolvedScheme.LIGHT -> BrandLightColorScheme
    resolved == ResolvedScheme.DARK -> BrandDarkColorScheme
    else -> BrandTrueDarkColorScheme
}
```

**The `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` guard is written out textually in each of the
three Material You branches — do not refactor it to call `useDynamic(prefs.dynamicColor,
Build.VERSION.SDK_INT)` instead, and do not hoist it into a shared `val`.** `dynamicLightColorScheme`/
`dynamicDarkColorScheme` are `@RequiresApi(Build.VERSION_CODES.S)` APIs. Android Lint's `NewApi`
detector recognizes an **inline** `Build.VERSION.SDK_INT >= S` (or `Build.VERSION_CODES.S`) comparison
as a guard and suppresses the warning at that call site — but it cannot see through a function call
like `useDynamic()`, even though `useDynamic()` performs the exact same comparison. Calling
`dynamicDarkColorScheme(context)` from inside `if (useDynamic(...))` therefore still trips `NewApi`. On
a **release** build (`isMinifyEnabled = true`, lint runs as `lintVitalRelease`), an unsuppressed `NewApi`
error **fails the build**, not just the report — this bit the branch once and was fixed in
`dfa42c4 fix(appearance): inline SDK guard for Material You branches so lint NewApi passes`. `useDynamic()`
remains the source of truth for the *preference* everywhere else (the settings screen's switch state,
and `AppearanceTest`'s unit tests) — only the three `Theme.kt` branches need the guard spelled out
inline, and only because lint can't trace through the helper.

## `AppearanceSettingsActivity` — the settings screen

[`settings/AppearanceSettingsActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/settings/AppearanceSettingsActivity.kt)
renders a radio list of the four `ThemeMode` values (each tap calls
`AppearanceRepository.setThemeMode` directly — no local "pending" state, no Save button) plus a
Material You `Switch`, shown only when `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`
(`dynamicAvailable`). The switch's checked state reads `useDynamic(prefs.dynamicColor,
Build.VERSION.SDK_INT)` rather than the raw `prefs.dynamicColor` — functionally redundant given the
row is already gated behind `dynamicAvailable`, but keeps the switch's displayed state consistent with
the same pure gate `Theme.kt` uses, rather than trusting the stored preference blindly. Toggling calls
`AppearanceRepository.setDynamicColor(context, it)`.

## Components

| File | Role |
|---|---|
| [`ui/theme/Appearance.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/ui/theme/Appearance.kt) | `ThemeMode`, `ResolvedScheme`, `AppearancePrefs`; pure `resolveScheme`, `useDynamic`. |
| [`ui/theme/AppearanceRepository.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/ui/theme/AppearanceRepository.kt) | `xray_prefs`-backed `load`/`setThemeMode`/`setDynamicColor`; process-wide `state: StateFlow<AppearancePrefs>`. |
| [`ui/theme/Theme.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/ui/theme/Theme.kt) | `XTLS_CORE_PROXYTheme` composable: brand schemes, True Dark pure-black transform, the inline-SDK-guard `when`. |
| [`ui/theme/Color.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/ui/theme/Color.kt) | Brand palette (`BrandMagenta` et al.), seeded from `BoykisserMagenta`. |
| [`settings/AppearanceSettingsActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/settings/AppearanceSettingsActivity.kt) | The settings screen: mode radio list + Material You switch, both writing straight through to `AppearanceRepository`. |
| [`XtlsApplication.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/XtlsApplication.kt) | `onCreate` calls `AppearanceRepository.load(this)` before any UI is shown. |

## Known limitations

- **No per-screen theme override.** `XTLS_CORE_PROXYTheme` is applied uniformly; there is no mechanism
  for a single screen to opt out of e.g. True Dark (not needed today).
- **Material You row disappears below API 31** rather than showing disabled — consistent with the rest
  of the app not showing controls for OS features that don't exist on the device.

## Testing

- **JUnit4 (JVM)** — [`ui/theme/AppearanceTest.kt`](../../app/src/test/java/com/justme/xtls_core_proxy/ui/theme/AppearanceTest.kt):
  `resolveScheme` follows device darkness for `SYSTEM` and ignores it for the three explicit modes;
  `useDynamic` requires both the preference **and** API 31+; `ThemeMode.fromName` falls back to
  `SYSTEM` for `null`/unknown names and round-trips `TRUE_DARK`.
- **On-device (manual)**: cycle all four modes and confirm the whole app (not just the settings screen)
  repaints live with no restart; confirm True Dark shows pure black backgrounds; on API 31+, toggle
  Material You and confirm wallpaper-derived colors apply; run a release build
  (`:app:lintDebug`/`assembleRelease`) after touching `Theme.kt` and confirm no `NewApi` failure.

## Related docs

- [`settings-hub.md`](settings-hub.md) — the hub row that opens this screen.
