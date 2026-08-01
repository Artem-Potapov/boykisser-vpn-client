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

"Connect to fastest" probes the long-pressed profile's pool — resolved by
[`failover/FailoverPoolResolver.resolve`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FailoverPoolResolver.kt)
(the manual "My profiles" partition, or the matching subscription's profiles pulled straight from
the DAO) — via the existing [ping-test](ping-test.md) `PingCoordinator` machinery, then connects to
whichever profile answered fastest. Pool resolution deliberately reuses `FailoverPoolResolver`
rather than deriving a separate view-based pool: that object's own KDoc calls it "the single place
that changes when user-curated pools land," and a second, independently-derived pool here would
silently diverge from what auto-failover itself rotates through once curated pools exist.

The row taps dismiss the dialog immediately (`menuProfile = null`, matching every other row's
idiom), but unlike every other row the underlying work does **not** finish synchronously with that
tap — `VpnViewModel.connectFastest` starts a probe run that keeps going after the dialog has already
closed.

Because that run can take up to `timeout * ceil(n / concurrency)` (several minutes at the ping-test
preference bounds), `MainScreen` shows a dedicated progress row (spinner + "Finding the fastest
server…" + a Cancel button) whenever `VpnViewModel.connectFastestActive` is true. Each pooled
server's row/group-header spinner lights up too, for free — but only for the *fresh* ids this run
actually admits: `PingCoordinator.runGroup` skips (does not re-mark) any id already in flight from
another active run (auto-ping, a manual ping test, or an overlapping connect-fastest pool), so a
pool id that was already `Testing` before this run started may show no visible change at all.
Cancelling (via that button, or `VpnViewModel.cancelConnectFastest()`) resets any of *this run's*
pool ids still on `Testing` back to `Idle` rather than leaving them spinning forever — see
`failover/FastestConnectRunner.start`'s doc for why that reset is necessary
(`PingCoordinator.runGroup` does not itself emit a terminal state for an id whose probe was
cancelled mid-flight).

All of the sequencing above — job replacement, the delivery-time re-gate below, cancellation
cleanup, and the busy/no-response distinction — lives in
[`failover/FastestConnectRunner`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FastestConnectRunner.kt),
a framework-free class `VpnViewModel` owns one instance of. It is unit-tested directly (see Testing
below) the same way `PingCoordinatorTest` exercises `PingCoordinator` itself, without needing
`AndroidViewModel`/Room/`Context`.

**A discarded run always tells the user why**, never silently: if no pool id resolved to a
successful probe, `LogRepository.emitError` fires one of two distinct messages —
`failover_connect_fastest_no_response_error` if every id was probed fresh and none answered, or
`failover_connect_fastest_busy_error` if at least one pool id was already `Testing` (owned by
another active run) before this run started, since in that case "no server responded" would be
misleading — the server may be fine, this run just never got a fresh read on it.

**The winner is re-gated at delivery time, not only at the tap that started the run** (Task 10
review Important 1). A run spanning minutes means the connection state can leave the connectable
set (`CONNECTED`/`CONNECTING`/`PAUSED`/`BLACKHOLED`) while it is in flight — another Connect action,
the QS tile, or auto-failover can all cause this. `FastestConnectRunner` re-checks
`state/VpnViewModel.canConnect` (a shared top-level function, not duplicated) against the fresh
connection state immediately before ever setting the winner; if it now fails, the winner is
discarded and `failover_connect_fastest_state_changed_error` is reported instead. **The row's own
`enabled = canConnect` (checked once, at tap time) is a cheap, obvious no-op-prevention gate only —
it does NOT by itself guarantee correctness of a winner minutes later.** Without the delivery-time
re-check, a stale winner firing into a no-longer-connectable state would have `connect()` silently
keep the OLD tunnel up (`XrayVpnService.startVpn`'s "VPN already running" no-op) while
unconditionally overwriting `ActiveProfileRepository`'s active profile id to the NEW one — the UI
would then report the WRONG server as connected while traffic kept flowing through the old one.

The winning profile is surfaced as `VpnViewModel.fastestWinnerId` (ViewModel state) rather than the
ViewModel calling `connect()` directly: every other Connect action in this app is gated by
`MainActivity`'s permission-checked flow (notification permission, then `VpnService.prepare()`
consent) before ever calling `VpnViewModel.connect`, and the ViewModel has no access to that
Activity-owned `ActivityResultLauncher` machinery. `MainScreen` observes `fastestWinnerId` and, when
it becomes non-null, calls the same `onConnect` callback every other Connect row uses, then consumes
it via `consumeFastestWinner()`. Surfacing the winner as state (not a callback captured by the
long-running coroutine) also means an Activity recreation (rotation) mid-probe cannot fire the
connect flow against a destroyed Activity — the observer re-subscribes fresh on every recomposition.

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
| [`failover/FastestConnectRunner.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FastestConnectRunner.kt) | Framework-free orchestrator — job replacement/cancellation, the delivery-time `canConnect` re-gate, busy-vs-no-response messaging (`FastestConnectOutcome`) |
| [`failover/FailoverPoolResolver.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FailoverPoolResolver.kt) | Single source of truth for "the pool a profile belongs to" — shared with auto-failover, `resolve(dao, profile)` |
| [`state/VpnViewModel.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/VpnViewModel.kt) | `canConnect` (shared connectability check, also used by `MainActivity`), owns the one `FastestConnectRunner` instance, `connectFastest`/`cancelConnectFastest`/`connectFastestActive`/`fastestWinnerId`/`consumeFastestWinner` delegate to it |

## Testing

JVM unit tests (`:app:testDebugUnitTest`):

| Test class | What it covers |
|---|---|
| [`ProfileShareLinkTest`](../../app/src/test/java/com/justme/xtls_core_proxy/ProfileShareLinkTest.kt) | `fromStoredConfig`: VLESS JSON → `vless://`, Hysteria2 JSON → `hy2://`, freedom-only → `null`, malformed JSON → `null` |
| [`Hysteria2ConfigCodecTest`](../../app/src/test/java/com/justme/xtls_core_proxy/Hysteria2ConfigCodecTest.kt) | `toShareLink` round-trips: common fields (sni, alpn, insecure, salamander), port-hopping + salamander, finalmask blob carried verbatim |
| [`FastestPickTest`](../../app/src/test/java/com/justme/xtls_core_proxy/failover/FastestPickTest.kt) | `pickFastest`: lowest latency wins, ignores `Unavailable`/`Testing`, null when nothing succeeded, ignores results for ids outside the candidate list |
| [`ClearStaleTestingTest`](../../app/src/test/java/com/justme/xtls_core_proxy/failover/ClearStaleTestingTest.kt) | `clearStaleTesting`: resets in-pool `Testing` ids to `Idle`, leaves resolved ids untouched, never touches `Testing` ids outside the pool |
| [`FastestConnectRunnerTest`](../../app/src/test/java/com/justme/xtls_core_proxy/failover/FastestConnectRunnerTest.kt) | `FastestConnectRunner` sequencing, driven with `kotlinx-coroutines-test` against a real (not faked) `PingCoordinator`: a superseding run's `active` flag survives the superseded run's own cancellation-driven cleanup; `cancel()` resets in-pool `Testing` ids to `Idle`; a winner found after `canConnect` turns false is discarded and reported `STATE_CHANGED`, never delivered; a winner found while still connectable is delivered; no winner with nothing pre-existing in flight reports `NO_RESPONSE`; no winner with a pool id already `Testing` beforehand reports `BUSY` instead |

`ProfileActionsDialog` itself has no dedicated unit test — it is a pure Compose rendering component
with no business logic of its own.
