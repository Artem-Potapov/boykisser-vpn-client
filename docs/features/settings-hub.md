# Settings Hub: Sectioned Structure and Promoted Settings

Maintainer reference for `SettingsHubActivity`: the sectioned single-scroll settings screen, the
reusable `SettingsSectionHeader`/`SettingsRow` components it (and `LogsActivity`) are built from, the
seven promoted settings destinations, and the remaining debug-placeholder convention.

## Why this exists

Before this feature, settings were scattered across ad hoc entry points with no single place a user (or
a maintainer wiring in a new toggle) could go to find "all settings." `SettingsHubActivity` is that
single entry point — the one screen `MainActivity` links to — organized into fixed sections so future
settings have an obvious home instead of accreting onto whichever screen was open when they were added.

## Structure: one screen, six sections, single scroll

[`settings/SettingsHubActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/settings/SettingsHubActivity.kt)
renders a single `Column` inside `verticalScroll(rememberScrollState())` — not a `LazyColumn`, not
per-section sub-screens. Sections appear in this fixed order:

| Section | String resource | Real rows today |
|---|---|---|
| UI | `settings_section_ui` | Language (→ `LanguageSettingsActivity`), App appearance (→ `AppearanceSettingsActivity`) |
| Tunnel | `settings_section_tunnel` | Split tunnel, Kill switch, **Auto-failover**, Auto-connect on boot, Fragmentation, Mux.Cool |
| Privacy | `settings_section_privacy` | Device identity (HWID) (→ `HwidSettingsActivity`; [hwid-device-identity.md](hwid-device-identity.md)) |
| Advanced | `settings_section_advanced` | XRAY, DNS, Config sanitization, Routing rules |
| Diagnostics | `settings_section_diagnostics` | Logs, Ping test |
| About | `settings_section_about` | Sideload warning (opens `SideloadWarningDialog` in-place), About (→ `AboutActivity`) |

Each real, navigable row calls a local `open(cls: Class<*>)` helper
(`context.startActivity(Intent(context, cls))`) except the two in-place-dialog rows — the sideload row
(`showSideloadWarning = true` → `SideloadWarningDialog`) and the Auto-connect on boot row
(`showAutoConnectInfo = true` → `AutoConnectInfoDialog`, see [`auto-connect-boot.md`](auto-connect-boot.md))
— which show an overlay rather than navigating away.

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

## The eight promoted rows

Plans 1–3 promoted all former Tunnel/Advanced/Diagnostics placeholders to real destinations; Privacy
adds Device identity, and the auto-failover branch adds Auto-failover:

- Tunnel → Auto-failover (`failover.FailoverSettingsActivity`; [auto-failover.md](auto-failover.md)) —
  sits directly after Kill switch. Not a promoted placeholder: a wholly new row.
- Tunnel → Mux.Cool (`MuxSettingsActivity`; [mux.md](mux.md))
- Privacy → Device identity (HWID) (`HwidSettingsActivity`; [hwid-device-identity.md](hwid-device-identity.md))
- Advanced → XRAY (`XraySettingsActivity`; [xray-settings.md](xray-settings.md))
- Advanced → DNS (`DnsSettingsActivity`; [dns.md](dns.md))
- Advanced → Config sanitization (`ConfigSanitizationActivity`;
  [config-sanitization.md](config-sanitization.md))
- Advanced → Routing rules (`RoutingSettingsActivity`; [routing-rules.md](routing-rules.md))
- Diagnostics → Ping test (`PingTestSettingsActivity`; [ping-test.md](ping-test.md))

These rows are always enabled and visible in both debug and release. Because Advanced now contains
four real rows, its header and contents are no longer wrapped in `BuildConfig.DEBUG`; the complete
Advanced section ships in release. Privacy is likewise a real section (not a debug placeholder).

## Autosave: the overlay screens' house style

The six global-overlay destinations reached from this hub — DNS, Mux, Routing, XRAY, Fragmentation,
and Ping test — **autosave**. There is no Save button on any of them: each screen loads its
preferences once, edits a local draft, and persists on change, and the `‹` back arrow is plain
up-navigation with no unsaved-edits concern (there is nothing unsaved to lose). The persisted overlay
is still captured into the session's `TuningSettings` snapshot only at the next full connection, so an
edit applies on reconnect, not mid-session (see each feature doc).

Device identity (HWID) under Privacy follows the same **autosave** control model (persist on every
change, no Save button) but is prefs-only — it is not part of `TuningSettings` and takes effect on
the next subscription refresh. See [hwid-device-identity.md](hwid-device-identity.md).

Auto-failover under Tunnel also autosaves, and is a third variety again: it is **not** part of
`TuningSettings` and it is **not** deferred to the next connection — `XrayVpnService` observes
`FailoverPreferences.state` for the live session, so the enable toggle takes effect immediately and a
timing change rebuilds the running health monitor. See [auto-failover.md](auto-failover.md).

The per-control commit model is deliberately typed:

- **Enums, toggles, and dropdowns persist immediately** on selection — resolver choice, IPv6/sniffing
  switches, routing mode/toggles, domain strategy, fragmentation preset.
- **DNS custom URL and pinned IP persist on every change** — there is no validity gate on the write.
  `applyDns` no-ops on a blank/invalid **URL**, leaving the canonical secure resolver in place. It does
  **not** do the same for the **pin**: a valid hostname URL with a blank pin persists and installs that
  URL as the unscoped resolver with no `dns.hosts` entry (the scoped proxy bootstrap is still retained,
  so the proxy's own hostname keeps resolving). That is a deliberate decision, not an oversight — read
  [dns.md](dns.md) ("Do not describe corrupt pin state as an unconditional fail-closed no-op") and
  [dns-leak-enforcement.md](dns-leak-enforcement.md) before changing it.
- **Bounded numerics persist only when the value validates** (Mux concurrency/xudp, XRAY MTU, Ping
  timeout/concurrency, Fragmentation packets/length/interval). An invalid entry **holds the last-good
  persisted value** so an in-progress number can't brick the next connect.

  **The hold is per-control, not per-screen.** An invalid numeric must never block the write of the
  *other* controls in the same settings tuple. XRAY and Ping previously did skip the whole write, so
  an unparsed MTU silently discarded a flip of the IPv6 switch — the screen showed IPv6 off while
  prefs kept it on, and the next connect emitted no `::/0 → block` rule: a privacy control failing
  fail-open. Each `persist()` now substitutes the last-good persisted value for just the invalid
  field. Two rules when touching one of these: re-read the fallback from **prefs**, not the
  screen-open `initial` (otherwise a valid edit made earlier in the same session gets reverted), and
  re-derive validity **inline** rather than reading the composition-time `xxxValid` val, which is a
  stale pre-keystroke read. `MuxSettingsActivity.persist()` is the reference implementation.

- **A CROSS-FIELD bound must be DERIVED from the same expression the repository's `coerce()` uses,
  never restated.** Auto-failover is the cautionary example. Its rule is
  `probeTimeout ≤ probeInterval − TIMEOUT_HEADROOM_MS`; the screen's accept test was written as the
  looser `timeout < interval`, so at interval 10 000 a typed 9 500 showed **no error**, was accepted,
  and was then silently rewritten to 9 000 by `save()` — a ~999 ms silent-rewrite window at every
  interval value, while the screen's own error string already stated the correct rule. Worse, ten unit
  tests written against the restated rule *encoded* the defect instead of catching it (one asserted
  19 500 accepted at a fallback interval of 20 000, whose real ceiling is 19 000). Both the persist
  decision and the display validity now compute
  `(effectiveInterval − TIMEOUT_HEADROOM_MS).coerceAtLeast(TIMEOUT_MIN)`, so there is **one** rule and
  `coerce()` is the final backstop rather than the enforcement point. Note the ceiling must be derived
  from the **effective** (post-fallback) interval, so that editing either field re-validates the pair.
  Auto-failover additionally keeps a `lastPersisted` state re-read via `load()` **after** each save, so
  display validity and the persisted value share one source of truth.

## The remaining `BuildConfig.DEBUG` placeholder convention

Rows that stake out not-yet-implemented settings follow this shape:

```kotlin
if (BuildConfig.DEBUG) SettingsRow(
    title = stringResource(R.string.settings_ph_check_update),
    enabled = false, badge = badge
)
```

i.e. `enabled = false` (greyed out, unclickable, no chevron), `badge = badge` (the shared
`stringResource(R.string.settings_badge_debug)` value computed once at the top of
`SettingsHubScreen`), and the whole row wrapped in `if (BuildConfig.DEBUG)`. The only remaining
placeholder is About → `settings_ph_check_update`.

Earlier promoted rows also include App appearance, Auto-connect, and Fragmentation. Because the sole
remaining placeholder is gated on `BuildConfig.DEBUG`, release builds omit only Check for updates;
all six section headers and every real row remain visible.

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
| [`settings/SettingsHubActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/settings/SettingsHubActivity.kt) | The hub screen: six fixed sections, eight promoted rows, `open(cls)` navigation helper, and the one remaining debug placeholder. |
| [`ui/SettingsComponents.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/ui/SettingsComponents.kt) | `SettingsSectionHeader`, `SettingsRow` — the shared primitives (also used by `LogsActivity`). |
| [`settings/AboutActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/settings/AboutActivity.kt) | App name, `BuildConfig.VERSION_NAME`, purpose blurb, GitHub link button (placeholder URL `https://github.com/` — flagged as a known follow-up, not yet the real repo URL), license line. Destination of the About row. |
| [`log/LogsActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/log/LogsActivity.kt) | Destination of the Diagnostics → Logs row; see [`logs-screen.md`](logs-screen.md). |
| `res/values/strings.xml`, `res/values-ru/strings.xml` | All section headers, real-row titles/subtitles, and `settings_ph_*` placeholder strings + `settings_badge_debug` (en + ru; ru mandatory or release lint fails). |

## Known limitations

- **`AboutActivity`'s GitHub link is a placeholder URL** (`https://github.com/`), not the project's real
  repository — flagged explicitly in the implementation plan as a follow-up, not an oversight to
  "helpfully" fix by guessing a URL.
- **No search/filter** across settings — with six sections and a handful of rows this hasn't been
  needed yet; revisit if the hub grows substantially.
- **The remaining Check for updates placeholder carries no persisted state** — it is a static,
  disabled debug row.

## Testing

- No dedicated JUnit4 suite — `SettingsHubActivity`/`SettingsComponents.kt` are Compose UI with no
  extracted pure decision logic (the `BuildConfig.DEBUG` gating is a compile-time constant, not runtime
  logic to unit test).
- **On-device (manual)**: confirm debug shows the disabled Check for updates row with a DEBUG badge;
  confirm release omits that row but shows all six sections, including Privacy and Advanced, and opens
  all eight promoted destinations. The Tunnel → Auto-failover chain is **verified on device**
  (SM-S918B): the row renders beside Kill switch and lands on `.failover.FailoverSettingsActivity`.
  A direct `am start` of that Activity is correctly **refused** ("not exported") — `exported="false"`
  matches every sibling settings screen; do not "fix" it.
