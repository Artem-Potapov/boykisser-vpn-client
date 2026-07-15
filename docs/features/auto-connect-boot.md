# Auto-Connect on Boot: Stateless Explainer over Android's Always-on VPN

Maintainer reference for the "Auto-connect on boot" settings row: what it actually does (nothing, by
design), why there is no boot receiver, and where the real behavior lives (Android's OS-level Always-on
VPN feature).

## Why this exists — and why it's stateless

Users want the VPN to survive a reboot or an app/process kill without re-opening the app. The
straightforward implementation would be a `BOOT_COMPLETED` `BroadcastReceiver` that starts
`XrayVpnService`, plus an app-side "auto-connect" toggle and its own persisted preference. This feature
takes neither path. Instead it's a hub row that **explains and deep-links to Android's own Always-on
VPN system setting** — the OS-level feature that already restarts a `VpnService` on boot and can
enforce a lockdown ("Block connections without VPN"). The app keeps **no local state** for this
feature: no preference key, no boot receiver, no toggle. Android's Always-on VPN setting (whether this
app is the chosen always-on VPN, and whether lockdown is enabled) is the single source of truth.

This is possible because `XrayVpnService` is already wired for it without any extra code:
`AndroidManifest.xml` declares it as a `specialUse` foreground service with the `android.net.VpnService`
intent-filter, which is **all** the always-on/boot mechanism needs to be able to restart it — see
[`failclosed-startup.md`](failclosed-startup.md)'s Components note ("No new persisted state, no
manifest change, no `BOOT_COMPLETED` receiver — the service is already a `specialUse` FGS with the
`android.net.VpnService` intent-filter, which is all the always-on/boot mechanism needs") and its Error
handling note on the `START_REDELIVER_INTENT` caveat: with Always-on VPN enabled, the system can
restart the service via the always-on mechanism itself (a null-intent start), independent of 2A's own
crash-redelivery path.

## What the feature actually is

[`settings/AutoConnectInfoDialog.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/settings/AutoConnectInfoDialog.kt)
is a stateless `@Composable` — no `ViewModel`, no repository, no persisted flag:

```kotlin
@Composable
fun AutoConnectInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        title = { Text(stringResource(R.string.autoconnect_dialog_title)) },
        text = { Text(stringResource(R.string.autoconnect_dialog_body)) },
        confirmButton = { TextButton(onClick = { /* deep-link, see below */ }) { ... } },
        dismissButton = { TextButton(onClick = onDismiss) { ... } },
    )
}
```

The body copy (`autoconnect_dialog_body`) explains Always-on VPN and lockdown in plain language and
tells the user to enable it themselves in system settings — the dialog is purely informational plus a
launcher, not a control surface.

**The deep-link, with a fallback:**

```kotlin
val primary = Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
if (primary.resolveActivity(context.packageManager) != null) {
    context.startActivity(primary)
} else {
    context.startActivity(fallback)
}
onDismiss()
```

`Settings.ACTION_VPN_SETTINGS` opens the system VPN settings list directly (where Always-on VPN and
lockdown live, per-app, on stock Android). It's probed with `resolveActivity` first because some OEM
skins don't expose that action; when it doesn't resolve, the dialog falls back to
`Settings.ACTION_SETTINGS` (the top-level Settings app) rather than doing nothing. Either way,
`onDismiss()` runs immediately after launching the intent, closing the dialog.

## The hub row

[`settings/SettingsHubActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/settings/SettingsHubActivity.kt)'s
Tunnel section renders the row and owns exactly one bit of local UI state — whether the dialog is
showing:

```kotlin
var showAutoConnectInfo by remember { mutableStateOf(false) }
...
SettingsRow(
    title = stringResource(R.string.settings_autoconnect_title),
    subtitle = stringResource(R.string.settings_autoconnect_subtitle),
    onClick = { showAutoConnectInfo = true },
)
...
if (showAutoConnectInfo) {
    AutoConnectInfoDialog(onDismiss = { showAutoConnectInfo = false })
}
```

This is the same in-place-overlay pattern `showSideloadWarning` / `SideloadWarningDialog` uses, **not**
the `open(cls)` navigate-to-a-new-`Activity` pattern the Appearance/Fragmentation rows use — there is no
`AutoConnectSettingsActivity` and no manifest entry, because there is nothing to navigate to besides the
dialog itself.

## Components

| File | Role |
|---|---|
| [`settings/AutoConnectInfoDialog.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/settings/AutoConnectInfoDialog.kt) | Stateless explainer `AlertDialog`; deep-links to `Settings.ACTION_VPN_SETTINGS` with an `ACTION_SETTINGS` fallback. |
| [`settings/SettingsHubActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/settings/SettingsHubActivity.kt) | Hub row (Tunnel section) + the `showAutoConnectInfo` overlay state that shows/hides the dialog. |
| `res/values/strings.xml`, `res/values-ru/strings.xml` | `settings_autoconnect_title`/`_subtitle`, `autoconnect_dialog_title`/`_body`, `autoconnect_open_settings`, `autoconnect_dismiss` (en + ru). |

## Known limitations

- **No in-app indication of current Always-on/lockdown state.** The hub row and dialog are static —
  they don't read back whether this app is currently the OS's chosen always-on VPN or whether lockdown
  is enabled. Confirming that requires opening the system VPN settings screen the dialog links to.
- **OEM variance.** Some OEM Settings apps hide or relocate the Always-on VPN toggle even when
  `ACTION_VPN_SETTINGS` resolves; the fallback only covers the case where the *action itself* doesn't
  resolve, not a resolved-but-relocated toggle.
- **Aggressive OEM battery managers** (e.g. some Xiaomi/Huawei builds) can still kill background
  services despite Always-on VPN being enabled; this feature does not attempt any OEM-specific
  autostart-permission workaround.

## Testing

- **No JUnit4 suite.** There is no pure decision logic here — the entire feature is two `Intent`
  constructions and a `resolveActivity` branch, already about as small as the extraction target in
  [`implementing-android-features`](../../AGENTS.md)'s "pure decision + exhaustive JUnit4 test" pattern
  would produce, and it has no branch worth isolating from the Compose call site.
- **On-device (manual)**: open Settings hub → Tunnel → "Auto-connect on boot" → dialog appears with the
  explainer copy → tap "Open VPN settings" → system VPN settings (or, on an OEM without
  `ACTION_VPN_SETTINGS`, the top-level Settings app) opens and the dialog closes; tap "Close" and
  confirm the dialog dismisses with no navigation. Separately, enable Always-on VPN for this app in
  system settings, reboot the device, and confirm the tunnel comes up — that on-device check belongs to
  [`failclosed-startup.md`](failclosed-startup.md)'s QA list, not this feature's own code path.

## Related docs

- [`failclosed-startup.md`](failclosed-startup.md) — the manifest wiring and service resilience
  (`START_REDELIVER_INTENT`, the always-on restart caveat) that makes Always-on VPN work with no
  boot-receiver code in this app.
- [`settings-hub.md`](settings-hub.md) — the hub row layout this feature's row lives in.
