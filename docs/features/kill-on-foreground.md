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
| [`KillSwitchRepository.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/killswitch/KillSwitchRepository.kt) | SharedPreferences-backed persistence (`xray_prefs`), exposes `load`/`save` and a `StateFlow` of preferences. Parallel to [`SplitTunnelRepository`](../../app/src/main/java/com/justme/xtls_core_proxy/split/SplitTunnelRepository.kt). |
| [`ForegroundAppMonitor.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/killswitch/ForegroundAppMonitor.kt) | Detection-mechanism interface. Exists so an Accessibility-based implementation can be dropped in later without service-side changes. |
| [`UsageStatsEventSource.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/killswitch/UsageStatsEventSource.kt) + [`AndroidUsageStatsEventSource.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/killswitch/AndroidUsageStatsEventSource.kt) | Wrapper around `UsageStatsManager` so the monitor's state machine is testable against a fake. |
| [`UsageStatsForegroundAppMonitor.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/killswitch/UsageStatsForegroundAppMonitor.kt) | Concrete monitor. 1 s polling on `Dispatchers.Default`. Tracks last-seen foreground package, emits listener events only on change. Pause/resume API used by the screen on/off receiver. |
| [`KillSwitchSettingsActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/killswitch/KillSwitchSettingsActivity.kt) | Compose UI: master toggle, app picker entry, Usage Access banner. Re-checks permission on every `ON_RESUME` so returning from system Settings unlocks the toggle. |

Shared with split-tunnel:

- [`apps/InstalledAppsLoader.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/apps/InstalledAppsLoader.kt) — enumerates installed packages via `PackageManager.getInstalledApplications`. Requires `QUERY_ALL_PACKAGES`.
- [`apps/AppPickerActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/apps/AppPickerActivity.kt) — generic multi-select picker. Caller passes initial selection and title via Intent extras and persists the result; the activity has no knowledge of which feature's prefs it's editing.

Service-side wiring lives in [`vpn/XrayVpnService.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt):

- The service implements `ForegroundAppMonitor.Listener` and starts the monitor on `ACTION_START` if the feature is enabled and the kill-list is non-empty.
- `killTunnel()` / `reviveTunnel()` run on `tunnelOpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))`, which serializes rapid kill→revive bursts.
- The service observes `KillSwitchRepository.observe()` and forwards updates via `monitor.updatePackages(...)`. Disabling the feature while paused triggers an immediate revive.
- A screen-state `BroadcastReceiver` pauses polling on `ACTION_SCREEN_OFF` and resumes on `ACTION_SCREEN_ON`. The monitor preserves controlled-app state across pause/resume.

## Permissions

| Permission | Why | How granted |
|---|---|---|
| `PACKAGE_USAGE_STATS` | Query `UsageStatsManager` for foreground events. | Special access — user toggles in *Settings → Apps → Special access → Usage access*. App opens the system page via `Settings.ACTION_USAGE_ACCESS_SETTINGS`. Gated by `AppOpsManager.unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS, ...)`. |
| `QUERY_ALL_PACKAGES` | Enumerate installed apps for the picker. | Normal install-time permission. Shared with split-tunnel. |

The master toggle stays disabled (greyed) until Usage Access is granted; the banner explains why and provides a button labelled "Open Usage Access settings" that goes straight to the system page.

If Usage Access is revoked mid-session, the monitor logs a warning and stops itself. The existing tunnel state is preserved — no surprise kill or revive — and the kill-switch silently disables until the next VPN start.

## Interaction with split-tunnel

The two features have independent lists. **An app may appear in both lists; this is supported and requires no special handling.** When a controlled app is foregrounded, kill-on-foreground takes precedence:

- Split-tunnel affects per-app routing while the tunnel exists.
- Kill-on-foreground affects whether the tunnel exists at all. No tunnel = nothing for split-tunnel to apply to.

When kill fires for an app that is also in the split-tunnel list, the tunnel is torn down, so the split-tunnel exclusion becomes moot. On revive, both lists resume their respective roles. No UI warnings or cross-list validation are needed.

## Notifications

Two channels, by design:

- **`vpn_channel`** (low importance): the existing foreground-service status notification. Flips between "Connected" and "Paused: \<app label\> is open" via [`LogRepository`](../../app/src/main/java/com/justme/xtls_core_proxy/log/LogRepository.kt)/`VpnConnectionState`. Silent.
- **`vpn_error_channel`** (default importance): posts when `reviveTunnel()` fails. Distinct ID so it survives the foreground service stopping. Tapping opens `MainActivity`. The kill path is silent on failure because the user is actively in the controlled app and will see it complain anyway; the revive path is loud because the user is not in our app and would otherwise not learn the VPN died.

## Known limitations

**Split-screen multi-window.** When the controlled app and our app run side-by-side, switching window focus between them does *not* fire `ACTIVITY_RESUMED` in `UsageStatsManager` — both activities are simultaneously `RESUMED` per Android's multi-window lifecycle. Result: the kill/revive cycle reflects whichever activity most recently transitioned to `RESUMED`, not whichever has window focus now. Editing the kill-list while both apps are visible still works because that path uses the repository observer, not new lifecycle events. Fixing this requires a focus-event source (Accessibility's `TYPE_WINDOW_STATE_CHANGED` / `TYPE_VIEW_FOCUSED`); intentionally deferred. The `ForegroundAppMonitor` interface is shaped so an Accessibility-based implementation can replace the UsageStats one without service-side changes.

**Detection latency floor (~1–2 s).** `UsageStatsManager` is poll-based and the query window has to be wide enough to absorb scheduler jitter. A controlled app reading `TRANSPORT_VPN` exactly on `Activity.onCreate` may briefly observe the tunnel before we kill. Accepted: the goal is compliance, not stealth — the apps we care about re-check on the warning banner, not just first frame. Future Accessibility-based monitor reduces this to near-zero.

**No reboot recovery.** Matches existing VPN behavior; no `BOOT_COMPLETED` receiver. Out of scope.

## Testing

**Unit tests** live in `app/src/test/java/com/justme/xtls_core_proxy/killswitch/`. The state machine is driven against a fake `UsageStatsEventSource` on `StandardTestDispatcher` — covers fires-once-on-transition, no-fire on controlled→controlled, leftForeground on remove-while-paused, pause/resume preserving state, and clean stop on permission revocation. Tunnel-side coverage:

- [`KillSwitchWiringTest`](../../app/src/test/java/com/justme/xtls_core_proxy/killswitch/KillSwitchWiringTest.kt) verifies the monitor → listener event flow end-to-end at the repository boundary.
- [`XrayBridgeCycleTest`](../../app/src/test/java/com/justme/xtls_core_proxy/bridge/XrayBridgeCycleTest.kt) exercises `StartXray` / `StopXray` repeatedly to catch goroutine leaks or stuck internal state in the Go bridge — the rapid kill/revive cycle is what would surface those.

**Manual on-device QA**: see [`docs/qa/kill-switch.md`](../qa/kill-switch.md) for the matrix of scenarios run against MegaFon and *Вкусно — и точка* on physical Android 13+ hardware. Both apps show prominent on-launch banners when a VPN is detected, which makes pass/fail unambiguous (banner absent = pass).

## Future work

- Hybrid Accessibility monitor implementing `ForegroundAppMonitor` for sub-second latency and lower battery use. Play Store policy around accessibility-for-non-accessibility-purposes makes this a non-trivial decision, not just an implementation task.
- Localization (Russian first) of all kill-switch strings in `values/strings.xml`.
- Per-app polling cadence tuned to known check patterns. Speculative; not building until a concrete app demands it.
