# Settings Hub: Sectioned Structure, Reusable Components, Debug Placeholders

Maintainer reference for `SettingsHubActivity`: the sectioned single-scroll settings screen, the
reusable `SettingsSectionHeader`/`SettingsRow` components it (and `LogsActivity`) are built from, and
the `BuildConfig.DEBUG` placeholder-row convention that stakes out where real settings will eventually
land.

## Why this exists

Before this feature, settings were scattered across ad hoc entry points with no single place a user (or
a maintainer wiring in a new toggle) could go to find "all settings." `SettingsHubActivity` is that
single entry point — the one screen `MainActivity` links to — organized into fixed sections so future
settings have an obvious home instead of accreting onto whichever screen was open when they were added.

## Structure: one screen, five sections, single scroll

[`settings/SettingsHubActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/settings/SettingsHubActivity.kt)
renders a single `Column` inside `verticalScroll(rememberScrollState())` — not a `LazyColumn`, not
per-section sub-screens. Sections appear in this fixed order:

| Section | String resource | Real rows today |
|---|---|---|
| UI | `settings_section_ui` | Language (→ `LanguageSettingsActivity`) |
| Tunnel | `settings_section_tunnel` | Split tunnel (→ `SplitTunnelSettingsActivity`), Kill switch (→ `KillSwitchSettingsActivity`) |
| Advanced | `settings_section_advanced` | none — **the whole section is debug-only** (see below) |
| Diagnostics | `settings_section_diagnostics` | Logs (→ `LogsActivity`) |
| About | `settings_section_about` | Sideload warning (opens `SideloadWarningDialog` in-place), About (→ `AboutActivity`) |

Each real, navigable row calls a local `open(cls: Class<*>)` helper
(`context.startActivity(Intent(context, cls))`) except the sideload row, which sets
`showSideloadWarning = true` to show `SideloadWarningDialog` as an overlay rather than navigating away.

## The reusable components

[`ui/SettingsComponents.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/ui/SettingsComponents.kt)
defines both pieces every settings-style screen in the app is built from — `SettingsHubActivity` and
`LogsActivity` (for its level/buffer rows) both use them, and any new settings screen should too rather
than hand-rolling row layout.

`SettingsSectionHeader(title: String)` — a `labelLarge`, primary-colored section label with fixed
top/bottom padding (`20.dp` / `4.dp`). Purely a label; it carries no state or click behavior.

`SettingsRow(...)` — the single row primitive, with every knob a settings screen needs:

```kotlin
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    trailingValue: String? = null,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    badge: String? = null,
    onClick: (() -> Unit)? = null,
)
```

- `subtitle` — optional second line (max 2 lines, ellipsized), used for a short description
  (e.g. `settings_split_subtitle`).
- `trailingValue` — optional right-aligned value text (e.g. the current language name, or a log level /
  buffer size, as `LogsActivity` uses it).
- `leadingIcon` — an optional `ImageVector`; **only `material-icons-core` symbols are used today**
  (`Settings`, `AutoMirrored.Filled.List`, `Info`, `Warning`) — adding `material-icons-extended` is out
  of scope (see the icon note below).
- `enabled` — when `false`, all text/icon color drops to 38% alpha (`onSurface.copy(alpha = 0.38f)`),
  the row is not clickable regardless of `onClick`, and the trailing chevron is suppressed. This is the
  mechanism placeholder rows use.
- `badge` — optional small tertiary-colored label rendered next to the title; placeholder rows pass the
  shared `settings_badge_debug` string here.
- `onClick` — when non-null **and** `enabled`, the row is clickable and shows a trailing
  `KeyboardArrowRight` chevron; a row with `onClick = null` (or `enabled = false`) renders with no
  chevron and no click affordance.

## The `BuildConfig.DEBUG` placeholder convention

Several rows across the UI, Tunnel, Advanced, and About sections exist only to show where a
not-yet-implemented setting will eventually live. Every one of them follows the same shape:

```kotlin
if (BuildConfig.DEBUG) SettingsRow(
    title = stringResource(R.string.settings_ph_appearance),
    enabled = false, badge = badge
)
```

i.e. `enabled = false` (greyed out, unclickable, no chevron), `badge = badge` (the shared
`stringResource(R.string.settings_badge_debug)` value computed once at the top of
`SettingsHubScreen`), and the whole row wrapped in `if (BuildConfig.DEBUG)`. Current placeholder rows:

| Section | Placeholder string resource |
|---|---|
| UI | `settings_ph_appearance` |
| Tunnel | `settings_ph_autoconnect`, `settings_ph_fragmentation`, `settings_ph_mux` |
| Advanced | `settings_ph_xray`, `settings_ph_dns`, `settings_ph_sanitization`, `settings_ph_routing` |
| Diagnostics | `settings_ph_ping` |
| About | `settings_ph_check_update` |

**The entire Advanced section header + all four of its rows are wrapped in one `if (BuildConfig.DEBUG)`**
— unlike the other sections (which are always visible with individual placeholder rows debug-gated
inside them), Advanced has no shipped real row yet, so the section itself disappears in release builds.

Because these are gated on `BuildConfig.DEBUG` (not a runtime flag), a **release build renders none of
them** — `isMinifyEnabled`/R8 aside, the `if (BuildConfig.DEBUG)` branches are dead code the compiler
can constant-fold away in release, so there is no user-visible trace of the placeholders outside debug
builds. This is a maintainer-facing roadmap of *not-yet-built* settings, not a beta/experimental toggle
the app ships to users.

**Do not re-enable (flip to always-visible) or delete a placeholder row without maintainer approval** —
same spirit as the Dormant/Temporarily-Disabled Features convention in `AGENTS.md`: a placeholder marks
an intentional gap, and removing it silently drops the roadmap signal for that setting; wiring it up for
real is fine (see below), but that's a distinct action from deleting the marker.

## Adding a real setting

To promote a placeholder (or add a wholly new setting) to a real row:

1. Build the destination first if it doesn't exist — either a new sub-Activity (following
   `KillSwitchSettingsActivity` / `SplitTunnelSettingsActivity` / `LogsActivity` as the pattern: a
   `LocalizedComponentActivity` with a `Scaffold` + `TopAppBar` + back button) or an in-place dialog
   overlay (following the `showSideloadWarning` pattern for something that doesn't need its own screen).
2. In `SettingsHubScreen`, replace the `if (BuildConfig.DEBUG) SettingsRow(..., enabled = false, badge = badge)`
   placeholder with a real `SettingsRow(title = ..., subtitle = ..., onClick = { open(YourActivity::class.java) })`
   — no `enabled`/`badge` args, so it defaults to enabled with a chevron.
3. If the new destination Activity needs registering, add its `<activity>` entry in
   `AndroidManifest.xml` (see how `LogsActivity`/`AboutActivity` are declared) — note the Self-Review
   ordering note from Task 11's plan: an Activity referenced by the hub must exist (its class importable)
   before the hub can reference it, so build the destination screen first.
4. Add any new user-visible strings to `res/values/strings.xml` **and** `res/values-ru/strings.xml` —
   missing a locale entry fails release lint (`MissingTranslation`).
5. If the leading icon you want isn't one of the four `material-icons-core` symbols already in use
   (`Settings`, `List`, `Info`, `Warning`), do **not** add the `material-icons-extended` dependency —
   add a vector drawable under `res/drawable/` (precedent: `ic_speedometer.xml`) and pass it through
   `painterResource`. `SettingsRow` only has an `ImageVector` parameter today; a row needing a drawable
   icon needs a small `painter: Painter?` overload added to `SettingsRow` rather than forcing a
   drawable-to-`ImageVector` conversion.

## Components

| File | Role |
|---|---|
| [`settings/SettingsHubActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/settings/SettingsHubActivity.kt) | The hub screen: `LocalizedComponentActivity` + `SettingsHubScreen` composable; single scrolling `Column`, five fixed sections, `open(cls)` navigation helper, debug placeholder rows. |
| [`ui/SettingsComponents.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/ui/SettingsComponents.kt) | `SettingsSectionHeader`, `SettingsRow` — the shared primitives (also used by `LogsActivity`). |
| [`settings/AboutActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/settings/AboutActivity.kt) | App name, `BuildConfig.VERSION_NAME`, purpose blurb, GitHub link button (placeholder URL `https://github.com/` — flagged as a known follow-up, not yet the real repo URL), license line. Destination of the About row. |
| [`log/LogsActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/log/LogsActivity.kt) | Destination of the Diagnostics → Logs row; see [`logs-screen.md`](logs-screen.md). |
| `res/values/strings.xml`, `res/values-ru/strings.xml` | All section headers, real-row titles/subtitles, and `settings_ph_*` placeholder strings + `settings_badge_debug` (en + ru; ru mandatory or release lint fails). |

## Known limitations

- **`AboutActivity`'s GitHub link is a placeholder URL** (`https://github.com/`), not the project's real
  repository — flagged explicitly in the implementation plan as a follow-up, not an oversight to
  "helpfully" fix by guessing a URL.
- **No search/filter** across settings — with five sections and a handful of rows this hasn't been
  needed yet; revisit if the hub grows substantially.
- **Debug placeholders carry no persisted state** — they are static, disabled rows; there's nothing to
  migrate when one is promoted to a real setting.

## Testing

- No dedicated JUnit4 suite — `SettingsHubActivity`/`SettingsComponents.kt` are Compose UI with no
  extracted pure decision logic (the `BuildConfig.DEBUG` gating is a compile-time constant, not runtime
  logic to unit test).
- **On-device (manual)**: confirm the hub renders all five sections in a debug build with placeholder
  rows visible (greyed out, "DEBUG" badge, no chevron, unclickable); confirm a release build
  (`assembleRelease`) shows only the real rows (Language, Split tunnel, Kill switch, Logs, Sideload
  warning, About) with the Advanced section absent entirely.
