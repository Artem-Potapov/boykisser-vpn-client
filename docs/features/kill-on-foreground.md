# Kill-on-Foreground

Maintainer reference for the kill-on-foreground feature: full VPN teardown while a user-selected app is the foreground app, automatic revive on its exit.

## Why this exists

A class of Russian apps (telecoms like MegaFon, government and franchise apps like *Вкусно — и точка*) refuse to function when **any** VPN tunnel is active on the device — even when the user has split-tunneled them out. Detection is via `ConnectivityManager.NetworkCapabilities.TRANSPORT_VPN` and/or the presence of a `tun0` interface; both are observable by any app without special permissions. Split-tunnel is insufficient because the tunnel still exists from the app's point of view.

The compliance contract is therefore stronger than "don't route this app's packets" — it is "no tunnel must exist while this app is in the foreground." That means the TUN file descriptor closed, Xray-core stopped, `ConnectivityManager` reporting no active VPN.

## State machine

```
[VPN OFF] ──user start──> [Connected: TUN up, Xray running, monitor polling]
                                          │ ▲
                     controlled app fg ───┘ │ non-controlled app fg
                                          ▼ │
                                     [Paused: TUN torn down, Xray stopped,
                                              monitor still polling]

[Connected or Paused] ──user stop──> [VPN OFF]
```

- **Kill** = close the TUN `ParcelFileDescriptor` and stop Xray via `XrayBridge` → `StopXray`. After this, `ConnectivityManager` no longer reports an active VPN.
- **Revive** = call `VpnService.Builder.establish()` for a fresh TUN fd, then start Xray via `XrayBridge` → `StartXray` with the same profile, passing the new fd through the existing `xray.tun.fd` / `XRAY_TUN_FD` env-var mechanism.

`VpnConnectionState.PAUSED` is the user-facing label for the kill state.

## Components

The feature lives in [app/src/main/java/com/justme/xtls_core_proxy/killswitch/](../../app/src/main/java/com/justme/xtls_core_proxy/killswitch/).

| File | Responsibility |
|---|---|
| [`KillSwitchRepository.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/killswitch/KillSwitchRepository.kt) | SharedPreferences-backed persistence (`xray_prefs`): `load`/`save`, a process-wide `state: StateFlow<Preferences>` (enabled + package set), and the consent flag (`hasConsented`/`markConsented`, key `kill_switch_consented`). Parallel to [`SplitTunnelRepository`](../../app/src/main/java/com/justme/xtls_core_proxy/split/SplitTunnelRepository.kt). |
| [`ForegroundAppMonitor.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/killswitch/ForegroundAppMonitor.kt) | Detection-mechanism interface. Exists so an Accessibility-based implementation can be dropped in later without service-side changes. |
| [`UsageStatsEventSource.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/killswitch/UsageStatsEventSource.kt) + [`AndroidUsageStatsEventSource.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/killswitch/AndroidUsageStatsEventSource.kt) | Wrapper around `UsageStatsManager` so the monitor's state machine is testable against a fake. |
| [`UsageStatsForegroundAppMonitor.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/killswitch/UsageStatsForegroundAppMonitor.kt) | Concrete monitor. 1 s polling on `Dispatchers.Default`. Tracks last-seen foreground package, emits listener events only on change. Pause/resume API used by the screen on/off receiver. |
| [`KillSwitchSettingsActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/killswitch/KillSwitchSettingsActivity.kt) | Compose UI: master toggle, app picker entry, Usage Access banner. Re-checks permission on every `ON_RESUME` so returning from system Settings unlocks the toggle. Toggling ON does **not** save — it opens the consent gate (below); only accepting persists `enabled = true`. Toggling OFF saves immediately, no gate. |
| [`KillSwitchConsentDialog.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/killswitch/KillSwitchConsentDialog.kt) | The consent gate dialog: countdown-locked accept/cancel buttons, back-press blocked during the countdown. See "Consent gate" below. |
| [`vpn/VpnNotifications.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/VpnNotifications.kt) | The exposed heads-up notification surface (channel creation, build/post/cancel), extracted from the service so it is exercisable from an instrumented test — services can't be instantiated directly. Owns the `NOTIFICATION_ID = 1101` / `EXPOSED_NOTIFICATION_ID = 1103` constants. |

Shared with split-tunnel:

- [`apps/InstalledAppsLoader.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/apps/InstalledAppsLoader.kt) — enumerates installed packages via `PackageManager.getInstalledApplications`. Requires `QUERY_ALL_PACKAGES`.
- [`apps/AppPickerActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/apps/AppPickerActivity.kt) — generic multi-select picker. Caller passes initial selection and title via Intent extras and persists the result; the activity has no knowledge of which feature's prefs it's editing.

Service-side wiring lives in [`vpn/XrayVpnService.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt):

- After a successful tunnel bring-up the service loads prefs once (`KillSwitchRepository.load`), applies them, then collects `KillSwitchRepository.state` (a `StateFlow<Preferences>`) in `settingsObserverJob` so live edits from the settings UI take effect immediately. The monitor runs only while `enabled && packages.isNotEmpty() && running` (`applyKillSwitchPreferences`).
- The kill/revive listener is the service's inner `KillSwitchListener` (`ForegroundAppMonitor.Listener`); it resolves the trigger package's label (falling back to the package name) before killing.
- `killTunnel()` / `reviveTunnel()` run on `tunnelOpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))`, which serializes rapid kill→revive bursts.
- Package-list edits reach the running monitor via `monitor.updatePackages(...)`, which reconciles against the *current* foreground app (adding the foregrounded app kills immediately; removing it revives). Disabling the feature — or emptying the list — while paused stops the monitor and triggers an immediate revive.
- A screen-state `BroadcastReceiver` (registered only while the monitor runs) calls `pausePolling()` on `ACTION_SCREEN_OFF` and `resumePolling()` on `ACTION_SCREEN_ON`. The monitor preserves controlled-app state across pause/resume.

## Consent gate

Enabling the feature is gated behind an explicit, deliberately-slowed consent dialog ([`KillSwitchConsentDialog.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/killswitch/KillSwitchConsentDialog.kt)). The feature turns the VPN off **device-wide** when triggered, so an accidental or reflexive enable is the failure mode being designed against.

Flow (all in `KillSwitchSettingsActivity`):

1. User flips the master toggle ON → `showConsentGate = true`. **Nothing is persisted yet**; the toggle visually stays off because `prefs.enabled` is unchanged.
2. The dialog explains the device-wide exposure (`kill_switch_consent_title` / `_body`). Both buttons start disabled behind a countdown: **5 s on first-ever consent, 2 s for a returning user** (`COUNTDOWN_FIRST` / `COUNTDOWN_RETURNING`; "returning" = `KillSwitchRepository.hasConsented()` is already true). The accept button label shows the ticking count (`kill_switch_consent_accept_countdown`, "I understand (%1$d)").
3. During the countdown the dialog is inescapable-by-accident: a `BackHandler` swallows back-presses, `dismissOnClickOutside` is `false` unconditionally, and `dismissOnBackPress` is tied to countdown completion. After the countdown, back-press falls through to `onDismissRequest` and **declines**.
4. **Accept** → `KillSwitchRepository.save(enabled = true, ...)` + `markConsented()` (pref `kill_switch_consented` in `xray_prefs`). **Decline** → the dialog closes and nothing is written; the feature stays off.
5. The countdown state and gate visibility live in `rememberSaveable`, so rotating mid-countdown neither bypasses the gate nor restarts the timer.

The gate fires on **every** enable, even after prior consent — prior consent only shortens the countdown from 5 s to 2 s. This is deliberate: the dialog is the reminder of what the feature does, not a one-time EULA. `KillSwitchConsentGateTest` locks in the no-bypass properties (no commit until accept, decline leaves off, gate re-fires after prior consent, rotation survives).

## Permissions

| Permission | Why | How granted |
|---|---|---|
| `PACKAGE_USAGE_STATS` | Query `UsageStatsManager` for foreground events. | Special access — user toggles in *Settings → Apps → Special access → Usage access*. App opens the system page via `Settings.ACTION_USAGE_ACCESS_SETTINGS`. Gated by `AppOpsManager.checkOpNoThrow(OPSTR_GET_USAGE_STATS, ...)`. |
| `QUERY_ALL_PACKAGES` | Enumerate installed apps for the picker. | Normal install-time permission. Shared with split-tunnel. |

The master toggle stays disabled (greyed) until Usage Access is granted; the banner explains why and provides a button labelled "Open Usage Access settings" that goes straight to the system page.

If Usage Access is revoked mid-session, the next `queryForegroundEvents` throws; the monitor logs `KillSwitchMonitor query failed` and its poll loop exits. The existing tunnel state is preserved — no surprise kill or revive. Note the service-side nuance: `XrayVpnService` constructs the monitor **without** an `onAutoStop` callback, so the dead monitor's reference stays set in the service until VPN stop or a prefs change clears it — the kill-switch is silently inert, not visibly disabled. A screen off→on cycle can restart the poll loop (`pausePolling` nulls the job; `resumePolling` relaunches it), which immediately exits again if access is still revoked.

## Interaction with split-tunnel

The two features have independent lists. **An app may appear in both lists; this is supported and requires no special handling.** When a controlled app is foregrounded, kill-on-foreground takes precedence:

- Split-tunnel affects per-app routing while the tunnel exists.
- Kill-on-foreground affects whether the tunnel exists at all. No tunnel = nothing for split-tunnel to apply to.

When kill fires for an app that is also in the split-tunnel list, the tunnel is torn down, so the split-tunnel exclusion becomes moot. On revive, both lists resume their respective roles. No UI warnings or cross-list validation are needed.

## Notifications

Three channels, by design (actual ids in code are `xray_vpn_*`):

- **`xray_vpn_channel`** (low importance, silent): the ongoing foreground-service status notification — **connecting/connected**, and while killed it drops to the paused status line (`vpn_status_paused`, "Paused: %1$s is open"). Updated in place under `NOTIFICATION_ID = 1101`.
- **`xray_vpn_exposed_channel`** (high importance, heads-up): the **paused/exposed** indicator. While a controlled app holds the tunnel down, `killTunnel` posts this via `VpnNotifications.postExposed(...)` under its **own** id, `EXPOSED_NOTIFICATION_ID = 1103` — **not** `NOTIFICATION_ID`. This separate id is load-bearing: **a notification's channel is fixed at its first post.** `1101` is first posted as the ongoing FGS notification on the *low* channel, so re-posting the exposed alert on `1101` would keep it low and silent — it could never heads-up (this was a real bug; reposting on the same id changed the text but threw no alert). A fresh id is a fresh post, so the alert adopts the high-importance channel and actually alerts. It spells out — via `BigTextStyle`, a red accent, and the trigger app's label — that the VPN is OFF for *every* app and the real IP is exposed. `reviveTunnel`/`stopVpn` call `VpnNotifications.cancelExposed(...)` to clear it (the ongoing `1101` notification lives separately and is managed on its own). A new *channel* id is also required because Android ignores app-side importance increases on an existing channel.
- **`xray_vpn_error_channel`** (default importance): posts when `reviveTunnel()` fails (also reused for the permission-revoked-at-start error), under a distinct id (`1102`) so it survives the foreground service stopping. Tapping opens `MainActivity`.

State writes (`setConnectionState`) are ordered **ahead of** the notification post, and `NotificationManager.notify()` is a silent no-op when `POST_NOTIFICATIONS` is denied, so a missing notification permission never stalls the paused↔connected state machine.

**Swipe-away re-post (Android 14+).** Android 14 makes ongoing FGS notifications user-dismissable with no opt-out flag. Both the ongoing FGS notification and the exposed alert carry a `deleteIntent` (`notificationDismissIntent()`) that fires `ACTION_NOTIFICATION_DISMISSED` back into the service; `StartCommandDecision` maps it to `RepostNotification`. If the VPN is running, `repostOngoingNotification()` is marshalled onto `tunnelOpScope` — serializing behind any in-flight kill/revive so a swipe mid-transition reads the settled state — and re-posts per the current connection state. In `PAUSED` it restores **both** notifications (the quiet `1101` paused line and the loud `1103` exposed alert, rebuilt from the service's `lastTriggerLabel`), since either could have been the one swiped. `DISCONNECTED`/`ERROR` are no-ops; if the service is no longer running, the stale delivery just calls `stopSelf()`.

**Localization.** All notification strings (including `vpn_status_paused` and the `vpn_exposed_*` set) are resolved per-call through `SupportedLanguage.localize(...)` — service contexts don't pick up per-app locale changes mid-session on API < 33. Channel names/descriptions are cached by the system at channel-creation time; that staleness is an Android limitation. English and Russian strings are both shipped (`values/strings.xml`, `values-ru/strings.xml`).

## Known limitations

**Split-screen multi-window.** When the controlled app and our app run side-by-side, switching window focus between them does *not* fire `ACTIVITY_RESUMED` in `UsageStatsManager` — both activities are simultaneously `RESUMED` per Android's multi-window lifecycle. Result: the kill/revive cycle reflects whichever activity most recently transitioned to `RESUMED`, not whichever has window focus now. Editing the kill-list while both apps are visible still works because that path uses the repository observer, not new lifecycle events. Fixing this requires a focus-event source (Accessibility's `TYPE_WINDOW_STATE_CHANGED` / `TYPE_VIEW_FOCUSED`); intentionally deferred. The `ForegroundAppMonitor` interface is shaped so an Accessibility-based implementation can replace the UsageStats one without service-side changes.

**Detection latency floor (~1–2 s).** `UsageStatsManager` is poll-based and the query window has to be wide enough to absorb scheduler jitter. A controlled app reading `TRANSPORT_VPN` exactly on `Activity.onCreate` may briefly observe the tunnel before we kill. Accepted: the goal is compliance, not stealth — the apps we care about re-check on the warning banner, not just first frame. Future Accessibility-based monitor reduces this to near-zero.

**No reboot recovery.** Matches existing VPN behavior; no `BOOT_COMPLETED` receiver. Out of scope.

## Testing

**JVM unit tests** (`app/src/test/java/com/justme/xtls_core_proxy/killswitch/`):

- [`UsageStatsForegroundAppMonitorTest`](../../app/src/test/java/com/justme/xtls_core_proxy/killswitch/UsageStatsForegroundAppMonitorTest.kt) drives the monitor state machine against a fake `UsageStatsEventSource` — fires-once-on-transition, no-fire on controlled→controlled, `updatePackages` reconciliation (add/remove the current foreground app), pause/resume preserving controlled state, poll-loop exit when the source throws, and stop-during-tick not invoking the listener.
- [`KillSwitchWiringTest`](../../app/src/test/java/com/justme/xtls_core_proxy/killswitch/KillSwitchWiringTest.kt) verifies the monitor → listener event flow end-to-end at the repository boundary.
- [`KillSwitchRepositoryTest`](../../app/src/test/java/com/justme/xtls_core_proxy/killswitch/KillSwitchRepositoryTest.kt) covers load/save round-trips, `state` emission, and the consent flag.

**Instrumented tests** (`app/src/androidTest/`, run locally — not in CI):

- [`KillSwitchConsentGateTest`](../../app/src/androidTest/java/com/justme/xtls_core_proxy/killswitch/KillSwitchConsentGateTest.kt) — no-bypass coverage of the consent gate: toggling on shows the gate and commits nothing until accept, decline leaves the feature off, the gate re-fires even after prior consent, and rotation mid-countdown doesn't bypass it. Grants `GET_USAGE_STATS` via `appops` shell in `@BeforeClass`; uses the v2 `createEmptyComposeRule` + `ActivityScenario` so prefs can be seeded before `onCreate` and the real-time `delay()` countdown can be awaited with `waitUntil` (virtual-time clocks don't advance coroutine `delay`).
- [`VpnNotificationsTest`](../../app/src/androidTest/java/com/justme/xtls_core_proxy/vpn/VpnNotificationsTest.kt) — the exposed channel is `IMPORTANCE_HIGH`, posting never throws when `POST_NOTIFICATIONS` is denied, and the separate-id regression (exposed alert must not be welded to the ongoing notification's low channel).
- [`XrayBridgeCycleTest`](../../app/src/androidTest/java/com/justme/xtls_core_proxy/bridge/XrayBridgeCycleTest.kt) — exercises `StartXray` / `StopXray` repeatedly on-device to catch goroutine leaks or stuck internal state in the Go bridge; the rapid kill/revive cycle is what would surface those.

**Manual on-device QA**: see [`docs/qa/kill-switch.md`](../qa/kill-switch.md) for the matrix of scenarios run against MegaFon and *Вкусно — и точка* on physical Android 13+ hardware. Both apps show prominent on-launch banners when a VPN is detected, which makes pass/fail unambiguous (banner absent = pass).

## Future work

- Hybrid Accessibility monitor implementing `ForegroundAppMonitor` for sub-second latency and lower battery use. Play Store policy around accessibility-for-non-accessibility-purposes makes this a non-trivial decision, not just an implementation task.
- Per-app polling cadence tuned to known check patterns. Speculative; not building until a concrete app demands it.
