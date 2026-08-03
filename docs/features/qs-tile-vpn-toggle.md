# Quick Settings VPN Tile

Maintainer reference for the system-level Quick Settings tile that toggles the Xray VPN tunnel without opening the app: the tile decides whether to dispatch directly to the foreground service, or to hand off to `MainActivity` when first-time permissions are still missing.

## Why this exists

The app is a "connect to my one or two regular servers" daily-driver more than a configuration-tweaking tool. Pulling down the shade and tapping a tile is dramatically faster than launching the activity, waiting for the profile list to populate, and tapping Connect. The tile is therefore the **primary** start path for returning users; the in-app Connect button is for first-run, profile management, and recovery.

A tile that worked only on already-permitted users would be useless on first launch. So the design accepts that some taps must be a fallback into `MainActivity` (to drive the `VpnService.prepare()` consent dialog and the `POST_NOTIFICATIONS` runtime grant), while keeping the steady-state path tile-only.

## State machine

```
              tile observed connectionState
              ┌────────────────────────────┐
              │                            │
[Tile bound] ─┤   DISCONNECTED / ERROR     │
              │   → STATE_INACTIVE         │
              │                            │
              │   CONNECTING / CONNECTED   │
              │   / PAUSED / BLACKHOLED    │
              │   → STATE_ACTIVE           │
              └────────────────────────────┘

[User taps]
   │
   ├── if state ∈ {CONNECTING, CONNECTED, PAUSED, BLACKHOLED}
   │       └── unlockAndRun { startForegroundService(ACTION_STOP) }
   │
   └── else (DISCONNECTED, ERROR)
           ├── IO: ActiveProfileRepository.pickOrPersistActive(ctx)
           │      └── null → toast "Please add a configuration in the app first", stop
           │
           └── Main + unlockAndRun:
                  ├── VpnService.prepare(ctx) != null  ─┐
                  ├── POST_NOTIFICATIONS missing ────────┤── launch MainActivity
                  │                                      │   with EXTRA_TILE_AUTOCONNECT
                  │                                      │   + EXTRA_TILE_PROFILE_ID
                  └── both OK → startForegroundService(ACTION_START + profileId)
```

The tile **observes** state but does not own it. `LogRepository.connectionState` is the canonical source, written by `XrayVpnService`; the tile collects it on `onStartListening` and re-renders. Tap intent is decided from `connectionState.value` at click time, not from any tile-private state.

**Tile text.** `updateTile` resolves strings through `SupportedLanguage.localize(applicationContext)` and writes the localized connection state ("Disconnected" / "Connecting" / "Paused" / "Error") into the tile's `label`. It always sets `subtitle = null` (and calls `updateTile()`), which also clears any subtitle cached from older builds that wrote state into the subtitle field. The manifest `android:label="@string/tile_label"` ("VPN") is used only by the QS *tile picker* (the system UI for adding/removing tiles); the panel never shows it once the tile is on screen. The stacked "VPN" + state rendering an earlier version produced was redundant — a user with the app installed already knows what the tile is for. The no-profile toast uses the same `SupportedLanguage.localize` path.

When state is **CONNECTED** the label is the active profile's `name` field instead of the literal "Connected" — gives the user immediate confirmation of *which* profile is up. The lookup is a single `ProfileDao.getById` on `Dispatchers.IO` inside the `updateTile` suspend function, so the collector path serializes the resolution against the next state transition without blocking the main thread. Falls back to the generic `main_state_connected` string if (a) no active profile id is set, (b) the profile row has been deleted between connect and the tile re-render, or (c) the profile's name is blank.

## Components

The tile lives in [`app/src/main/java/com/justme/xtls_core_proxy/tile/`](../../app/src/main/java/com/justme/xtls_core_proxy/tile/). The persistence layer it shares with `VpnViewModel` lives in [`state/`](../../app/src/main/java/com/justme/xtls_core_proxy/state/).

| File | Responsibility |
|---|---|
| [`tile/XrayVpnTileService.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/tile/XrayVpnTileService.kt) | The `TileService` subclass. Observes `LogRepository.connectionState` on `onStartListening`, renders localized state (via `SupportedLanguage.localize`) or the active profile's name when CONNECTED into `qsTile.label` (always `subtitle = null`), decides start/stop at click time via the pure `decideTileClick` function (see `tile/TileClickDecision.kt`), dispatches to `XrayVpnService` via `startForegroundService` for both start and stop, hands off to `MainActivity` when first-time permissions are still missing. |
| [`tile/TileClickDecision.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/tile/TileClickDecision.kt) | Sealed `TileClickDecision` (`Stop`, `NoProfileToast`, `Start(id)`, `HandoffToMainActivity(id)`) plus the pure `decideTileClick(state, profileId, needsVpnConsent, needsNotifPermission)` extracted from `XrayVpnTileService` so the click pipeline is unit-testable without the QS framework. |
| [`state/ActiveProfileRepository.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/ActiveProfileRepository.kt) | Shared owner of the `vpn_prefs.active_profile_id` SharedPreferences key. Exposes `activeProfileIdFlow: StateFlow<Long?>` observed by `VpnViewModel` throughout its lifetime, plus `pickOrPersistActive(ctx)` which honors a valid stored id or falls back to `ProfileDao.getFirst()` when none is stored. Tile and VM share the same persistence and the same observable surface. |
| [`log/LogRepository.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/log/LogRepository.kt) | Owns `connectionState: StateFlow<VpnConnectionState>` (the tile collects this on `onStartListening`) and `errorEvents: SharedFlow<Int>` (replay=1, DROP_OLDEST; the VM collects this in `init`). `XrayVpnService` pairs every `setConnectionState(ERROR)` call with `emitError(@StringRes resId)`, so tile-initiated failures propagate to the in-app error Text in addition to the existing error notification channel. |
| [`db/ProfileDao.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/db/ProfileDao.kt) | Source of `getFirst()` — `SELECT * FROM profiles ORDER BY id ASC LIMIT 1`. Used by `pickOrPersistActive` to pick a default when nothing is stored; covers manual and subscription-imported profiles alike. Also provides `getById(id)`, called by the tile when CONNECTED to resolve the active profile's name for the label. |
| [`MainActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/MainActivity.kt) — `maybeAutoConnectFromTile` | Consumes `EXTRA_TILE_AUTOCONNECT` + `EXTRA_TILE_PROFILE_ID`, single-shot strips both extras, runs the same permission dance the in-app Connect button does, then calls `viewModel.connect(...)`. Gated by `savedInstanceState == null` in `onCreate` so rotation / process-death recovery doesn't re-trigger. |
| [`res/drawable/ic_vpn_tile.xml`](../../app/src/main/res/drawable/ic_vpn_tile.xml) | 24dp vector cat mascot, intentionally full-bleed (~96% of the 24×24 viewport). Paths use solid `#FFFFFFFF` fills (no opaque background rect). The QS framework state-tints it (active/inactive); explicit white fills are the standard pattern that lets the framework do that tinting. See [Tile icon and One UI scaling](#tile-icon-and-one-ui-scaling) below. |
| [`res/values/strings.xml`](../../app/src/main/res/values/strings.xml) / [`values-ru/strings.xml`](../../app/src/main/res/values-ru/strings.xml) | `tile_label` ("VPN" in both locales), `tile_toast_no_profiles`, `vpn_permission_revoked_error`. |
| [`AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml) | `<service android:name=".tile.XrayVpnTileService"` with `android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"` and `<action android:name="android.service.quicksettings.action.QS_TILE"/>`. `MainActivity` has `android:launchMode="singleTop"` (deterministic handoff) plus a second `<intent-filter>` for `android.service.quicksettings.action.QS_TILE_PREFERENCES` + `category.DEFAULT` that overrides the OS default long-press → app-info behavior. |

`XrayVpnService` itself ([`vpn/XrayVpnService.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt)) is shared with the in-app start path — the tile and `VpnViewModel.connect` both dispatch the same `ACTION_START` + `EXTRA_PROFILE_ID` intent. There is no tile-specific service code path.

## Tile icon and One UI scaling

The tile icon is intentionally drawn near full-bleed. One UI's "Available buttons" tile picker scales icons by its own rules that (a) differ from how the live/active tile sizes them and (b) drift across One UI versions. The picker and the live tile can never be made to render the cat at the same size — and this affects every app's tile, not just ours. Tuning the drawable's internal padding or scale does **not** close the picker-vs-live gap; it only trades "oversized in live" for "too small in live."

**Do not** reintroduce a smaller scale or extra padding to make the picker look right. Keep the icon full-bleed and judge it on the **live** QS panel only. White fills plus framework state-tint is the correct pattern (`#FFFFFFFF` fills, transparent background).

## Click handling: the IO / unlock split

`TileService.onClick` is delivered on the main thread while the QS panel is open. Two operations there are sensitive:

1. **`ActiveProfileRepository.pickOrPersistActive`** — touches Room. Must run off the main thread.
2. **Launching `MainActivity` via `startActivityAndCollapse`** — must run on the main thread, and only after the device is unlocked.

The current shape (see `handleClick` in `XrayVpnTileService.kt`):

```kotlin
clickJob = serviceScope.launch(Dispatchers.IO) {
    val profileId = ActiveProfileRepository.pickOrPersistActive(appCtx)
    // ... toast on null ...
    withContext(Dispatchers.Main) {
        runOrDeferUnlock {
            // VpnService.prepare(), POST_NOTIFICATIONS check, dispatch
        }
    }
}
```

Why `unlockAndRun` wraps only the Main-thread tail, not the whole click handler:

- `pickOrPersistActive` does not need an unlocked device — Room reads `xraytun.db` directly, no UI involvement.
- Pulling the DB read out of the unlock callback minimizes the time spent waiting on the user to unlock and reduces the window in which the IO completion could race with the device re-locking.

The stop path needs no IO at all (profile id isn't required to stop), so it goes directly through `runOrDeferUnlock { sendStopIntent() }`. `sendStopIntent` uses `startForegroundService` (not plain `startService`) because `XrayVpnService` is a foreground service and API 31+ background-start restrictions can deny `startService` if the tile's transient foreground grant has already elapsed by the time the stop dispatch runs.

## Concurrency model

`XrayVpnTileService` uses a **single service-scoped** `MainScope()` (created at field-init, cancelled in `onDestroy`) and tracks two jobs against it:

- `listenJob` — the `LogRepository.connectionState.collect { updateTile(it) }` collector. Started on `onStartListening`, cancelled on `onStopListening`.
- `clickJob` — the per-click IO + Main coroutine. Started on `handleClick` (start path); cancelled on the next click and on `onStopListening`.

This mirrors the pattern used in [`XrayVpnService.serviceScope`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt) and avoids the earlier pattern of allocating fresh `CoroutineScope(SupervisorJob() + ...)` instances per event, which leaked transient `SupervisorJob` parents whose only children were the launched jobs anyway.

Cancellation semantics: `clickJob?.cancel()` at the top of a new click cancels any in-flight previous click before reassigning. Rapid double-taps therefore do not race two parallel `pickOrPersistActive` calls into two parallel `sendStartIntent` calls.

`StateFlow` always replays its current value to a new collector, so `listenJob` does not need a manual prime — `collect` will receive the current connection state synchronously on subscription.

## Permission handoff to MainActivity

When `VpnService.prepare(ctx)` returns non-null (consent never granted, or revoked since) **or** `POST_NOTIFICATIONS` is not granted on API 33+, the tile cannot proceed without an Activity. It builds an intent with:

- `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP`
- `EXTRA_TILE_AUTOCONNECT = true`
- `EXTRA_TILE_PROFILE_ID = profileId` (the resolved id, so the activity does not need to re-pick)

…and dispatches via `startActivityAndCollapse`. The API split:

- **API 34+ (`UPSIDE_DOWN_CAKE`)** — uses the `PendingIntent` overload, which is the modern non-deprecated path.
- **API 29–33** — uses the deprecated `Intent` overload. Two annotations are required because they suppress different things: `@Suppress("DEPRECATION")` silences the Kotlin compiler's deprecation warning on the call, and `@SuppressLint("StartActivityAndCollapseDeprecated")` silences the Android lint check (which fires as an *error*, not a warning, since newer lint baselines). `minSdk = 29`, so the floor is fixed; both overloads must be supported for the lifetime of those API levels.

`MainActivity` is `android:launchMode="singleTop"`, so:

- If `MainActivity` is already foreground, `onNewIntent` fires with the tile extras. `setIntent(newIntent)` writes the new intent back so subsequent rotations don't replay the old one.
- If `MainActivity` is not the current top of its task, a fresh `onCreate` runs with `savedInstanceState == null`, and the tile extras are handled there.

`maybeAutoConnectFromTile` does both: takes a non-nullable `Intent`, reads the extras, **removes them with `removeExtra` so the next rotation / `onResume` doesn't re-trigger**, then runs the same `notificationPermissionLauncher` / `requestVpnPermissionAndConnect()` flow as the in-app Connect button. The `onCreate` call site is gated by `savedInstanceState == null` so the autoconnect only fires on a fresh launch, not on configuration-change recreate.

## Long-press handoff to MainActivity

Android's default long-press behavior for any QS tile is to open the system Settings app on the per-app details page — useless for a VPN tile, where the user almost certainly wants the app itself. `MainActivity` declares a second `<intent-filter>` for `android.service.quicksettings.action.QS_TILE_PREFERENCES` (with `android.intent.category.DEFAULT`) so the OS dispatches the long-press to MainActivity instead.

The OS sends an implicit `Intent(ACTION_QS_TILE_PREFERENCES).setPackage(packageName)` with no extras. `maybeAutoConnectFromTile` is gated on `EXTRA_TILE_AUTOCONNECT`, so the long-press path naturally does not trigger an autoconnect — MainActivity simply opens to whatever the user last saw (or fresh main screen). `singleTop` ensures a long-press while MainActivity is already foreground fires `onNewIntent`, not a recreate.

## Active-profile resolution

`pickOrPersistActive` is the only DB-touching code path on the tile click. Behavior:

1. Read `vpn_prefs.active_profile_id`. If present and the row still exists in `profiles`, return it. **No write.**
2. If the stored id is missing or refers to a deleted profile, call `ProfileDao.getFirst()` (`ORDER BY id ASC LIMIT 1`). Persist that id and return it.
3. If the table is empty, clear the stored id (if any) and return `null`. Caller toasts.

The "pick the lowest id" behavior is intentional and matches what the previous `dao.getAll().first().firstOrNull()` did before `getFirst()` existed. Manual and subscription-imported profiles are not distinguished — whichever exists at the lowest id wins.

This is the single piece of profile-resolution lore that lives in the repository rather than the tile, because `VpnViewModel.activeProfileId` binds to `ActiveProfileRepository.activeProfileIdFlow` via `stateIn` (so UI collectors see tile-initiated writes), and the VM's internal logic reads the authoritative id directly via `getActiveProfileId(getApplication())` — both paths see the same value regardless of who wrote it.

## TOCTOU on `VpnService.prepare`

The tile pre-flights `VpnService.prepare(ctx)` on the main thread before dispatching `ACTION_START`. The user can revoke VPN consent in the gap between that check and the service running. Without a defensive re-check, `Builder.establish()` would throw `SecurityException` and the user would see ERROR state in the tile **label** with no explanation.

[`XrayVpnService.startVpn`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt) re-checks `VpnService.prepare(this)` after `startForeground` (to satisfy the FGS contract before stopping) and, if non-null, posts an error notification via the existing `vpn_error_channel` using `vpn_permission_revoked_error` *and* calls `LogRepository.emitError(R.string.vpn_permission_revoked_error)` so the in-app surface sees the same reason. The notification's `contentIntent` opens `MainActivity` so the user can re-grant consent.

The shared `postErrorNotification(@StringRes messageRes)` helper is used by both this path and `postReviveErrorNotification` (kill-switch revive failures).

## Error propagation to the in-app UI

The tile dispatches directly to `XrayVpnService` and bypasses `VpnViewModel`, so tile-initiated failures used to be silent in the in-app UI — only the system error notification surfaced them, and only if `POST_NOTIFICATIONS` was granted. They now reach the VM via a `SharedFlow<Int>` of `@StringRes` ids.

`XrayVpnService` writes to `LogRepository.emitError(@StringRes resId)` at every site that calls `setConnectionState(VpnConnectionState.ERROR)`, plus `onRevoke`. The current paired strings:

- `vpn_permission_revoked_error` — `onRevoke` and the `VpnService.prepare()` defensive re-check.
- `vpn_start_failed_error` — generic startup-path failures: no profile id in `onStartCommand`, profile-not-found in `startVpn`'s Thread, `bringUpTunnel.onFailure`, outer Thread catch.
- `vpn_revive_error` — `reviveTunnel`'s profile-not-found, `reviveTunnel.onFailure`, and `killTunnel`'s teardown-failure catch. The wording ("VPN service stopped due to an unknown error") is intentionally generic so the same string covers any unexpected kill-switch failure that stops the VPN.

`VpnViewModel.init` launches two collectors on `viewModelScope`:

1. Mirrors `LogRepository.errorEvents` into `_error: StateFlow<String?>`, resolving each `resId` via `SupportedLanguage.localize(getApplication()).getString(resId)` so the message picks up the per-app locale at observation time.
2. Clears `_error` on every `connectionState == CONNECTING` transition (the "user — or tile — tried to start again" signal). This replaces the previous `_error.value = null` line in `VpnViewModel.connect`; clearing via the state-transition collector covers both in-app and tile-initiated retries uniformly.

`MainScreen` collects `vm.error` and renders the message inline. The `SharedFlow`'s replay=1 buffer means a user who opens the app *after* a tile-initiated failure sees the error on the next composition; a process restart drops the cache, which is acceptable because the user resumes at `DISCONNECTED` anyway.

## VM observation of `activeProfileId`

`VpnViewModel.activeProfileId` binds to `ActiveProfileRepository.activeProfileIdFlow` via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), getActiveProfileId(application))`. `MainScreen` collects this for the "active dot" rendering, so tile-initiated writes propagate to the UI without a process restart.

`WhileSubscribed(5_000)` keeps the upstream dormant unless a downstream collector exists, which is fine for the public `StateFlow` surface (the UI collects it) but **not** fine for the VM's own internal reads. `SubscriptionsActivity` has its own `VpnViewModel` instance and never collects `activeProfileId`, so internal reads of `activeProfileId.value` from that activity's VM would observe only the construction-time seed.

The VM therefore reads the authoritative id through `ActiveProfileRepository.getActiveProfileId(getApplication())` for every internal decision — `deleteProfile`, `deleteSubscription`, and the four `activeProfileIdProvider` lambdas passed to `SubscriptionRefreshCoordinator`. The repository's in-memory `MutableStateFlow` is the process-singleton source of truth and is not gated on Flow subscriptions. UI subscribers still read through `vm.activeProfileId.collectAsState()` as before.

## Locked-device handling

When the QS panel is pulled down from the lock screen, `TileService.isLocked` returns true. The tile uses `unlockAndRun { … }` to defer the runnable until after the user unlocks. There are two pieces of lore here:

- `unlockAndRun` only delays the runnable; it does not keep the device unlocked. By the time the runnable fires, the user has unlocked once, but the device can re-lock immediately afterward. This is why the start-path IO runs **outside** the `unlockAndRun` wrapper — minimizing the work that depends on the device staying unlocked.
- `startActivityAndCollapse` on a locked device behaves differently across API levels. Wrapping the dispatch in `unlockAndRun` consistently delivers the Activity launch after unlock.

## Known limitations

**`BLACKHOLED` is `STATE_ACTIVE`, and the Stop gate is duplicated.** [Auto-failover](auto-failover.md)
added `VpnConnectionState.BLACKHOLED` — the service is still running and still owns a TUN, so the tile
must stay a working **Stop** control, exactly like `PAUSED`. Mapping it to `STATE_INACTIVE` (as `ERROR`
is) would dispatch `ACTION_START` on tap, which `startVpn` no-ops with "VPN already running" — a dead
control in the state where the user most needs a live one. **Widening this enum needs a grep sweep, not
a green build:** the two exhaustive `when` sites are compiler-checked, but the Stop gate exists as a
plain boolean chain in *two* places — `decideTileClick` **and** `handleClick`'s no-IO fast path — plus
`sendStopIntent`'s comment. Adding the state to the render while missing the chains once left the tile
rendering active and behaving dead, which is strictly worse than looking dead.

**The tile offers Stop only in `BLACKHOLED` — the in-app "Reconnect" has no tile equivalent.** The
in-app connect affordance is now three-valued (`state/ConnectAction`) and renders **"Reconnect"** in
`BLACKHOLED`, routing through `state/ReconnectFlow`'s stop-then-start; see
[auto-failover.md](auto-failover.md#reconnect-the-affordance-a-give-up-actually-offers). A QS tile has
one binary action, so it cannot offer both Stop and Reconnect — Stop is the correct choice, since it
is the exit that always works and needs no server pick. **Consequence, and it is a real one:** a tile
Stop dispatched *while a Reconnect is in flight* overrides that reconnect for up to ~10 s, because
`ReconnectFlow` watches a source-blind state signal and reads the resulting `DISCONNECTED` as its own
teardown completing. `VpnViewModel.cancelReconnect()` covers the **in-app** Disconnect only; the tile
never reaches it. **If you touch `sendStopIntent` or the Stop gate, this is your marker.** Ledgered in
auto-failover.md's Known limitations and QA'd as Test 21.

**`ERROR` remains `STATE_INACTIVE`, and that is an accepted compromise.** `ERROR` now conflates two
situations: "session dying, VPN off" (where `Start` *is* the right tile action) and auto-failover's
`UNPROTECTED` give-up (service running but unprotected, where `Stop` would be). A tile has one action,
so it cannot serve both. Giving `UNPROTECTED` its own connection state was explicitly declined as too
much machinery for a state that is rare and bounded to one `rotationWindowMs` by the auto-stop. The
working exits from `UNPROTECTED` are the in-app **Disconnect** (the gate includes `ERROR`), the **Stop
action on the ongoing notification**, and Connect-from-`UNPROTECTED`, which restarts the session. **This
is a ledgered, accepted residual — do not "fix" the tile mapping without revisiting that decision.**

**`STATE_ACTIVE` during `CONNECTING`.** Quick Settings only has `STATE_ACTIVE` / `STATE_INACTIVE` / `STATE_UNAVAILABLE`. The current mapping marks `CONNECTING` as `STATE_ACTIVE` (consistent with the toggle semantics — a tap during CONNECTING means "stop"). A double-tap during the 1–3 s connect window will therefore fire `ACTION_STOP`. Considered acceptable because the alternative (`STATE_UNAVAILABLE`) makes the tile look broken to users in low-bandwidth conditions where CONNECTING is long-lived.

**Tile pre-flight permission check is racy with revocation.** Mitigated by the defensive `VpnService.prepare(this)` re-check at the top of `XrayVpnService.startVpn` (after `startForeground`, before `setConnectionState(CONNECTING)`), which posts an error notification on failure. When permission was revoked in the gap, the tile can jump **DISCONNECTED → ERROR** without ever showing CONNECTING — the defensive check runs before CONNECTING is published. A brief CONNECTING flash is still possible on other failure paths (e.g. profile lookup / tunnel bring-up) that occur after CONNECTING is set.

**No reboot recovery for the active-profile selection.** Matches the rest of the app — there is no `BOOT_COMPLETED` handler that pre-warms `ActiveProfileRepository`. First tile tap after a reboot re-reads SharedPreferences as usual; no behavior change is needed.

## Testing

**Instrumented:** [`ActiveProfileRepositoryTest`](../../app/src/androidTest/java/com/justme/xtls_core_proxy/state/ActiveProfileRepositoryTest.kt) covers the shared persistence layer end-to-end against an in-memory Room database — `setInstanceForTests` swaps the singleton in `@Before`, and the test closes the DB and unsets the override in `@After`. Coverage:

- Storage / sentinel behavior (`-1L` is treated as absent).
- `pickOrPersistActive` branches: empty DB, stale stored id, valid stored id, no stored id with multiple profiles.
- `getFirst()` contract: lowest id wins across all profiles.

[`VpnViewModelStateObservationTest`](../../app/src/androidTest/java/com/justme/xtls_core_proxy/state/VpnViewModelStateObservationTest.kt) covers the observation contract that makes tile-initiated changes visible to the in-app UI. Each test resets `ActiveProfileRepository` and `LogRepository` state in `@Before`/`@After`. Coverage:

- VM's `activeProfileId` reflects repository writes from a simulated tile path (the test calls `ActiveProfileRepository.setActiveProfileId` directly rather than going through `VpnViewModel.connect`). Uses `vm.activeProfileId.first { predicate }` to wait for the emission — a plain `.value` read would not force the `stateIn` upstream live, since `WhileSubscribed` requires a collector.
- VM's `error` reflects `LogRepository.emitError` emissions, localized against the application context, and clears on the next CONNECTING-state transition.

**Tile click decision** is covered by [`TileClickDecisionTest`](../../app/src/test/java/com/justme/xtls_core_proxy/tile/TileClickDecisionTest.kt), a fast JVM unit test against the pure `decideTileClick(state, profileId, needsVpnConsent, needsNotifPermission)` function in [`tile/TileClickDecision.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/tile/TileClickDecision.kt). The function lives outside `XrayVpnTileService` so the click pipeline can be exercised without the QS framework, a Context, or system services. Coverage: every `VpnConnectionState` value × profile-present-vs-absent × VPN-consent-needed-vs-not × notification-permission-missing-vs-not, plus two whole-enum guards — `everyLiveStateDecidesStop` (the live set pinned in one place) and `everyStateIsClassifiedExplicitly` (iterates `VpnConnectionState.entries`, so **widening the enum fails a test** instead of silently falling through to `Start`).

**What that test does *not* cover: `handleClick`'s own duplicate.** `handleClick` does **not** simply translate `decideTileClick`'s result — it re-states the Stop gate **by hand** for its no-IO fast path and returns early, reaching `decideTileClick` only on the start path. So the JVM test exercises the pure copy and *cannot see the duplicate*, which is precisely the copy that drifted and shipped a live-looking dead tile. `handleClick` is a `TileService` method, so no plain-JVM test can call it. **A new `VpnConnectionState` must be added to `handleClick` by hand; only a reader will catch it.** Extracting the shared gate would close this properly.

**Long-press intent-filter resolution** is covered by [`XrayVpnTileLongPressTest`](../../app/src/androidTest/java/com/justme/xtls_core_proxy/tile/XrayVpnTileLongPressTest.kt), an instrumented test that queries `PackageManager` for `android.service.quicksettings.action.QS_TILE_PREFERENCES` and asserts that exactly one activity in this package resolves it — `MainActivity`. Catches manifest regressions that would silently send long-press back to the system Settings page.

**Build verification:** `:app:assembleDebug` and `:app:assembleAndroidTest` must compile after any change touching this feature. The androidTest APK build is the practical "did the test changes compile" gate; the tests themselves require a device or emulator with the app installed.

## Future work

- **`STATE_UNAVAILABLE` during CONNECTING (optional UX).** Wait for a real complaint before doing this — current behavior is consistent with the in-app button.
- **Per-profile tile.** Android lets apps register multiple tiles; a "Connect to <last used> profile" variant would be useful for users with two regular servers. Not building until at least one user asks.
