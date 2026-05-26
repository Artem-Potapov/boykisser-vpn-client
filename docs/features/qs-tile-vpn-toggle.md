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
              │   / PAUSED                 │
              │   → STATE_ACTIVE           │
              └────────────────────────────┘

[User taps]
   │
   ├── if state ∈ {CONNECTING, CONNECTED, PAUSED}
   │       └── unlockAndRun { startForegroundService(ACTION_STOP) }
   │
   └── else (DISCONNECTED, ERROR)
           ├── IO: ActiveProfileRepository.pickOrPersistActive(ctx)
           │      └── null → toast "Please add a configuration", stop
           │
           └── Main + unlockAndRun:
                  ├── VpnService.prepare(ctx) != null  ─┐
                  ├── POST_NOTIFICATIONS missing ────────┤── launch MainActivity
                  │                                      │   with EXTRA_TILE_AUTOCONNECT
                  │                                      │   + EXTRA_TILE_PROFILE_ID
                  └── both OK → startForegroundService(ACTION_START + profileId)
```

The tile **observes** state but does not own it. `LogRepository.connectionState` is the canonical source, written by `XrayVpnService`; the tile collects it on `onStartListening` and re-renders. Tap intent is decided from `connectionState.value` at click time, not from any tile-private state.

## Components

The tile lives in [`app/src/main/java/com/justme/xtls_core_proxy/tile/`](../../app/src/main/java/com/justme/xtls_core_proxy/tile/). The persistence layer it shares with `VpnViewModel` lives in [`state/`](../../app/src/main/java/com/justme/xtls_core_proxy/state/).

| File | Responsibility |
|---|---|
| [`tile/XrayVpnTileService.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/tile/XrayVpnTileService.kt) | The `TileService` subclass. Observes `LogRepository.connectionState` on `onStartListening`, decides start/stop at click time, dispatches directly to `XrayVpnService` for the steady-state path, hands off to `MainActivity` when first-time permissions are still missing. |
| [`state/ActiveProfileRepository.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/ActiveProfileRepository.kt) | Shared owner of the `vpn_prefs.active_profile_id` SharedPreferences key. Read by both `VpnViewModel` (on construction) and the tile. Exposes `pickOrPersistActive(ctx)` which honors a valid stored id or falls back to `ProfileDao.getFirst()` when none is stored. |
| [`db/ProfileDao.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/db/ProfileDao.kt) | Source of `getFirst()` — `SELECT * FROM profiles ORDER BY id ASC LIMIT 1`. Used by `pickOrPersistActive` to pick a default when nothing is stored; covers manual and subscription-imported profiles alike. |
| [`MainActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/MainActivity.kt) — `maybeAutoConnectFromTile` | Consumes `EXTRA_TILE_AUTOCONNECT` + `EXTRA_TILE_PROFILE_ID`, single-shot strips both extras, runs the same permission dance the in-app Connect button does, then calls `viewModel.connect(...)`. Gated by `savedInstanceState == null` in `onCreate` so rotation / process-death recovery doesn't re-trigger. |
| [`res/drawable/ic_vpn_tile.xml`](../../app/src/main/res/drawable/ic_vpn_tile.xml) | 24dp vector padlock with `@android:color/white` fill. The QS framework state-tints it (active/inactive); using an explicit white fill is the standard pattern that lets the framework do that tinting. |
| [`res/values/strings.xml`](../../app/src/main/res/values/strings.xml) / [`values-ru/strings.xml`](../../app/src/main/res/values-ru/strings.xml) | `tile_label` ("VPN" in both locales), `tile_toast_no_profiles`, `vpn_permission_revoked_error`. |
| [`AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml) | `<service android:name=".tile.XrayVpnTileService"` with `android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"` and `<action android:name="android.service.quicksettings.action.QS_TILE"/>`. Plus `android:launchMode="singleTop"` on `MainActivity` so the activity handoff is deterministic. |

`XrayVpnService` itself ([`vpn/XrayVpnService.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt)) is shared with the in-app start path — the tile and `VpnViewModel.connect` both dispatch the same `ACTION_START` + `EXTRA_PROFILE_ID` intent. There is no tile-specific service code path.

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

The stop path needs no IO at all (profile id isn't required to stop), so it goes directly through `runOrDeferUnlock { sendStopIntent() }`.

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
- **API 30–33** — uses the deprecated `Intent` overload with an explicit `@Suppress("DEPRECATION")`. `minSdk = 30`, so the floor is fixed; both overloads must be supported for the lifetime of those API levels.

`MainActivity` is `android:launchMode="singleTop"`, so:

- If `MainActivity` is already foreground, `onNewIntent` fires with the tile extras. `setIntent(newIntent)` writes the new intent back so subsequent rotations don't replay the old one.
- If `MainActivity` is not the current top of its task, a fresh `onCreate` runs with `savedInstanceState == null`, and the tile extras are handled there.

`maybeAutoConnectFromTile` does both: takes a non-nullable `Intent`, reads the extras, **removes them with `removeExtra` so the next rotation / `onResume` doesn't re-trigger**, then runs the same `notificationPermissionLauncher` / `requestVpnPermissionAndConnect()` flow as the in-app Connect button. The `onCreate` call site is gated by `savedInstanceState == null` so the autoconnect only fires on a fresh launch, not on configuration-change recreate.

## Active-profile resolution

`pickOrPersistActive` is the only DB-touching code path on the tile click. Behavior:

1. Read `vpn_prefs.active_profile_id`. If present and the row still exists in `profiles`, return it. **No write.**
2. If the stored id is missing or refers to a deleted profile, call `ProfileDao.getFirst()` (`ORDER BY id ASC LIMIT 1`). Persist that id and return it.
3. If the table is empty, clear the stored id (if any) and return `null`. Caller toasts.

The "pick the lowest id" behavior is intentional and matches what the previous `dao.getAll().first().firstOrNull()` did before `getFirst()` existed. Manual and subscription-imported profiles are not distinguished — whichever exists at the lowest id wins.

This is the single piece of profile-resolution lore that lives in the repository rather than the tile, because `VpnViewModel`'s constructor uses the same `ActiveProfileRepository.getActiveProfileId(application)` read for its initial `_activeProfileId` StateFlow value.

## TOCTOU on `VpnService.prepare`

The tile pre-flights `VpnService.prepare(ctx)` on the main thread before dispatching `ACTION_START`. The user can revoke VPN consent in the gap between that check and the service running. Without a defensive re-check, `Builder.establish()` would throw `SecurityException` and the user would see ERROR state in the tile subtitle with no explanation.

[`XrayVpnService.startVpn`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt) re-checks `VpnService.prepare(this)` after `startForeground` (to satisfy the FGS contract before stopping) and, if non-null, posts an error notification via the existing `vpn_error_channel` using `vpn_permission_revoked_error`. The notification's `contentIntent` opens `MainActivity` so the user can re-grant consent.

The shared `postErrorNotification(@StringRes messageRes)` helper is used by both this path and `postReviveErrorNotification` (kill-switch revive failures).

## Locked-device handling

When the QS panel is pulled down from the lock screen, `TileService.isLocked` returns true. The tile uses `unlockAndRun { … }` to defer the runnable until after the user unlocks. There are two pieces of lore here:

- `unlockAndRun` only delays the runnable; it does not keep the device unlocked. By the time the runnable fires, the user has unlocked once, but the device can re-lock immediately afterward. This is why the start-path IO runs **outside** the `unlockAndRun` wrapper — minimizing the work that depends on the device staying unlocked.
- `startActivityAndCollapse` on a locked device behaves differently across API levels. Wrapping the dispatch in `unlockAndRun` consistently delivers the Activity launch after unlock.

## Known limitations

**VpnViewModel state staleness for tile-initiated changes.** `VpnViewModel._activeProfileId` is initialized once at VM construction from `ActiveProfileRepository.getActiveProfileId(application)` and is then only updated by the VM's own `connect` / `disconnect` / `deleteProfile` paths. The tile bypasses the VM, so a tile-initiated start with a different profile id, or any tile-initiated stop, does not propagate to the VM's StateFlow. The in-app UI can therefore display a stale "active dot" until process restart.

The follow-up direction is to make `ActiveProfileRepository` expose a `StateFlow<Long?>` (SharedPreferences-listener-backed) and have `VpnViewModel.activeProfileId` bind to it via `stateIn`. Same shape applies to `_error` — tile-initiated `XrayVpnService` failures surface on the error notification channel but never reach the VM's error StateFlow. Both items are marked with `TODO(qs-tile-followup)` breadcrumbs in [`VpnViewModel.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/VpnViewModel.kt) so they don't get lost. Until that lands, the tile is a write-only path from the UI's point of view; users who switch profiles via the tile see the change reflected only after relaunching the app.

**`STATE_ACTIVE` during `CONNECTING`.** Quick Settings only has `STATE_ACTIVE` / `STATE_INACTIVE` / `STATE_UNAVAILABLE`. The current mapping marks `CONNECTING` as `STATE_ACTIVE` (consistent with the toggle semantics — a tap during CONNECTING means "stop"). A double-tap during the 1–3 s connect window will therefore fire `ACTION_STOP`. Considered acceptable because the alternative (`STATE_UNAVAILABLE`) makes the tile look broken to users in low-bandwidth conditions where CONNECTING is long-lived.

**Tile pre-flight permission check is racy with revocation.** Mitigated by the defensive `VpnService.prepare(this)` re-check at the top of `XrayVpnService.startVpn`, which posts an error notification on failure. Still, the user sees a brief CONNECTING state before the error notification fires; no way to avoid this without making the tile slow.

**No reboot recovery for the active-profile selection.** Matches the rest of the app — there is no `BOOT_COMPLETED` handler that pre-warms `ActiveProfileRepository`. First tile tap after a reboot re-reads SharedPreferences as usual; no behavior change is needed.

## Testing

**Instrumented:** [`ActiveProfileRepositoryTest`](../../app/src/androidTest/java/com/justme/xtls_core_proxy/state/ActiveProfileRepositoryTest.kt) covers the shared persistence layer end-to-end against an in-memory Room database — `setInstanceForTests` swaps the singleton in `@Before`, and the test closes the DB and unsets the override in `@After`. Coverage:

- Storage / sentinel behavior (`-1L` is treated as absent).
- `pickOrPersistActive` branches: empty DB, stale stored id, valid stored id, no stored id with multiple profiles.
- `getFirst()` contract: lowest id wins across all profiles.

**No tile click-flow instrumented test yet.** The end-to-end "tap tile → MainActivity launches with the right extras" path is currently covered only by manual QA. Adding a `XrayVpnTileServiceTest` with a fake `ActiveProfileRepository` and an injected `LogRepository` state is in the future-work list below.

**Build verification:** `:app:assembleDebug` and `:app:assembleAndroidTest` must compile after any change touching this feature. The androidTest APK build is the practical "did the test changes compile" gate; the tests themselves require a device or emulator with the app installed.

## Future work

- **`VpnViewModel` observes `ActiveProfileRepository` as a Flow.** Closes the staleness loop described under *Known limitations*. Includes giving the VM an error surface that mirrors `XrayVpnService` failures so tile-initiated start errors show up in-app, not just as a notification. Tracked by the `TODO(qs-tile-followup)` breadcrumbs.
- **Instrumented test for the tile click pipeline.** Inject `ActiveProfileRepository` into the tile (or expose its DAO so tests can swap), drive `onClick` with each `LogRepository.connectionState` value, assert the correct `Intent` is dispatched.
- **`STATE_UNAVAILABLE` during CONNECTING (optional UX).** Wait for a real complaint before doing this — current behavior is consistent with the in-app button.
- **Per-profile tile.** Android lets apps register multiple tiles; a "Connect to <last used> profile" variant would be useful for users with two regular servers. Not building until at least one user asks.
