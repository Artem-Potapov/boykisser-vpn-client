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
| 2 | Ping test | `ic_speedometer` (drawable, reused from ping-test group header) | always shown | always |
| 3 | Edit | `Icons.Filled.Edit` | always shown | always |
| 4 | Copy link | `ic_link` (drawable) | `shareLink != null` only | always |
| 5 | Copy config (JSON) | `ic_content_copy` (drawable) | always shown | always |
| — | *(divider)* | | | |
| 6 | Delete | `Icons.Filled.Delete` | always shown | always |

Connect/Disconnect occupies the same row slot — exactly one variant is shown, never both. The Connect
row is greyed out (alpha 0.38) and non-clickable when `canConnect` is false (the VPN is already
active for another profile, or a connection is in progress).

Delete uses `MaterialTheme.colorScheme.error` for both the icon tint and label color.

`PlayArrow`, `Edit`, and `Delete` come from `material-icons-core` (`Icons.Filled.*`). The remaining
four icons — `ic_power_off`, `ic_speedometer`, `ic_link`, `ic_content_copy` — are custom vector
drawables under `res/drawable/`.

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
grammar equivalent. Fields dropped from a `hy2://` link include `congestion`, `uploadBandwidth`
(`brutalUp`), `downloadBandwidth` (`brutalDown`), and `udpHopInterval`. The `finalmask` blob IS
carried verbatim as the `fm` query parameter, so QUIC params embedded in the provider-supplied
finalmask survive round-tripping. For VLESS, any extension fields beyond what the standard `vless://`
grammar covers may similarly be omitted.

**"Copy config (JSON)" is the lossless path.** Agents and maintainers should not treat share-link
reconstruction as a bug to fix by expanding the URI grammar; the lossiness is intentional.
See [`Hysteria2ConfigCodec.toShareLink`](../../app/src/main/java/com/justme/xtls_core_proxy/config/Hysteria2ConfigCodec.kt)
and its doc comment for the definitive list of what is and is not expressed in the link.

## Clipboard sensitivity

Both "Copy link" and "Copy config (JSON)" go through `copyToClipboardMarkedSensitive` (private
top-level function in `MainActivity.kt`). On API 33+ (Android 13, `Build.VERSION_CODES.TIRAMISU`)
this sets `ClipDescription.EXTRA_IS_SENSITIVE = true` on the `ClipData`, which suppresses the
system paste preview. On older APIs the clip is written normally. If `ClipboardManager` is unavailable
the call is a no-op.

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

## Testing

JVM unit tests (`:app:testDebugUnitTest`):

| Test class | What it covers |
|---|---|
| [`ProfileShareLinkTest`](../../app/src/test/java/com/justme/xtls_core_proxy/ProfileShareLinkTest.kt) | `fromStoredConfig`: VLESS JSON → `vless://`, Hysteria2 JSON → `hy2://`, freedom-only → `null`, malformed JSON → `null` |
| [`Hysteria2ConfigCodecTest`](../../app/src/test/java/com/justme/xtls_core_proxy/Hysteria2ConfigCodecTest.kt) | `toShareLink` round-trips: common fields (sni, alpn, insecure, salamander), port-hopping + salamander, finalmask blob carried verbatim |

`ProfileActionsDialog` itself has no dedicated unit test — it is a pure Compose rendering component
with no business logic of its own.
