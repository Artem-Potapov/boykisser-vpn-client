# Profile Actions Island Menu

Maintainer reference for the per-profile actions menu: a centered Material3 `BasicAlertDialog` island
that appears on a long-press of any `ProfileRow` on the main screen. It replaced the former
`ModalBottomSheet` with a more compact, modal-safe surface.

## Trigger and wiring

`MainScreen` in
[`MainActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/MainActivity.kt) holds a
single `var menuProfile by remember { mutableStateOf<Profile?>(null) }`. Every `ProfileRow` (both
ungrouped and grouped) passes `onLongPress = { menuProfile = profile }`. When `menuProfile` is non-null
the composable renders:

```kotlin
if (menuProfile != null) {
    val profile = menuProfile!!
    val shareLink = remember(profile.id, profile.config) {
        ProfileShareLink.fromStoredConfig(profile.config)
    }
    ProfileActionsDialog(
        profile = profile,
        isConnectedProfile = isActive(profile, activeId, state),
        canConnect = canConnect(state),
        shareLink = shareLink,
        ...
        onDismiss = { menuProfile = null }
    )
}
```

`shareLink` is recomputed whenever the profile's `id` or stored `config` changes; the rest of the
menu state is purely derived from the current VPN state at render time.

## Layout

[`ProfileActionsDialog`](../../app/src/main/java/com/justme/xtls_core_proxy/ProfileActionsDialog.kt)
is a `BasicAlertDialog` wrapping a `Surface(shape = extraLarge, tonalElevation = 6.dp)`. The dialog is
vertically scrollable (`verticalScroll`) and opens centered on screen. The profile's display name
appears as a `titleMedium` header at the top (max two lines, ellipsized).

## Actions

Actions are rendered as `ProfileActionRow` composables (icon + label, `bodyLarge` text, full-width
tap target with 24 dp horizontal / 14 dp vertical padding). A `HorizontalDivider` separates the
non-destructive rows from Delete.

| Order | Label | Icon | Condition | Enabled |
|---|---|---|---|---|
| 1 | Disconnect | `ic_power_off` (drawable) | `isConnectedProfile == true` | always |
| 1 | Connect | `Icons.Filled.PlayArrow` | `isConnectedProfile == false` | `canConnect` only |
| 2 | Connect to fastest | `ic_bolt` (drawable) | always shown | `canConnect` only |
| 3 | Ping test | `ic_speedometer` (drawable, reused from ping-test group header) | always shown | always |
| 4 | Edit | `Icons.Filled.Edit` | always shown | always |
| 5 | Copy link | `ic_link` (drawable) | `shareLink != null` only | always |
| 6 | Copy config | `ic_content_copy` (drawable) | always shown | always |
| — | *(divider)* | | | |
| 7 | Delete | `Icons.Filled.Delete` | always shown | always |

Connect/Disconnect occupies the same row slot — exactly one variant is shown, never both. The Connect
row is greyed out (alpha 0.38) and non-clickable when `canConnect` is false — i.e. when
`VpnConnectionState` is `CONNECTED`, `CONNECTING`, `PAUSED`, or `BLACKHOLED` (another profile may be
active, a connection is in progress, the tunnel is paused, or the tunnel is deliberately blackholed by
auto-failover).

Every action callback in `MainScreen` sets `menuProfile = null` after running, so the dialog always
dismisses once an action is chosen (including Copy link / Copy config).

Delete uses `MaterialTheme.colorScheme.error` for both the icon tint and label color.

`PlayArrow`, `Edit`, and `Delete` come from `material-icons-core` (`Icons.Filled.*`). The remaining
five icons — `ic_power_off`, `ic_bolt`, `ic_speedometer`, `ic_link`, `ic_content_copy` — are custom
vector drawables under `res/drawable/`.

### Connect to fastest

"Connect to fastest" probes every profile in the long-pressed profile's current group — the manual
("My profiles") partition, or the matching subscription group — via the existing
[ping-test](ping-test.md) `PingCoordinator`/`pingStates` machinery
(`state/VpnViewModel.poolForProfile` resolves the pool from a single `Profile` back to its group),
then connects to whichever profile answered fastest. Unlike every other row, it does **not**
dismiss the dialog by itself finishing synchronously — the probe run continues after the dialog
closes (`menuProfile = null` still fires immediately on tap, matching the dismissal idiom, but the
underlying `VpnViewModel.connectFastest` coroutine is not tied to the dialog's lifetime).

Because the probe run can take up to `timeout * ceil(n / concurrency)` (several minutes at the
ping-test preference bounds), `MainScreen` shows a dedicated progress row (spinner + "Finding the
fastest server…" + a Cancel button) whenever `VpnViewModel.connectFastestActive` is true, and each
pooled server's row/group-header spinner lights up for free since the run marks pool ids
`PingState.Testing` through the same `pingStates` map a manual ping test uses. Cancelling (via that
button, or `VpnViewModel.cancelConnectFastest()`) resets any pool id still on `Testing` back to
`Idle` rather than leaving it spinning forever — see `VpnViewModel.connectFastest`'s doc for why that
reset is necessary (`PingCoordinator.runGroup` does not itself emit a terminal state for an id whose
probe was cancelled mid-flight).

The winning profile is surfaced as `VpnViewModel.fastestWinnerId` (ViewModel state) rather than the
ViewModel calling `connect()` directly: every other Connect action in this app is gated by
`MainActivity`'s permission-checked flow (notification permission, then `VpnService.prepare()`
consent) before ever calling `VpnViewModel.connect`, and the ViewModel has no access to that
Activity-owned `ActivityResultLauncher` machinery. `MainScreen` observes `fastestWinnerId` and, when
it becomes non-null, calls the same `onConnect` callback every other Connect row uses, then consumes
it via `consumeFastestWinner()`. Surfacing the winner as state (not a callback captured by the
long-running coroutine) also means an Activity recreation (rotation) mid-probe cannot fire the
connect flow against a destroyed Activity — the observer re-subscribes fresh on every recomposition.

The row itself is disabled (not hidden) whenever `canConnect` is false, mirroring the Connect row
directly above it: `XrayVpnService.startVpn` no-ops with "VPN already running" for a start dispatched
while a session is already live, so the multi-minute probe must never be allowed to run only to
discover that at the very end.

## Copy link

"Copy link" is shown only when `ProfileShareLink.fromStoredConfig(profile.config)` returns a
non-null string. That call is made once per `menuProfile` population and memoized via `remember`.

[`ProfileShareLink`](../../app/src/main/java/com/justme/xtls_core_proxy/config/ProfileShareLink.kt)
walks the stored JSON's `outbounds` array for the first `vless` or `hysteria` outbound, then delegates:

- `vless` → `ProfileConfigCodec.toVlessUri(ProfileConfigCodec.parseVlessProfileFromJson(config))`
  → a `vless://` link.
- `hysteria` (Xray's internal protocol name for Hysteria2 v2) →
  `Hysteria2ConfigCodec.toShareLink(Hysteria2ConfigCodec.parseProfileFromJson(config))` → a `hy2://`
  link.

Any exception or an outbound type with no URI form yields `null`, and the row is hidden. This covers:
raw JSON configs with freedom/blackhole-only outbounds, malformed JSON, and any future protocol with
no share-link grammar.

### Accepted lossiness

**Share links are lossy by design.** Not every field in a stored Hysteria2 Xray config has a URI
grammar equivalent. [`toShareLink`](../../app/src/main/java/com/justme/xtls_core_proxy/config/Hysteria2ConfigCodec.kt)
does not emit standalone query params for `congestion`, `uploadBandwidth` (`brutalUp`),
`downloadBandwidth` (`brutalDown`), or `udpHopInterval` — those structured fields are omitted from
the URI surface. They may still survive inside the `fm` query parameter when the stored config's
`streamSettings.finalmask` object already carries them (the blob is passed through verbatim). For
VLESS, any extension fields beyond what the standard `vless://` grammar covers may similarly be
omitted.

**"Copy config" is the lossless path.** Agents and maintainers should not treat share-link
reconstruction as a bug to fix by expanding the URI grammar; the lossiness is intentional.
See [`Hysteria2ConfigCodec.toShareLink`](../../app/src/main/java/com/justme/xtls_core_proxy/config/Hysteria2ConfigCodec.kt)
and its doc comment for the definitive list of what is and is not expressed in the link.

## Copy config

"Copy config" always copies `profile.config` through
[`JsonFormatter.formatJsonIfValid`](../../app/src/main/java/com/justme/xtls_core_proxy/config/JsonFormatter.kt):
valid JSON is pretty-printed; invalid/non-JSON text is copied as-is.

## Clipboard sensitivity and toasts

Both "Copy link" and "Copy config" go through `copyToClipboardMarkedSensitive` (private top-level
function in `MainActivity.kt`). On API 33+ (Android 13, `Build.VERSION_CODES.TIRAMISU`) this sets
`ClipDescription.EXTRA_IS_SENSITIVE = true` on the `ClipData`, which suppresses the system paste
preview. On older APIs the clip is written normally. If `ClipboardManager` is unavailable the call
is a no-op.

On API 33+ the app's own "Link copied" / "Config copied" toasts are also skipped — Android shows its
own clipboard-copy confirmation, and showing both would double-confirm. Below API 33 a
`Toast.LENGTH_SHORT` is shown after each successful copy.

This matches `LogRepository`'s redaction posture: stored configs contain UUIDs, REALITY public keys,
and `shortId` values that should not be surfaced in system UI.

## Scope

Only the main-screen `ProfileRow` composables trigger the menu. Group-header long-press (if any) and
`SubscriptionsActivity` are unchanged. The menu is not accessible from the QS tile or from any
notification action.

## Files

| File | Role |
|---|---|
| [`ProfileActionsDialog.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/ProfileActionsDialog.kt) | Composable — layout and all action rows |
| [`config/ProfileShareLink.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/config/ProfileShareLink.kt) | Object — reconstructs share links from stored JSON |
| [`MainActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/MainActivity.kt) | Wiring — `menuProfile` state, `copyToClipboardMarkedSensitive`, toast messages |
| [`config/ProfileConfigCodec.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/config/ProfileConfigCodec.kt) | VLESS URI reconstruction (`toVlessUri`) |
| [`config/Hysteria2ConfigCodec.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/config/Hysteria2ConfigCodec.kt) | Hysteria2 link reconstruction (`toShareLink`) |
| [`failover/FastestPick.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FastestPick.kt) | Connect-to-fastest pure logic — `pickFastest` (lowest-latency successful candidate) and `clearStaleTesting` (post-cancel `pingStates` cleanup) |
| [`state/VpnViewModel.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/VpnViewModel.kt) | `poolForProfile` (group resolution), `connectFastest`/`cancelConnectFastest`/`connectFastestActive`/`fastestWinnerId`/`consumeFastestWinner` |

## Testing

JVM unit tests (`:app:testDebugUnitTest`):

| Test class | What it covers |
|---|---|
| [`ProfileShareLinkTest`](../../app/src/test/java/com/justme/xtls_core_proxy/ProfileShareLinkTest.kt) | `fromStoredConfig`: VLESS JSON → `vless://`, Hysteria2 JSON → `hy2://`, freedom-only → `null`, malformed JSON → `null` |
| [`Hysteria2ConfigCodecTest`](../../app/src/test/java/com/justme/xtls_core_proxy/Hysteria2ConfigCodecTest.kt) | `toShareLink` round-trips: common fields (sni, alpn, insecure, salamander), port-hopping + salamander, finalmask blob carried verbatim |
| [`FastestPickTest`](../../app/src/test/java/com/justme/xtls_core_proxy/failover/FastestPickTest.kt) | `pickFastest`: lowest latency wins, ignores `Unavailable`/`Testing`, null when nothing succeeded, ignores results for ids outside the candidate list |
| [`ClearStaleTestingTest`](../../app/src/test/java/com/justme/xtls_core_proxy/failover/ClearStaleTestingTest.kt) | `clearStaleTesting`: resets in-pool `Testing` ids to `Idle`, leaves resolved ids untouched, never touches `Testing` ids outside the pool |
| [`PoolForProfileTest`](../../app/src/test/java/com/justme/xtls_core_proxy/state/PoolForProfileTest.kt) | `poolForProfile`: manual profile → whole manual partition, subscription profile → its `SubGroup`, orphaned subscription id → falls back to `listOf(profile)` |

`ProfileActionsDialog` itself has no dedicated unit test — it is a pure Compose rendering component
with no business logic of its own.
