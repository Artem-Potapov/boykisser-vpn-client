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
| [`vpn/VpnNotifications.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/VpnNotifications.kt) | The exposed heads-up notification surface (channel creation, build/post/cancel), extracted from the service so it is exercisable from an instrumented test — services can't be instantiated directly. Owns the `NOTIFICATION_ID = 1101` / `EXPOSED_NOTIFICATION_ID = 1103` constants, and (added by auto-failover) `FAILOVER_NOTIFICATION_ID = 1104` / `FAILOVER_BLACKHOLE_NOTIFICATION_ID = 1105` plus their two channels, and `KILL_SWITCH_NOT_APPLIED_NOTIFICATION_ID = 1106` (no new channel — it reuses `EXPOSED_CHANNEL_ID`). `ERROR_NOTIFICATION_ID = 1102` and the two base channel ids stay private in `XrayVpnService`. |

Shared with split-tunnel:

- [`apps/InstalledAppsLoader.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/apps/InstalledAppsLoader.kt) — enumerates installed packages via `PackageManager.getInstalledApplications`. Requires `QUERY_ALL_PACKAGES`.
- [`apps/AppPickerActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/apps/AppPickerActivity.kt) — generic multi-select picker. Caller passes initial selection and title via Intent extras and persists the result; the activity has no knowledge of which feature's prefs it's editing.

Service-side wiring lives in [`vpn/XrayVpnService.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt):

- After a successful tunnel bring-up the service loads prefs once (`KillSwitchRepository.load`), applies them, then collects `KillSwitchRepository.state` (a `StateFlow<Preferences>`) in `settingsObserverJob` so live edits from the settings UI take effect immediately. The monitor runs only while `enabled && packages.isNotEmpty() && running` (`applyKillSwitchPreferences`).
- The kill/revive listener is the service's inner `KillSwitchListener` (`ForegroundAppMonitor.Listener`); it resolves the trigger package's label (falling back to the package name) before killing.
- `killTunnel()` / `reviveTunnel()` run on `tunnelOpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))`, which serializes rapid kill→revive bursts.
- Package-list edits reach the running monitor via `monitor.updatePackages(...)`, which reconciles against the *current* foreground app (adding the foregrounded app kills immediately; removing it revives). Disabling the feature — or emptying the list — while paused stops the monitor and triggers an immediate revive.
- A screen-state `BroadcastReceiver` calls `pausePolling()` on `ACTION_SCREEN_OFF` and `resumePolling()` on `ACTION_SCREEN_ON`. The monitor preserves controlled-app state across pause/resume. **The receiver is now SHARED with auto-failover's health monitor** and is no longer owned by this feature: `reconcileScreenReceiverLocked` holds it while *either* monitor is live and releases it only when *neither* is (`shouldHoldScreenReceiver(killSwitchLive, failoverLive)` in `SessionLifecycleDecision.kt`). The old kill-switch-only ownership broke failover two ways — with failover on and the kill-switch off (the default pairing) no receiver existed at all, and disabling the kill-switch mid-session tore the receiver out from under a running failover monitor. Both `applyKillSwitchPreferences` branches now *reconcile* rather than register/unregister outright.
- **Kill and revive interact with auto-failover's rotation.** A kill landing during a rotation (`ROTATING`) is **deferred** exactly like one landing during a revive — `shouldDeferKillDuringTransition` is the single rule covering both — and replayed if the rotation commits `CONNECTED`. A rotation has a **third** exit, though: if it *gives up*, `giveUpRotationLocked` **drops** the deferred kill (clearing `pendingKillLabel` at the top of the funnel) and posts the "VPN is still on" notice (id **1106**, on this feature's own exposure channel) naming the app. Replaying it minutes later instead would tear down a just-restored tunnel and blame an app the user closed long ago; dropping it silently would leave the kill-switch quietly non-functioning. See [auto-failover.md](auto-failover.md). `killTunnel` **stops** (not pauses) the failover monitor, because pausing preserves the consecutive-failure count and it would trip instantly on revive; `reviveTunnel`'s success path re-applies failover preferences to bring the monitor back, and clears any leftover give-up state + its `1105` alert. See [auto-failover.md](auto-failover.md).

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

**Five** channels, by design (actual ids in code are `xray_vpn_*`) — three owned by this feature, two added by [auto-failover](auto-failover.md). Each notification id is used on exactly one channel, and the ids are mutually distinct; `vpn/FailoverNotificationIdsTest` (JVM, so it runs in CI) pins that.

- **`xray_vpn_channel`** (low importance, silent): the ongoing foreground-service status notification — **connecting/connected**, and while killed it drops to the paused status line (`vpn_status_paused`, "Paused: %1$s is open"). Updated in place under `NOTIFICATION_ID = 1101`. It also carries a **Stop action** (added for auto-failover's give-up states, where the app UI may be closed and the copy is telling the user to turn the VPN off) — that action is present in *every* running state, including PAUSED.
- **`xray_vpn_exposed_channel`** (high importance, heads-up): the **paused/exposed** indicator. While a controlled app holds the tunnel down, `killTunnel` posts this via `VpnNotifications.postExposed(...)` under its **own** id, `EXPOSED_NOTIFICATION_ID = 1103` — **not** `NOTIFICATION_ID`. This separate id is load-bearing: **a notification's channel is fixed at its first post.** `1101` is first posted as the ongoing FGS notification on the *low* channel, so re-posting the exposed alert on `1101` would keep it low and silent — it could never heads-up (this was a real bug; reposting on the same id changed the text but threw no alert). A fresh id is a fresh post, so the alert adopts the high-importance channel and actually alerts. It spells out — via `BigTextStyle`, a red accent, and the trigger app's label — that the VPN is OFF for *every* app and the real IP is exposed. `reviveTunnel`/`stopVpn` call `VpnNotifications.cancelExposed(...)` to clear it (the ongoing `1101` notification lives separately and is managed on its own). A new *channel* id is also required because Android ignores app-side importance increases on an existing channel. **This channel also carries a second notice, `KILL_SWITCH_NOT_APPLIED_NOTIFICATION_ID = 1106`** (`postKillSwitchNotApplied`): when an auto-failover give-up drops a kill that was deferred during a rotation, this is what tells the user the VPN was *not* turned off for that app. Its own **id** is mandatory (sharing 1103 would replace the exposure alert), but it needs no new channel — ids and channels are independent, and "your kill-switch did not act" belongs on exactly this high-importance channel. **1106 is retracted by `cancelKillSwitchNotApplied`, and `killTunnel` is one of the two callers.** The notice claims in the *present tense* that a listed app is still going through the VPN, and `setAutoCancel(true)` only clears it if the user taps it — so `killTunnel` cancels 1106 **immediately before posting 1103**, because the deferred kill has now landed and the tunnel is gone. Both notices sit on *this same* high-importance channel, so skipping that retraction pairs "VPN is OFF for every app" with "that app is still going through the VPN" as two simultaneous heads-up alerts. `stopVpn` cancels it too, beside `cancelExposed`/`cancelFailoverBlackholed`. Retract **before** posting; the contradictory pair must never coexist, not even briefly. **The same retract-before-post applies to `1105`**, auto-failover's give-up alert on its own equally loud channel: `killTunnel` cancels it in the same block. A give-up leaves `sessionTunnelState == CONNECTED` on every path that posts `1105`, and nothing in that path touches the kill-switch monitor, so a pause landing on a blackholed or degraded session is ordinary — and would otherwise pair "your connection was paused to keep you protected" with "the VPN is OFF and you're exposed". `giveUpOutcome` itself is deliberately left set; see [auto-failover.md](auto-failover.md).
- **`xray_vpn_error_channel`** (default importance): posts when `reviveTunnel()` fails (also reused for the permission-revoked-at-start error, and for auto-failover's "the VPN has been switched off" stop), under a distinct id (`1102`) so it survives the foreground service stopping. Tapping opens `MainActivity`.
- **`xray_vpn_failover_channel`** (default importance) — auto-failover's routine "**Switched server**" notice, id `1104`, `setAutoCancel(true)`. Default importance on purpose: an automatic server switch is routine housekeeping and must not heads-up like the exposure alert. It reports a completed event, so it needs no cancel counterpart.
- **`xray_vpn_failover_blackhole_channel`** (high importance, heads-up) — auto-failover's **give-up** alert, id `1105`, cleared by `VpnNotifications.cancelFailoverBlackholed(...)`. A separate channel from `1104` for the same reason `1103` is separate from `1101` (Android ignores app-side importance *increases* on an existing channel) **plus** a second one: muting routine switch spam must not also mute "no server could be reached". All three give-up variants (`postFailoverBlackholed` / `postFailoverNoResponse` / `postFailoverUnprotected`) deliberately share this one id — only one give-up state exists at a time, so a later variant must *replace* the earlier notice rather than stack beside it. See [auto-failover.md](auto-failover.md) for why the three must never share one *message*.

State writes (`setConnectionState`) are ordered **ahead of** the notification post, and `NotificationManager.notify()` is a silent no-op when `POST_NOTIFICATIONS` is denied, so a missing notification permission never stalls the paused↔connected state machine.

**Swipe-away re-post (Android 14+).** Android 14 makes ongoing FGS notifications user-dismissable with no opt-out flag. Both the ongoing FGS notification and the exposed alert carry a `deleteIntent` (`notificationDismissIntent()`) that fires `ACTION_NOTIFICATION_DISMISSED` back into the service; `StartCommandDecision` maps it to `RepostNotification`. If the VPN is running, `repostOngoingNotification()` is marshalled onto `tunnelOpScope` — serializing behind any in-flight kill/revive so a swipe mid-transition reads the settled state — and re-posts per the current connection state. In `PAUSED` it restores **both** notifications (the quiet `1101` paused line and the loud `1103` exposed alert, rebuilt from the service's `lastTriggerLabel`), since either could have been the one swiped. `DISCONNECTED` is a no-op; if the service is no longer running, the stale delivery just calls `stopSelf()`.

Auto-failover added two more branches here. `BLACKHOLED` restores **only** `1101` (with the no-response or blackholed line depending on `giveUpOutcome`) — `1105` is `setAutoCancel(true)`, so re-posting it would fight a deliberate dismissal. `ERROR` is *still* a no-op in the normal case (the session is dying and there is nothing ongoing to restore) **except** when `giveUpOutcome == UNPROTECTED`, where the service really is still running and the line it needs is the honest "not protected" one — never the containment copy.

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
