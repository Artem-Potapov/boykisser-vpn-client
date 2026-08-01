# Fail-Closed: `protect()`, Whole-App Tunneling & Resilient Startup (2A)

Maintainer reference for three changes that ship as one unit: socket-level loop-avoidance via
`VpnService.protect()`, tunneling the app's own traffic instead of excluding it, and a service that
resurrects itself after a crash / on always-on boot. Its application-layer counterpart is
[DNS-Leak Enforcement](dns-leak-enforcement.md) (2B); the two ship and reason together.

## Why this exists

The tunnel had two fail-**open** weaknesses:

1. **Loop-avoidance leaked the app's own traffic.** Loop-avoidance excluded the *whole app* from the
   tun (block mode disallowed the app's package; allow mode omitted it) so Xray's own packets to the
   proxy wouldn't loop. But that also pushed **all** the app's other traffic — subscription fetches
   over `HttpURLConnection`, update checks — outside the tun, in cleartext over the bare network.
2. **Nothing brought the tunnel back.** If Xray crashed (process dies, OS reclaims the tun fd) or the
   device rebooted under always-on, the service did nothing: `onStartCommand` returned
   `START_NOT_STICKY` and ignored starts it didn't initiate.

## The `protect()` mechanism

Loop-avoidance moves from app-level exclusion to **per-socket `VpnService.protect()`**, so Xray's own
sockets are carved out of the tun at the OS level and the whole app can ride the tunnel without looping.

- **Go side** ([`xray-go/xray_bridge.go`](../../xray-go/xray_bridge.go)) — a `Protector` interface
  (`Protect(fd int) bool`, implemented on the Android side) and `RegisterProtector(p) string`. The
  latter installs **one** Xray dial controller under `sync.Once` (load-bearing:
  `internet.RegisterDialerController` *appends* to a global slice, so installing per-connect would
  stack duplicate controllers), and on every dial reads the current swappable protector and calls
  `c.Control(fd → p.Protect(fd))`. Because the controller is global to the default dialer, it covers
  **every** Xray outbound socket — the proxy link **and** the 2B DoH resolver query. Returns `""` on
  success or the controller-install error string.
- **Kotlin facade** ([`bridge/XrayBridge.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/bridge/XrayBridge.kt))
  — `registerProtector(vpnService): Result<Unit>` reflectively locates `RegisterProtector` and passes a
  `java.lang.reflect.Proxy` implementing the gomobile-generated protector interface; the fd callback
  routes to `vpnService.protect(fd)`. It **throws if the returned status string is non-blank** (so
  `getOrThrow()` gates the connect on controller-install failure, not only on binding failure) and
  **logs to `LogRepository` when `protect(fd)` returns `false`**.

The protector reference is swapped on each start (last start wins), so the *current* `VpnService`
instance does the protecting; clearing it on stop is an unnecessary nicety.

## Whole-app tunneling

With Xray's sockets carved out by `protect()`, the app itself is placed *inside* the tunnel — its own
non-Xray traffic routes through the proxy, and only `protect()`'d sockets bypass. Self-exclusion is
removed in both split-tunnel modes. A pure
[`split/SplitTunnelPlanner.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/split/SplitTunnelPlanner.kt)
computes the allowed/disallowed package sets so the asymmetric self-handling is unit-testable: **block
mode no longer disallows self; allow mode adds self** (without duplicating it if the user already
selected it).

## Resilient startup

`onStartCommand` is driven by a pure, **total** decision
([`vpn/StartCommandDecision.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/StartCommandDecision.kt),
mirroring the existing `TileClickDecision`):

| Incoming action | Decision | Service effect |
| --- | --- | --- |
| `ACTION_START` + valid profile id | `StartProfile(id)` | `startVpn(id)` — see "`startVpn` while already running" below |
| `ACTION_START` + sentinel id | `RefuseNoProfile` | error state + `stopSelf()` (pre-existing) |
| `ACTION_STOP` | `Stop` | `tunnelOpScope.launch { stopVpn() }` — user-stop is marshalled OFF the main thread (see note below) |
| `ACTION_NOTIFICATION_DISMISSED` | `RepostNotification` | `if (running)` marshal `repostOngoingNotification()` onto `tunnelOpScope` (serializes behind in-flight kill/revive writers on the same notification id) `else stopSelf()`; when state is **PAUSED**, restores both the quiet FGS line and the separate exposed heads-up |
| `null` / anything else | `StartActiveProfile` | resolve active profile, then start |

`onStartCommand` returns **`START_REDELIVER_INTENT`**, so an OS kill / process crash re-delivers the
last `ACTION_START` (profile id included) and reconnects the same profile. The decision is **total** —
every concrete action is enumerated — specifically so the Android 14+ `ACTION_NOTIFICATION_DISMISSED`
start doesn't fall through a catch-all `else` into a spurious auto-connect.

### `startVpn` while already running: idempotent, with one narrow exception

"Start while running" is **idempotent by design** — the tile, `START_REDELIVER_INTENT` crash recovery
and stray/duplicated intents all depend on it, so a general restart would let a stray intent bounce a
perfectly healthy tunnel. [Auto-failover](auto-failover.md) added two things to that early-return arm:

- **An active-profile rollback.** Every connect path (per-server rows, the profile-actions dialog, the
  QS tile, always-on, connect-to-fastest) records the requested profile as active **before**
  dispatching the start. When the start is refused, traffic keeps flowing through the session's real
  `currentProfileId` while the UI and the tile would go on labelling the *requested* profile as
  connected — the one fact a VPN client must never get wrong. The pure
  `activeProfileIdToRestoreOnRefusedStart(requested, current)` (in `SessionLifecycleDecision.kt`)
  returns the id to write back, or `null` when the request already matches the running session or no
  real session profile exists (the field carries `-1L` when unset; Room never issues a non-positive
  id). `setActiveProfileId` uses `apply()`, so no disk I/O is held under `lock`.
- **A recovery restart, for exactly one state.** `shouldRestartForRecovery(running, giveUpOutcome)` is
  true **only** for auto-failover's `UNPROTECTED` give-up — the service is running but owns no tunnel
  and is protecting nothing, so the early return would swallow the user's only in-app recovery. It
  tears the dead session down with `stopVpn(stopService = false)` (keeping this service instance and
  its foreground promotion alive) and falls through to the normal start path for a fresh epoch. This
  arm deliberately does **not** roll the active profile back — it really does go on to start the
  requested profile. **That `stopVpn` runs on the main thread; the constraint that keeps it safe is
  documented in [auto-failover.md](auto-failover.md) ("RISK 1") — nothing that awaits may ever be added
  to `stopVpn`.**

### User-stop runs OFF the main thread

`bringUpTunnel` holds the lifecycle `lock` across the blocking `XrayBridge.startXray` (seconds, with
geo-file loading). The **user-stop** path (`ACTION_STOP` → `stopVpn()`) previously ran that blocking
teardown — and contended for the same lock — **on the main thread**, so a Disconnect during a connect
could freeze the UI or ANR (single lock, so no deadlock — a stall). It is now marshalled onto
`tunnelOpScope` (`limitedParallelism(1)`), which serializes it behind any in-flight kill/revive.
`stopVpn()` is still called with **no expected epoch**, so it tears down whatever session is current;
a stop that lands mid-start still reliably stops the tunnel — either it wins the lock and tears down
the just-published resources, or the start commits `CONNECTED` first and the epoch-agnostic stop then
tears that down. Moving the work changes the **thread, not the locking**: `stopVpn` still flips
`running`/epoch and sets `STOPPED` under `lock`.

**`onDestroy` and `onRevoke` keep the SYNCHRONOUS `stopVpn()`** — those must complete teardown inline
(the system is destroying the service / revoking permission), so they are deliberately not marshalled.

The screen-state `BroadcastReceiver` (screen-off/on polling pause — shared by the kill-switch monitor
**and** auto-failover's health monitor) no longer takes the full lock on the main thread either: it reads a `@Volatile` epoch mirror (`activeEpochVolatile`, authored
only under `lock` alongside `activeSessionEpoch`) to cheaply reject a stale session, then enqueues the
actual `pausePolling()`/`resumePolling()` onto `tunnelOpScope`, where the monitor field is read and the
session re-checked under `lock` — race-free, just off the main thread.

## Components

| File | Change |
|---|---|
| [`xray-go/xray_bridge.go`](../../xray-go/xray_bridge.go) | `Protector` interface, `RegisterProtector` with `sync.Once` dial-controller install, error surfaced as a status string. |
| [`bridge/XrayBridge.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/bridge/XrayBridge.kt) | `registerProtector(vpnService): Result<Unit>` (reflection + dynamic `Proxy`); throws on non-blank status, logs `protect(fd)==false`. |
| [`vpn/XrayVpnService.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt) | Register protector before `startXray` in the shared connect/revive path; use `SplitTunnelPlanner`; `onStartCommand` via `StartCommandDecision` returning `START_REDELIVER_INTENT`; `resolveActiveAndStart()` promotes to foreground **synchronously** before the async active-profile resolution; `buildNotification` sets `FOREGROUND_SERVICE_IMMEDIATE` (Android 12+ otherwise defers the FGS notification up to ~10 s — `specialUse` is not deferral-exempt). |
| [`split/SplitTunnelPlanner.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/split/SplitTunnelPlanner.kt) | Pure `plan(mode, userPackages: Set<String>, selfPackage): SplitTunnelPlan`. |
| [`vpn/StartCommandDecision.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/StartCommandDecision.kt) | Pure sealed decision + `decide(action: String?, profileId: Long)`. |

**No R8 change** — the reverse-binding interface is covered by the existing `-keep class xraybridge.**`
and the runtime `Proxy` needs no keep. **No new persisted state, no manifest change, no
`BOOT_COMPLETED` receiver** — the service is already a `specialUse` FGS with the `android.net.VpnService`
intent-filter, which is all the always-on / boot mechanism needs.

## Error handling

- **`registerProtector` must succeed before `startXray`.** With self-exclusion gone, a missing
  protector means Xray's proxy socket would route into the tun and loop, so a failure **aborts the
  connect** (ERROR state + teardown) rather than starting an unprotected, looping core. The guard
  covers both the **binding layer** (reflection / `Proxy` rejected) and the **controller-install
  layer** (`RegisterDialerController` returned an error → non-blank status string).
- **Per-socket `protect()` failure** does not abort the in-flight dial (it can't usefully fail one),
  but the facade logs the `false` to `LogRepository` so a regression is visible rather than silent.
- **FGS startForeground deadline.** A system-initiated always-on / boot start arrives with
  `startForegroundService` semantics — the service must `startForeground` within ~5 s or be killed.
  Because active-profile resolution (`pickOrPersistActive`) is an async Room read that can be slow on
  cold boot, `resolveActiveAndStart()` promotes to foreground **before** launching that coroutine, and
  tears it down (`stopForeground` + `stopSelf`) if no profile resolves.
- **`START_REDELIVER_INTENT` `startId` contract.** `ACTION_START`'s `startId` is never consumed while
  running, so the intent stays pending and redelivers on crash. `ACTION_STOP` → `stopVpn()` →
  **no-arg** `stopSelf()` clears *all* pending intents, so an explicit stop never self-restarts — those
  no-arg `stopSelf()` calls are a property to **preserve**. (Caveat: with **Always-on VPN enabled** the
  system may still re-start the service via the always-on mechanism — a null-intent `StartActiveProfile`,
  not a redelivery. That's by design and outside 2A's control.)
- **Crash leak window.** A process crash inherently exposes the bare network for the gap between death
  and the redelivered restart; only **lockdown** (a system setting, out of scope) seals that.
  `START_REDELIVER_INTENT` gives auto-recovery, not a sealed gap.
- **Kill-on-foreground × restart.** If the OS kills the service while it is *paused* by
  [kill-on-foreground](kill-on-foreground.md), redelivery reconnects the tun and the monitor re-pauses
  if the controlled app is still foreground — it self-heals via the normal `startVpn → bringUpTunnel`
  success path. The in-process `reviveTunnel()` path uses the same `bringUpTunnel()` helper, so it
  also re-registers the protector (not only a cold `startVpn` restart).

## Known limitations

- **Crash leak gap** (above) — auto-recovery, not a sealed gap; lockdown is the only seal and is a
  separate concern.
- **Custom-dialer configs aren't protected.** `RegisterDialerController` only takes effect with Xray's
  default system dialer. This app produces standard VLESS, Hysteria2, `freedom`, `blackhole`, and `dns`
  outbounds through Xray's default dialer; a config installing a custom dialer is not a path this app
  produces. **Hysteria2 note:** it rides QUIC/UDP, so whether its sockets go through the default dialer
  (and are therefore `protect()`'d out of the tun) was the key runtime unknown. It is now **confirmed
  on-device** — a release build against a real Hysteria2 server egresses through the proxy with no
  dial-to-self loop, which is only possible if those QUIC/UDP sockets are `protect()`'d. Re-verify if the
  bridge or xray-core's dialer changes. See [hysteria2-support.md](hysteria2-support.md).

## Testing

- **[`SplitTunnelPlannerTest`](../../app/src/test/java/com/justme/xtls_core_proxy/split/SplitTunnelPlannerTest.kt)**
  — pure JVM: block mode doesn't disallow self; allow mode always includes self (no duplicate); lists
  otherwise the user's selection.
- **[`StartCommandDecisionTest`](../../app/src/test/java/com/justme/xtls_core_proxy/vpn/StartCommandDecisionTest.kt)**
  — pure JVM covering **each decision outcome** (one test per sealed branch), incl. the
  `ACTION_NOTIFICATION_DISMISSED → RepostNotification` branch that guards the dismissal-misrouting
  defect. The `else → StartActiveProfile` path is exercised for `null` action only; unknown action
  strings share that branch but are not separately asserted.
- **`protect()` mechanism** — no pure unit test is feasible (Go + gomobile + JNI; the `.aar` isn't
  unit-testable from Kotlin). **Justified TDD exception.** Verified by building the `.aar` + app, a
  `javap` inspection of the generated `Protector` interface, and the on-device proof below.
- **On-device** — (1) **Connect:** traffic flows, perceived public IP is the proxy's, a subscription
  refresh egresses via the proxy (not direct), logs show no dial-to-self loop — *with self-exclusion
  removed, connecting at all is the proof `protect()` carries the loop*. (2) **Always-on:** enable
  Always-on VPN, reboot, VPN comes up on the active profile. (3) **Crash:**
  `adb shell am kill com.justme.xtls_core_proxy` (a low-memory-style kill, not force-stop) → service
  restarts and reconnects. (4) **Explicit stop (always-on OFF):** stop from app/tile → stays down.

## Coordination with auto-failover

[Auto-failover](auto-failover.md) depends on both halves of this document, and extends the fail-closed
posture to a case 2A did not cover.

- **Whole-app tunneling is what makes the health probe valid.** Because self-exclusion is gone and only
  `protect()`'d Xray sockets bypass, auto-failover's plain-Kotlin `HttpURLConnection` 204 probe travels
  `tun → xray → proxy → internet` — the exact path user traffic takes. It deliberately does *not* use
  `XrayBridge.measureLatency`, whose throwaway instance is `protect()`'d **out** of the tun and would
  therefore answer "can this config reach that server", not "is the live tunnel passing traffic".
  **Reverting whole-app tunneling would silently make the watchdog meaningless** — it would start
  measuring the clear network and would never fire. Treat the two as coupled.
- **The give-up posture is a fail-closed guarantee of the same family as the ones above.** 2A's
  `START_REDELIVER_INTENT` gives auto-recovery for a *crash*; auto-failover has to answer the different
  question of what happens when the tunnel is alive but no server works. "Leave the dead tunnel up"
  does not hold — the all-servers-dead path tears the TUN down before the final bring-up fails, so
  whether the user is exposed would depend on *where* bring-up died. So the service re-establishes a
  **blackhole TUN** (same routes, addresses, DNS servers and split-tunnel plan; no protector, no Xray)
  and packets are dropped into an fd nobody reads. When even that fails, the state is reported honestly
  as unprotected rather than with containment copy, and a second consecutive failure stops the service.
  See [auto-failover.md](auto-failover.md).

## Coordination with 2B

2A's global dial controller `protect()`s every socket Xray dials — **including** 2B's DoH query and the
proxy bootstrap dial — so 2B's secure-DNS block is reachable on the bypass path with no per-socket
coordination. And 2A dropping self-exclusion is precisely what makes 2B load-bearing: the bootstrap
query and any port-53→direct egress now ride `protect()`'d sockets onto the LAN unless 2B routes them
through DoH / `dns-out`. **The controller must stay global** — never register it in a narrower,
per-socket way that would miss the DNS module's socket.

## Out of scope

- **Lockdown** — a system setting the app can't toggle programmatically; separate brief.
- **Reboot recovery without always-on** — there is no `BOOT_COMPLETED` receiver; resilient startup
  tracks the *service* lifecycle and the always-on mechanism, not raw boot.
