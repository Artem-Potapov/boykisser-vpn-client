# Auto-Failover: Tunnel Health Watchdog, Rotation Engine & Fail-Closed Give-Up

Maintainer reference for auto-failover: a health watchdog that probes the **live tunnel**, an engine
that rotates to a sibling server when it stops passing traffic, and a give-up posture that is
fail-**closed** by construction rather than by luck. Connect-to-fastest (the manual counterpart that
reuses the same pool and the same ping machinery) is documented here for the failover-side halves and
in [`profile-actions-menu.md`](profile-actions-menu.md) for the menu/UI half.

Read together with [`failclosed-startup.md`](failclosed-startup.md) (whole-app tunneling is what makes
the probe valid), [`kill-on-foreground.md`](kill-on-foreground.md) (the two features share one screen
receiver and one tunnel), and [`ping-test.md`](ping-test.md) (the probe target and the coordinator).

> Everything in this doc is off by default. `FailoverPreferences.DEFAULT.enabled = false`.

## Why this exists

A VLESS/REALITY or Hysteria2 server can stop passing traffic without the tunnel noticing: the TUN fd
is still open, Xray-core is still running, `ConnectivityManager` still reports a VPN, and the app
still says **Connected**. The user sees pages that never load and has to work out for themselves that
the server died and that another one in the same subscription would work.

Auto-failover closes that loop. It also has to answer a second, harder question, which is where most
of the design effort went: **what should happen when nothing works?** The naive answer ("leave the
tunnel up") does not hold on the path that actually matters, and getting it wrong means silently
dropping the user onto the clear network — the exact failure mode a censorship-circumvention client
must never have.

## The health probe: a Kotlin HTTP 204 through the live tunnel

[`failover/Http204HealthProbe.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/Http204HealthProbe.kt)
issues a plain `HttpURLConnection` GET against the ping-test target and requires HTTP **204**.

**It is deliberately NOT `XrayBridge.measureLatency`.** `MeasureLatency` builds a *throwaway*
`core.Instance` whose sockets are `protect()`'d **out** of the tun by 2A's global dial controller (see
[`ping-test.md`](ping-test.md) and [`failclosed-startup.md`](failclosed-startup.md)). It therefore
answers "can this config reach that server", **not** "is the live tunnel passing traffic" — and those
two diverge in precisely the failure mode this feature targets.

What makes the plain Kotlin request the right probe is 2A's other half: `SplitTunnelPlanner` keeps
**this app inside the tunnel in both split modes** (only `protect()`'d Xray sockets bypass). So the
probe's packets travel `tun → xray → proxy → internet`, the exact path user traffic takes. **If
whole-app tunneling is ever reverted, this probe silently becomes meaningless** — it would start
measuring the clear network and would never fire. Treat the two as coupled.

That coupling is also load-bearing in the other direction: it is what structurally guarantees a probe
through a **blackhole** TUN (an fd nobody reads) can never succeed, which is why
`clearGiveUpStateOnRecovery` can trust a healthy probe (see below).

## The monitor loop

[`failover/TunnelHealthMonitor.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/TunnelHealthMonitor.kt)
polls on a fixed cadence and fires `onUnhealthy` after `failureThreshold` **consecutive** failures.
Four properties are non-obvious and each one exists because of a defect found in review:

- **Offline guard.** Before every probe it asks
  [`NetworkAvailability`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/NetworkAvailability.kt)
  whether a **non-VPN** network with `NET_CAPABILITY_INTERNET` exists. If not, the tick is skipped
  **and `consecutiveFailures` is reset to 0**. Without this, airplane mode or lost signal would make
  every probe fail and the engine would thrash through the entire server list blaming servers for the
  phone being offline. The reset (not merely a `continue`) matters: returning signal must not trip an
  instant rotation off a stale count.
- **Terminal after firing.** `isStarted = false` and `job = null` are written **before**
  `listener.invoke()`, so the monitor is fully terminal for that `start()` call. Both pause/resume
  orderings were broken before this: if the fire landed first, `resumePolling()` relaunched into a
  loop carrying `reportedUnhealthy = true`, which then polled **forever while permanently deaf**.
  (During the fix round this manifested as a *hung* test rather than a failing one — the deaf loop
  never terminates, so `runTest`'s cleanup never reached an idle scheduler.) Only a fresh `start()`
  revives it — which is exactly what `applyFailoverPreferences` does after a rotation.
- **Nothing thrown escapes.** The monitor's scope has **no** `CoroutineExceptionHandler`, so an escaping
  throw would reach the default handler and kill the process while the VPN is up. Both launch sites
  wrap `runPollLoop()` in `try/catch(Throwable)`, the availability check and the probe are each guarded
  per-tick (a throwing availability check is treated as "offline", not as a server failure), and both
  listener invocations are guarded too. `CancellationException` is rethrown at every one of those sites
  — structured concurrency must still work.
- **`resumePolling()` probes immediately** (via `firstTick`) rather than waiting a full interval, so
  picking the phone back up recovers fast at zero idle cost. `pausePolling()` deliberately **preserves**
  `consecutiveFailures` across a screen-off; `stop()` clears it.

`onHealthy` fires **at most once** per `start()`, on the first successful probe. It exists because the
give-up state would otherwise be able to outlive the condition it describes (see
`clearGiveUpStateOnRecovery`). Note its **parameter order**: `start(onHealthy = null, onUnhealthy)` —
`onHealthy` is declared first *specifically* so the mandatory `onUnhealthy` stays last and existing
trailing-lambda callers keep binding to it. Swapping them silently re-binds every such caller to the
optional listener.

## Rotation: the state machine

Rotation is a third tunnel transition alongside the kill-switch's kill and revive, and it keeps their
locking discipline exactly: reserve the transition under `lock` **before** any async work, re-check
ownership before every mutation, route every escape through one fail path.

```
                        monitor fires onUnhealthy
[CONNECTED] ──canReserveRotation (CONNECTED only)──> [ROTATING]
     ▲                                                    │
     │                                    tearDownTunnelLocked()
     │                              + announce CONNECTING / "Switching…"
     │                                                    │
     │                                          bringUpTunnel(next)
     │                                             │            │
     └────── success: CONNECTED, 1104 notice ──────┘            │
     │       active profile advances, monitor restarted         │
     │                                                          │
     └────── failure: episodeFailedIds += next.id,      ────────┘
             tear down the half-built fd,
             currentProfileId rolled back,
             recurse into rotateTunnel (still under the thrash cap)

thrash cap Denied ──┐
no candidate left ──┼──> giveUpRotationLocked()   (the ONE funnel; see below)
rotation error   ───┘
```

Key properties:

- **`ROTATING` is its own `SessionTunnelState`**, deliberately not shared with `REVIVING`. Rotation
  reserves from `CONNECTED`; revive reserves from `PAUSED`. Sharing one state would let a kill-switch
  revive and a failover rotation each believe they own the same transition.
- **The gap is ANNOUNCED.** From `tearDownTunnelLocked()` until `establish()` there is **no VPN
  interface at all** — `bringUpTunnel` does `buildRuntimeConfig`, geo-asset prep and the split read
  off-lock first. Leaving the UI, the ongoing notification and the QS tile saying CONNECTED through
  that window would claim protection the user does not have, so the same locked block that tears down
  publishes `CONNECTING` + `vpn_status_switching`. The teardown-before-bring-up ordering itself is
  forced by `bringUpTunnel`'s `check(tunInterface == null)` and is **not** what changed — only the
  silence was. Every exit re-announces: success → `CONNECTED`, retry → `CONNECTING` again, give-up →
  `BLACKHOLED`/`ERROR`.
- **A failed candidate's fd is torn down.** `bringUpTunnel` can fail *after* `establish()` (e.g.
  `startXray` threw), leaving a real fd with an indeterminate Xray behind it. The failure arm drops it
  so `tunInterface != null` keeps its single downstream meaning: *the live, still-proxying tunnel*.
  Without this, a give-up would mistake that half-built fd for a working tunnel.
- **`currentProfileId` is rolled back on failure.** It is what `reviveTunnel` brings up; leaving it on
  a server just proved dead would make a kill-switch revive fail and `failRevive` → `stopVpn` take the
  whole tunnel down. It also keeps the field in step with `ActiveProfileRepository`, which only
  advances on success.
- **`episodeFailedIds` is episode-scoped**, cleared on a successful rotation and on every give-up, so
  a server that failed an hour ago is not skipped forever.
- **The thrash cap is a sliding window.** `FailoverDecision.admitRotation` prunes attempts older than
  `rotationWindowMs` before counting, so a burst long ago cannot permanently lock failover out.
- **Candidate order is list order, not latency order** (`FailoverDecision.nextCandidate`). Deliberate
  for v1: ping results live in `VpnViewModel` and are unreachable from the service, and for failover
  "any working server" beats "the fastest server" — a poor pick simply rotates again. Latency ordering
  needs a process-scoped ping repository (deferred).
- **The pool comes from `FailoverPoolResolver`** — the manual (`subscriptionId == null`) partition or
  the current profile's whole subscription, resolved fresh from the DAO and never persisted (Room
  profile ids churn: `replaceProfilesForSubscription` deletes and re-inserts on every refresh). That
  object's KDoc marks it the **Spec 2 seam** for user-curated pools. Connect-to-fastest resolves through
  the same object on purpose — an independently-derived second pool would diverge with no compile-time
  signal.

## Coexistence with the kill-switch

The two features share one tunnel, one screen receiver, and — in `ROTATING`/`PAUSED` — one set of
mutually-exclusive claims on the fd. The rules:

| Situation | Behaviour | Why |
|---|---|---|
| Kill-switch event arrives during `ROTATING` | **Deferred**, not dropped (`shouldDeferKillDuringTransition`), recorded in `pendingKillLabel`, and replayed if the rotation commits `CONNECTED`. If the rotation instead **gives up**, the funnel **drops** it and says so — see below | The foreground monitor is edge-triggered and would never re-fire the event, leaving the tunnel CONNECTED with a kill-listed app foregrounded |
| Tunnel is `PAUSED` (kill-switch) | The health monitor is **stopped**, not paused (`shouldRunFailoverMonitor` is `CONNECTED`-only) | There is no tunnel, so every probe would fail and the engine would "rotate" a tunnel the kill-switch deliberately tore down. `stop()` (not `pausePolling()`) because pausing preserves the failure count, which would trip instantly on revive |
| Revive commits `CONNECTED` | `reviveTunnel` calls `applyFailoverPreferences` **outside** the locked block | Nothing else restarts the monitor; without this, failover is dead for the rest of the session after the first kill-switch pause. It must run after `CONNECTED` is committed or it reads `REVIVING` and no-ops |
| Give-up lands while the state is not `CONNECTED` | Stand down: log, re-arm the monitor timer, touch nothing | `PAUSED`'s compliance contract is literally "no tunnel must exist"; establishing a blackhole (or overwriting the PAUSED connection state) would break it outright |
| Screen off / on | One shared `BroadcastReceiver` pauses/resumes **both** monitors | Registration used to belong entirely to the kill-switch, which failed failover two ways: with failover on and the kill-switch off (the **default** pairing) no receiver existed at all, and turning the kill-switch off mid-session tore the receiver out from under a running failover monitor. `shouldHoldScreenReceiver(killSwitchLive, failoverLive)` now owns it — hold while EITHER is live, release only when NEITHER is |

### The third exit: a give-up must DISCHARGE the deferred kill

A rotation has **three** exits, not two. `reviveTunnel`/`rotateTunnel`'s success arms replay
`pendingKillLabel`; the give-up funnel is the third and it must **drop** it. Leaving the marker armed
was a real merge blocker: the kill never fires, and then the re-arm rotation — up to
`rotationWindowMs` (default **10 min**, max 1 h) later — replays it, tearing down a just-restored
tunnel, setting `PAUSED`, and posting the 1103 "VPN is OFF — you're exposed" alert naming an app that
closed long ago. The user loses protection they never asked to lose, for a stated reason that is false.

So `giveUpRotationLocked` clears `pendingKillLabel` **at the top of the funnel** (one home, so no exit
— including the stand-down early return — can leave it armed) and then **notifies**: a bare silent
drop would leave the user with a silently non-functioning kill-switch. Replaying the kill on the two
contained outcomes was rejected for the same reason the deferral exists: if the app has already left
the foreground, the edge-triggered monitor has already fired, so the replay would strand the session
`PAUSED` with an exposure alert and no revive.

The notice is `VpnNotifications.postKillSwitchNotApplied` — **id 1106 on the existing
`EXPOSED_CHANNEL_ID`**. Ids and channels are independent: a new *id* is mandatory (channels are welded
at first post, so reusing 1103 would replace the exposure alert), while the kill-switch's
high-importance exposure channel is already the semantically right home for "your kill-switch did not
act". Whether it posts at all is the pure `deferredKillNoticeLabel(pendingKillLabel, tunnelStillUp)`:
nothing was deferred → silent; **no tunnel remains** (`UNPROTECTED`, and the give-up that stops the
service) → silent, because there the listed app genuinely is *not* behind a VPN and both of those
outcomes already report themselves on their own surfaces. Both contained outcomes still own an fd —
live or blackhole — and a blackhole TUN is still a VPN interface to the app that was trying to detect
one, so the notice is true there.

## Give-up: three outcomes, one funnel, fail-closed

`giveUpRotationLocked` is the **single funnel every give-up passes through**. The thrash-cap and
no-candidate paths call it directly and never go through `failRotation`, so anything wired only into
`failRotation` (the re-arm timer, in particular) would leave the common "all servers dead" case
permanently disarmed.

### Why "just keep the tunnel up" is not fail-closed

The spec originally said give-up should keep the dead tunnel established so traffic is never exposed.
**That does not hold on the path that matters.** The all-servers-dead path tears the TUN down *before*
the final bring-up fails, so a give-up can land with `tunInterface == null` and hand the user's traffic
straight back to the clear network — and whether that happened would depend on *where* bring-up died
(after `establish()` = contained, before = exposed). A posture that is fail-closed-if-you-are-lucky is
worse than either consistent answer.

So when the session should own a tunnel and has none, `establishBlackholeTunnelLocked()` re-establishes
a **blackhole TUN**: same session name, MTU, addresses, default routes, DNS servers and split-tunnel
plan as a real bring-up, but **no protector registration and no Xray**. Packets enter an fd nobody
reads and are dropped. Capture parity with `bringUpTunnel` is exact and load-bearing — in particular
`addDnsServer` is set **and** both default routes send the resolver's own address into the unread fd,
so the system resolver, an app's own DoH/DoT to hardcoded IPs, and Private DNS strict mode all time
out rather than leaking. Apps the user split **out** keep the direct route they already had while
connected; everything else keeps riding the tun, now into the blackhole.

`check(tunInterface == null)` sits **inside** the `try` on purpose: this method is reached from
`rotateTunnel`'s `catch (Throwable)`, and a throw raised inside a catch block escapes that try/catch
entirely — landing uncaught on a `SupervisorJob` with no handler, i.e. process death with the VPN up. A
contract violation must degrade to "uncontained", never to a crash.

### The three outcomes

`classifyGiveUpOutcome(hadTunnel, blackholeEstablished)` (pure, in `SessionLifecycleDecision.kt`).
`hadTunnel` wins outright — if an fd was already owned we never tried to blackhole, so
`blackholeEstablished` carries no information in that case.

| Outcome | Physical situation | Connection state | Ongoing line (1101) | Alert (1105) | Stops the service? |
|---|---|---|---|---|---|
| `CONTAINED_BY_LIVE_TUNNEL` | The current server's tunnel is **still up and still proxying**; there was simply nowhere to rotate to (no-candidate / thrash-cap, both of which run before any teardown) | `BLACKHOLED` | `vpn_status_no_response` | `postFailoverNoResponse` | never |
| `CONTAINED_BY_BLACKHOLE` | No tunnel existed; a blackhole was established. Traffic is deliberately dropped | `BLACKHOLED` | `vpn_status_blackholed` | `postFailoverBlackholed` | never |
| `UNPROTECTED` | No tunnel **and** the blackhole could not be established. The user **is** on the clear network | `ERROR` + `LogRepository.emitError` | `vpn_status_unprotected` | `postFailoverUnprotected` | on the **second** consecutive one (see below) |

**These must never share one message.** An earlier revision fired the blackhole copy unconditionally,
so the 1105 body asserted "nothing leaks to the open network" in the exact case where everything leaks,
and the "no candidate" case told a user with a perfectly working tunnel that their traffic was blocked.
The `UNPROTECTED` variant must never inherit the reassuring containment copy — that would tell a user
their traffic is safe at the exact moment it is not. That is also why `UNPROTECTED` is the one outcome
that additionally goes through `LogRepository.emitError`: the Logs screen is not where a user learns
their traffic just went clear.

### `BLACKHOLED` is a friendly facade, on purpose

`VpnConnectionState.BLACKHOLED` was added rather than reusing `ERROR`, because `ERROR` maps to a dead
session on every surface (tile → `STATE_INACTIVE`, no Disconnect) while a blackholed session is very
much **alive and holding a TUN**. The enum constant is technical because only developers read it;
**every user-visible string mapped from it is deliberately plain and non-alarming**:

- `main_state_blackholed` — "No server connection"
- `vpn_status_blackholed` — "No server connection — paused to keep you protected"
- `failover_blackhole_title/body` — "No server connection" / "…your connection is paused on purpose.
  That keeps your real location private instead of quietly going unprotected."

No jargon ("blackhole", "traffic blocked") appears in any user-visible string, in either locale. The
Russian copy carries the same three facts and the same register — do not "improve" it toward the
technical wording.

**Widening this enum needs a grep sweep, not a green build.** The two `when` sites over
`VpnConnectionState` are exhaustive and the compiler flags them, but the enum is *also* read by plain
boolean equality chains the compiler cannot flag. Adding `BLACKHOLED` to the renders while missing the
chains left the QS tile rendering `STATE_ACTIVE` while `decideTileClick` still returned `Start` —
strictly worse than before, because the control now *looked* live and was not. The sites that must all
agree today:

| Site | Rule |
|---|---|
| `tile/TileClickDecision.decideTileClick` | `BLACKHOLED` → `Stop` |
| `tile/XrayVpnTileService.handleClick` | the same Stop gate, duplicated for the no-IO fast path |
| `tile/XrayVpnTileService` render | `BLACKHOLED` → `STATE_ACTIVE`, like `PAUSED` |
| `state/VpnViewModel.canConnect` | `BLACKHOLED` is **not** connectable |
| `MainActivity` Disconnect gate | `BLACKHOLED` **and** `ERROR` both show Disconnect |
| `XrayVpnService.repostOngoingNotification` | `BLACKHOLED` restores 1101 only (1105 is `setAutoCancel` — re-posting it would fight a deliberate dismissal); `ERROR` restores 1101 **only when** `giveUpOutcome == UNPROTECTED` |

Three deliberate **non**-changes: `MainActivity.isConnecting`, `VpnViewModel`'s
`filter { CONNECTING }` error gate (widening it would erase the very error the user needs), and
`XrayVpnService`'s `wasPaused` check (`BLACKHOLED` is not the kill-switch's paused state, and
`reviveTunnel` would no-op at `canReserveRevive` anyway).

## "Disconnect now, stop if the re-arm fails"

An `UNPROTECTED` give-up used to leave a long-lived `ERROR` with the service **running** and no working
stop control on any surface, while its own copy instructed the user to "turn the VPN off and on again".
The ruling that closed it has two halves, and a third mechanism that had to be *created* rather than
preserved:

1. **Disconnect works immediately.** `MainActivity`'s Disconnect gate includes `ERROR`, and the ongoing
   FGS notification (1101) carries a **Stop action** — the one surface present in every running state,
   including when the app UI is closed.
2. **`retryByRotation`: the re-arm actually retries.** This is the subtle part.
   `scheduleFailoverRearmLocked` normally re-arms the **health monitor** after `rotationWindowMs`. Out
   of `UNPROTECTED` **that can never produce a rotation**: there is no tunnel, so the probe travels the
   **clear network**, succeeds for the wrong reason, fires `onHealthy`, and
   `clearGiveUpStateOnRecovery` early-returns on `tunInterface == null`; `onUnhealthy` can never fire
   because probes never fail. The recovery was therefore *nonexistent*, not merely fragile. The
   `retryByRotation` flag makes the timer call `rotateTunnel` **directly** for `UNPROTECTED` only; both
   contained outcomes keep re-arming the monitor as before, because they still have a tunnel for a
   probe to test.
3. **A second uncontained give-up stops the service.** `shouldStopServiceOnGiveUp(outcome,
   unprotectedRetryConsumed)` — only `UNPROTECTED`, and only once its single automatic recovery attempt
   has been spent. The first `UNPROTECTED` must **not** stop: forfeiting the re-arm would switch the
   VPN off without the user asking. It emits on **both** surfaces (`emitError` for a user who is
   looking, the 1102 error notification — its own id, survives `stopForeground` — for one who is not),
   then `stopVpn`. An honest off state beats a service that is running, protecting nothing, and telling
   the user to reconnect.

`unprotectedRetryConsumed` is cleared exactly where `giveUpOutcome` is: a successful rotation, a
successful revive, the recovery callback, and full teardown — i.e. the events where *a tunnel
demonstrably worked*. **Two carry-over cases are known and ACCEPTED**, both because they err towards
stopping a service that cannot protect anything:

- a retry whose give-up classifies `CONTAINED_BY_BLACKHOLE` leaves the flag set (traffic is contained,
  so nothing clears it), so a later `UNPROTECTED` stops immediately with no retry of its own;
- a kill-switch pause landing before the timer fires makes `rotateTunnel` bail at `canReserveRotation`,
  silently spending the retry without attempting anything.

**The retry timer is gated twice.** `applyFailoverPreferences` cancels and nulls `failoverRearmJob`
when the user disables failover — gated on `!settings.enabled` **alone**, deliberately *not* on
`shouldRunFailoverMonitor`, which is also false in `PAUSED`/`ROTATING`: folding the cancel into it
would drop a legitimate pending retry on an unrelated settings save during a kill-switch pause. The
backstop is `shouldFireFailoverRetry(failoverEnabled, isCurrentSession)`, re-read from the flow at the
firing point, because the timer is scheduled under one set of preferences and fires up to an hour
later. Without both, a user who turned auto-failover **off** could still be handed an automatic server
rotation — and, on the unprotected retry path, an automatic VPN shutdown.

### Connect from `UNPROTECTED` restarts the session

`shouldRestartForRecovery(running, giveUpOutcome)` is deliberately narrow: **only** `UNPROTECTED`
bypasses `startVpn`'s "VPN already running" early return. Everything else keeps the early return,
because the tile, `START_REDELIVER_INTENT` crash recovery and stray/duplicated intents all depend on
"start while running" being idempotent — a general restart would let a stray intent bounce a perfectly
healthy tunnel. The two contained outcomes are excluded for the same reason: they still hold a TUN, so
there is nothing for a restart to rescue.

The restart calls `stopVpn(stopService = false)` and **falls through** to the normal start path for a
fresh epoch. `stopService = false` keeps this service instance alive: a real `stopSelf()` there would
schedule our own destruction and `onDestroy` would tear down the session we are about to start, and
skipping `stopForeground` keeps the FGS promotion continuous across the restart instead of dropping and
re-taking it.

> ### ⚠️ RISK 1 — a documented constraint on `stopVpn`
>
> That `stopVpn` call runs **on the main thread**, inside the held admission block of `onStartCommand`.
> It is safe today for two reasons and **only** those two:
>
> 1. **`UNPROTECTED` implies a stopped core.** `StopXray` returns immediately when `instance == nil`
>    (`xray_bridge.go`), and Go's `mu` is taken only by `StartXray`/`StopXray`, both called exclusively
>    under the Kotlin `lock` — so the Go mutex adds no contention beyond the lock the caller already
>    holds.
> 2. **Nothing in `stopVpn` awaits.** Dispatching the teardown to `tunnelOpScope` and awaiting it would
>    **deadlock** (those ops take `lock` themselves, and `startVpn` is not suspendable), so "marshal
>    only the teardown" is unavailable without restructuring `onStartCommand`.
>
> **Therefore: never add anything that awaits to `stopVpn`, and never widen `shouldRestartForRecovery`
> to a state that can imply a running core.** Either change turns a fast no-op into a real
> `instance.Close()` + fd close on the main thread. (The pre-existing `synchronized(lock)` in
> `onStartCommand` *already* blocks the main thread for a full rotation's `bringUpTunnel`, so this is
> an increment on an existing risk, not a new class of it — but the constraint is what keeps it an
> increment.)

## Recovery: clearing a stale give-up

`clearGiveUpStateOnRecovery` is driven by `TunnelHealthMonitor.onHealthy`. Without it, the
no-candidate and thrash-cap give-ups — which can both land on a tunnel merely having a bad minute —
would leave the user staring at an error state over a **working** connection until they stopped and
restarted the VPN by hand, because the monitor only ever reported *failure*.

Its guard set is complete and each clause is load-bearing:

- current session (`isCurrentSessionLocked`),
- a give-up is actually showing (`giveUpOutcome != null`),
- `sessionTunnelState == CONNECTED`,
- **`tunInterface != null`** — after an `UNPROTECTED` give-up there is no tunnel, so the probe travels
  the clear network and succeeds for the wrong reason. Clearing on that would announce `CONNECTED` with
  no VPN at all. `UNPROTECTED` has no automatic *state* recovery by design; it needs the user action the
  notification and the in-app error both ask for.

The `CONTAINED_BY_BLACKHOLE` case is closed **structurally**, not by a guard: `SplitTunnelPlanner` puts
this app inside the tunnel in both modes and `Http204HealthProbe` uses an unprotected
`HttpURLConnection`, so a probe through an unread fd cannot succeed.

## Live settings, and why the monitor is rebuilt rather than mutated

`applyFailoverPreferences` is the single reconciliation point, mirroring `applyKillSwitchPreferences`
including its stale-epoch discipline (a superseded epoch returns **without touching the monitor**, so a
late emission from an already-cancelled observer can never stop the current session's monitor).

`TunnelHealthMonitor`/`Http204HealthProbe` bake interval, timeout and threshold in at **construction**,
so a timing edit can only land by rebuilding. `failoverMonitorNeedsRebuild(builtFrom, next)` decides:
only those three fields qualify (`enabled` is handled by `shouldRunFailoverMonitor`; `maxRotations` and
`rotationWindowMs` are read fresh at rotation time). **An unchanged emission MUST return false** — the
settings `StateFlow` re-emits on every save, and rebuilding on each one would restart the poll cycle
continuously so the tunnel is never actually observed.

The `failoverMonitor != null` early return is what stops the observer stacking duplicate monitors. The
fix for a stale post-rotation monitor is to **clear the field** (`stopFailoverMonitorLocked()` inside
`rotateTunnel`'s reservation), **never** to drop that guard: a fired monitor is already terminal but
the field still holds it, so without the clear the post-rotation re-apply would early-return on a
non-null field and failover would arm exactly **once per session**.

**The seeded `FailoverPreferences.load(...)` in `startVpn` is load-bearing, not defensive.**
`FailoverPreferences.state` is a process-global `MutableStateFlow(DEFAULT)`; an observer-only wiring
would receive `enabled = false` and never arm failover on any path where the settings Activity never
ran in this process — process death, always-on restart, a first-launch QS-tile connect. Both that load
and `KillSwitchRepository.load` touch SharedPreferences and so stay **outside** the lifecycle lock.

## Settings, defaults, and the `timeout < interval` invariant

Reached from Settings → **Auto-failover** (`settings/SettingsHubActivity` → `failover_title`), the
screen is [`failover/FailoverSettingsActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FailoverSettingsActivity.kt).
It follows the hub's **per-control autosave** house style (see [`settings-hub.md`](settings-hub.md)) —
no Save button, each control persists on change, and an invalid field holds its own last-good
**persisted** value without vetoing the rest of the tuple.

| Setting | Default | Bounds | Notes |
|---|---|---|---|
| `enabled` | **off** | — | The only field that takes effect mid-session without a rebuild |
| `probeIntervalMs` | 15 000 | 5 000 – 300 000 | |
| `probeTimeoutMs` | 5 000 | 1 000 – (interval − 1 000) | The cross-field rule; see below |
| `failureThreshold` | 2 | 1 – 10 | Consecutive failures before rotating |
| `maxRotations` | 3 | 1 – 10 | Sliding-window thrash cap |
| `rotationWindowMs` | 600 000 | 60 000 – 3 600 000 | **No UI control** — carried through from the stored tuple. Also the re-arm delay |

The probe **target URL** is not a failover setting: it is deliberately shared with
`PingPreferences.targetUrl` (read fresh in `applyFailoverPreferences`), so editing it under
Diagnostics → Ping test also changes what the health watchdog probes. One target, one place to change
it — but a maintainer editing either screen should know the coupling exists.

### The cross-field rule, and the lesson it taught

`FailoverPreferences.coerce` clamps every field into bounds and **then** enforces
`probeTimeout ≤ interval − TIMEOUT_HEADROOM_MS` (floored at `TIMEOUT_MIN`). The pair rule runs **last**
so it sees already-clamped values and cannot be undone by a later clamp.

The UI must derive the ceiling **exactly the way `coerce()` derives it**, not restate it. It briefly
did not — the accept test was `timeout < interval`, so at interval 10 000 a typed 9 500 showed **no
error**, was accepted, and was then silently rewritten to 9 000 by `save()`. A ~999 ms silent-rewrite
window at every interval value, while the screen's own error string already stated the correct rule.
Both `resolveFailoverSettings` and the activity's display validity now compute
`(i − TIMEOUT_HEADROOM_MS).coerceAtLeast(TIMEOUT_MIN)` from the **effective** (post-fallback) interval,
so there is one rule rather than two, and `coerce()` remains the final backstop rather than the
enforcement point.

**The fix exposed that an existing unit test had encoded the bug** — it asserted 19 500 accepted at
fallback interval 20 000, whose real ceiling is 19 000. That is the argument, in one line, for
*deriving* shared bounds instead of restating them: ten tests written against a restated rule described
the defect instead of catching it.

Display validity falls back to `lastPersisted` (re-read via `FailoverPreferences.load` **after** each
save, so it reflects the post-`coerce` stored value), not the screen-open-time `initial`, so what the
screen shows and what `persist()` writes share one source of truth.

## Connect to fastest

The manual counterpart: long-press a server → **Connect to fastest** probes that profile's pool through
the existing, **unmodified** `PingCoordinator` and connects to whichever answered fastest. The menu/UI
half is documented in [`profile-actions-menu.md`](profile-actions-menu.md); the parts that belong here:

- **Same pool as auto-failover.** `FastestConnectRunner.resolvePool` is backed by
  `FailoverPoolResolver.resolve` — not a separately-derived view of the on-screen group. An earlier
  revision had a second copy of the rule in `VpnViewModel`; when curated pools land, that copy would
  have let auto-failover rotate within the curated pool while Connect-to-fastest kept probing the whole
  subscription, diverging with **no compile-time signal**. It was deleted, not synchronised.
- **The winner is re-gated TWICE — production side and consumption side — and both are needed.** The
  run can last minutes (`timeout × ceil(n / concurrency)`), and the winner can then sit **unconsumed
  indefinitely** because the Compose frame clock pauses below `STARTED`.
  - *Production-side* (`FastestConnectRunner`): `canConnect` is a **closure**, evaluated immediately
    before `_winnerId` is set, so it cannot be a stale captured value by construction. This bounds the
    probe's own window.
  - *Consumption-side* (`MainActivity`'s `LaunchedEffect(fastestWinnerId)`): re-checks `canConnect(state)`
    against the live collected state right before `onConnect(winnerId)`; on failure it calls
    `discardFastestWinner()` (consume **and** report `STATE_CHANGED` — never silent). Both branches
    consume, so a winner can never re-fire.
  - Without the second check: start a run, background the app, connect via the QS tile, return →
    `onConnect` fires, `connect()` no-ops with "VPN already running" while `ActiveProfileRepository`'s
    active id is overwritten to the winner, and **the UI names server B while traffic flows through
    server A**. Two checks around one unbounded gap are correct, not redundant.
- **The service now rolls back too (T10b).** `activeProfileIdToRestoreOnRefusedStart(requested, current)`
  restores the session's real profile in `startVpn`'s "VPN already running" arm, closing
  "UI names B while traffic flows through A" for **every** connect path, not just this one. The
  `UNPROTECTED` recovery restart deliberately does **not** roll back — it really does go on to start the
  requested profile, so it must keep the caller's write. `setActiveProfileId` uses `apply()`, so no disk
  I/O is held under `lock`.
- **The ViewModel never calls `connect()` itself.** Every other Connect action routes through
  `MainActivity`'s permission-checked flow (notification permission, then `VpnService.prepare()`
  consent). Surfacing the winner as ViewModel state and letting Compose consume it through the same
  `onConnect` keeps that invariant and survives an Activity recreation mid-probe.

## Components

| File | Responsibility |
|---|---|
| [`failover/FailoverPreferences.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FailoverPreferences.kt) | `FailoverSettings` model + `xray_prefs` persistence; `coerce` (bounds, then the `timeout < interval` pair rule); process-wide `state: StateFlow` mirroring `KillSwitchRepository.state`. |
| [`failover/HealthProbe.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/HealthProbe.kt) | One-call seam: `suspend fun isHealthy(): Boolean`. |
| [`failover/Http204HealthProbe.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/Http204HealthProbe.kt) | The concrete probe — plain `HttpURLConnection` GET requiring 204, through the tun. Injectable `opener` for tests. |
| [`failover/NetworkAvailability.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/NetworkAvailability.kt) | "Is there a non-VPN internet transport?" — the offline guard. `AndroidNetworkAvailability` is defensive (fail-open on a missing service or any throw). |
| [`failover/TunnelHealthMonitor.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/TunnelHealthMonitor.kt) | The poll loop: offline guard, consecutive-failure counting, terminal-after-fire, once-per-start `onHealthy`, pause/resume for screen off/on. |
| [`failover/FailoverDecision.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FailoverDecision.kt) | Pure: `nextCandidate(pool, currentId, recentlyFailed)` and `admitRotation(attempts, now, maxRotations, windowMs)` → `RotationAdmission`. No clock, no Android. |
| [`failover/FailoverPoolResolver.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FailoverPoolResolver.kt) | `resolve(dao, current)` — manual partition or the profile's subscription. **Spec 2 seam** for curated pools; shared with Connect-to-fastest. |
| [`failover/FailoverSettingsActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FailoverSettingsActivity.kt) | The settings screen; per-control autosave, display validity derived the same way `coerce()` derives it. `FAILOVER_ENABLED_SWITCH_TAG` for the instrumented test. |
| [`failover/FailoverSettingsPersistDecision.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FailoverSettingsPersistDecision.kt) | Pure `resolveFailoverSettings(...)` — the autosave rule extracted out of the Activity so it is JVM-testable (the codebase's `TileClickDecision`/`StartCommandDecision` shape). |
| [`failover/FastestConnectRunner.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FastestConnectRunner.kt) | Framework-free Connect-to-fastest orchestration: generation-counter job replacement, delivery-time re-gate, `FastestConnectOutcome` (NO_RESPONSE / BUSY / STATE_CHANGED), cancellation cleanup. |
| [`failover/FastestPick.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FastestPick.kt) | Pure `pickFastest(states, candidates)` and `clearStaleTesting(states, ids)`. |
| [`vpn/SessionLifecycleDecision.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleDecision.kt) | All the pure service-side rules: `SessionTunnelState.ROTATING`, `canReserveRotation`, `shouldDeferKillDuringTransition`, `shouldHoldScreenReceiver`, `shouldRunFailoverMonitor`, `failoverMonitorNeedsRebuild`, `shouldEstablishBlackholeTunnel`, `FailoverGiveUpOutcome` + `classifyGiveUpOutcome`, `shouldStopServiceOnGiveUp`, `shouldFireFailoverRetry`, `shouldRestartForRecovery`, `activeProfileIdToRestoreOnRefusedStart`, `deferredKillNoticeLabel`. |
| [`vpn/XrayVpnService.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt) | The wiring: `applyFailoverPreferences`, `rotateTunnel`, `giveUpRotationLocked`, `establishBlackholeTunnelLocked`, `clearGiveUpStateOnRecovery`, `scheduleFailoverRearmLocked`, `reconcileScreenReceiverLocked`, the 1101 Stop action, and the recovery restart in `startVpn`. |
| [`vpn/VpnNotifications.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/VpnNotifications.kt) | Channels 4 and 5 and ids 1104/1105; `postFailover`, the three `postFailover*` give-up variants (shared id + `postGiveUp` builder), `cancelFailoverBlackholed`. Also id 1106 / `postKillSwitchNotApplied` — a new id on the kill-switch's **existing** exposure channel. |
| [`log/LogRepository.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/log/LogRepository.kt) | `VpnConnectionState.BLACKHOLED`. |
| `res/values/strings.xml`, `res/values-ru/strings.xml` | All failover strings, both locales (release lint fails on a missing one). |
| `AndroidManifest.xml` | `ACCESS_NETWORK_STATE` (required — `cm.allNetworks` throws `SecurityException` without it) and the `FailoverSettingsActivity` entry (`exported="false"`, like every sibling settings screen). |

**No R8 change.** Nothing in `failover/` is reached by reflection or JNI; the ping path it borrows is
already covered by the existing `-keep class xraybridge.**`. `assembleRelease` is green with no R8
warning about the package. That proves the pipeline builds — it does **not** prove the reflection/JNI
bridge paths survive obfuscation; see the QA matrix.

## Known limitations and accepted trade-offs

Everything here was found, reasoned about, and **deliberately kept**. Please read before "fixing" one.

- **`cm.allNetworks` is deprecated and is used anyway.** The replacement, `activeNetwork`, returns the
  **VPN's own network** while the tunnel is up — precisely the transport this check must exclude.
  Switching to it would silently *invert* the guard. The deprecation surfaces as a kotlinc warning
  (also in the release compile) and is left unsuppressed on purpose so it stays visible. **Do not
  "fix" it.**
- **The probe timeout does not bound a hung DNS.** `withTimeoutOrNull` wraps a *blocking* `runProbe()`,
  so the timeout only lands after the blocking call returns. Not a leak — the poll loop is sequential,
  ticks cannot overlap — detection is just slower. Accepted for v1. `PingCoordinator.probeWithBackstop`
  is the in-repo pattern if this ever needs real bounding.
- **`TunnelHealthMonitor` has no constructor validation.** `failureThreshold <= 0` fires on the first
  failure (`>=` comparison); `intervalMs <= 0` hot-spins the loop. Both are unreachable through
  `FailoverPreferences.coerce`, which is the only production source.
- **Disabling auto-failover while a give-up is showing** stops the monitor, so
  `clearGiveUpStateOnRecovery` can never fire — `BLACKHOLED` and the blackhole TUN persist until a
  manual disconnect. Fail-closed and Disconnect works, so non-blocking.
- **The kill-switch can pause a blackholed session**, leaving 1105 ("paused to keep you protected")
  showing next to the kill-switch's 1103 ("VPN is OFF — you're exposed"). A contradictory pair;
  `giveUpOutcome` is not cleared by `killTunnel`.
- **`giveUpOutcome` survives into `PAUSED`.** No surface can dispatch a start from `PAUSED` today, but
  **any future start affordance in `PAUSED` would bring a tunnel up under a kill-switch pause** via the
  recovery-restart path. If you add one, clear `giveUpOutcome` in `killTunnel` first.
- **`rotateTunnel` has no stale-callback guard**, unlike `killTunnel` (which drops a queued event whose
  feature was disabled while it sat on `tunnelOpScope`). A monitor callback already queued when the user
  disables failover still performs one rotation — a one-dispatch window, versus the up-to-an-hour timer
  window that *is* closed. **A naive port of `killTunnel`'s guard would break the `UNPROTECTED` retry
  path**, which legitimately rotates with `failoverMonitor == null`: the check must be on `enabled`, not
  on the monitor.
- **The single automatic recovery rotation excludes the last-known-good server**, because
  `nextCandidate` filters out `currentId` and `currentProfileId` was rolled back to it.
- **`UNPROTECTED` can briefly coexist with a live tunnel and a running core** — `bringUpTunnel` releases
  `lock` after `establish()` + `startXray()`, and the rotation's `.onSuccess` only clears
  `giveUpOutcome` after re-acquiring it. A start intent queued on the main thread could win the lock in
  between. Unreachable from the UI (during a rotation the state is `CONNECTING`, so `canConnect` is
  false and the tile returns `Stop`); it needs a duplicated/queued intent. The outcome is still correct
  — epoch discipline no-ops the rotation's `.onSuccess`.
- **`shouldEstablishBlackholeTunnel` is redundant at its only call site** (its state arm is dead behind
  the stand-down above it), and 3 of its 4 tests cover unreachable branches — coverage that looks
  stronger than it is.
- **`stopVpn`'s `!shouldStop && tunInterface == null` early return skips `failoverRearmJob?.cancel()`**,
  so an up-to-an-hour timer can idle past it. Harmless: the job re-checks `isCurrentSessionLocked`.
- **The blackhole builder omits `bringUpTunnel`'s "allow-only mode with no selected apps" warning** —
  the one diagnostic difference between the two builders.
- **`FastestConnectRunner`'s BUSY-vs-NO_RESPONSE is a heuristic**, read from a `pingStates` snapshot
  rather than from `PingCoordinator`'s internal state (modifying the coordinator was out of scope). A
  false `NO_RESPONSE` is effectively unreachable; a false `BUSY` is reachable but both messages end in
  "try again", so a wrong label misexplains without misleading into a wrong action.
- **`PingCoordinator`'s cross-run dedup can hand `pickFastest` a stale `Success`** from an earlier run,
  producing a winner chosen from non-fresh data with no message. See [`ping-test.md`](ping-test.md).
> **Note on `failover_hint`.** An earlier draft of this string read "the tunnel is kept up so your
> traffic is never exposed" — true for both *contained* outcomes but **false for `UNPROTECTED`**,
> where `establish()` itself failed. It was rewritten (commit `4091571`) to promise a pause rather
> than a guarantee, and to say the app will tell you when even that is impossible. Keep any future
> edit to this string honest about `UNPROTECTED`: the give-up notifications are per-outcome for
> exactly this reason, and a settings hint that contradicts them is the same defect one screen over.

## Deferred: extract a `FailoverEngine` behind a `TunnelHost` seam

**This is a real recommendation that was deferred to a follow-up spec, not a rejected one.**

`XrayVpnService` is now ~1,670 lines carrying **three** tunnel operations (kill, revive, rotate), two
monitors sharing one receiver, and a give-up funnel. Every Critical and Important finding in this
feature's review lived in the **sequencing** — announce-before-teardown, teardown-of-a-half-built-fd,
profile-id rollback, outcome classification, give-up ordering — and none of it is reachable from a JVM
test today: the pure predicates in `SessionLifecycleDecision.kt` guard the *rules*, but the *order in
which the service calls them* is only proven on hardware.

The recommendation is to extract a `vpn/FailoverEngine` behind a narrow `TunnelHost` seam
(`reserve` / `tearDown` / `bringUp` / `establishBlackhole` / `notify`) so that sequencing becomes
testable off-device.

**Why it was deferred, in the maintainer's words:** mixing a large refactor of human-review-gated code
into a *safety fix round* makes both harder to review, and it would have invalidated the review that
had just found those bugs. It is a follow-up spec, and the size of the "Known limitations" list above
is itself the argument for doing it.

## Testing

**JVM unit tests** (`:app:testDebugUnitTest`) — `app/src/test/java/com/justme/xtls_core_proxy/`:

| Test class | Coverage |
|---|---|
| `failover/FailoverPreferencesTest` (8) | Defaults; every bound clamps on load **and** save; the `timeout < interval` pair rule; `load`/`save` I/O against mocked `SharedPreferences` (the `KillSwitchRepositoryTest` precedent), with `save` pinning the **coerced** value per key via `eq()`, not a bare `any()`. |
| `failover/Http204HealthProbeTest` (5) | 204 → healthy; non-204/throwing opener → false, never a throw; `CancellationException` **propagates** rather than being swallowed. |
| `failover/TunnelHealthMonitorTest` (15) | Threshold counting; the offline guard skips the tick **and** resets the counter; a throwing availability check is treated as offline; terminal-after-fire across both pause/resume orderings; a throwing listener does not kill the loop; tick continuation. |
| `failover/FailoverDecisionTest` (7) | `nextCandidate` skips the current id and episode failures; `admitRotation` admits under the cap, denies at it, and slides the window. |
| `failover/FailoverPoolResolverDispatchTest` (4) | Manual profile → `getManualList`, subscription profile → `getBySubscriptionId`; a fake DAO records calls and throws `UnsupportedOperationException` on any unexpected method, so a wrong dispatch fails loudly. |
| `failover/FailoverSettingsPersistDecisionTest` (12) | Per-control autosave: an invalid field never vetoes the tuple; the timeout ceiling is derived from the **effective** interval; the exact headroom boundary (9 000 at interval 10 000) is accepted and the coerce-gap value (9 500) is rejected. |
| `failover/FastestPickTest` (4), `failover/ClearStaleTestingTest` (3) | Lowest successful latency wins / nothing succeeded → null; stale-`Testing` reset scoped to the run's own ids. |
| `failover/FastestConnectRunnerTest` (7) | Sequencing against a **real** `PingCoordinator` under `kotlinx-coroutines-test`: supersede (disjoint **and** identical pools), cancel-resets-`Testing`, the delivery-time re-gate discards + reports `STATE_CHANGED`, connectable winner is delivered, `NO_RESPONSE` vs `BUSY`. |
| `vpn/SessionLifecycleDecisionTest` (42) | Every pure service rule above, including stale-epoch × `{REVIVING, ROTATING}` and `running = false` × `{REVIVING, ROTATING}` for the transition-defer guard. |
| `vpn/SessionLifecycleRotationTest` (8) | Rotation reservation, the give-up predicates, and `deferredKillNoticeLabel` (names the app while a tunnel remains; silent with nothing deferred and silent with no tunnel left). |
| `vpn/FailoverNotificationIdsTest` (3) | All five ids and three channel ids are **mutually distinct** — the JVM-runnable (therefore CI-runnable) guard against the welded-channel regression. |
| `tile/TileClickDecisionTest` | `BLACKHOLED` → `Stop`, with and without a profile. |

**Instrumented tests** (`:app:connectedDebugAndroidTest`, local only — not in CI):

- `failover/FailoverPoolResolverTest` (2) — the real Room `@Query` SQL for both pool shapes.
- `failover/FailoverSettingsPersistTest` (1) — `invalidInterval_doesNotVetoEnableToggle`, i.e. the
  `MuxSettingsActivity` whole-save-veto bug proven **absent**.
- `vpn/FailoverNotificationTest` (6) — including `failoverChannelIsDefaultImportance` and
  `failoverBlackholeChannelIsHighImportance`.

**Status:** all three classes above were previously never executed and now **pass on hardware**
(Samsung SM-S918B / Galaxy S23 Ultra, Android 15, arm64-v8a). Full instrumented suite: **57/57**.

> Note the instrumented notification tests post 1104 and 1105 and never cancel them, and
> `gradle.properties` keeps the app installed after a run — so a hardware run can leave a live
> HIGH-importance "no server connection" alert on the device while traffic is fine. An `@After`
> cancelling both ids would fix it. Also, `cancelFailoverBlackholed_clearsTheAlert` can pass
> **vacuously** (no `GrantPermissionRule`, no assert-posted-before-cancel), so with
> `POST_NOTIFICATIONS` denied the `notify` no-ops and the assertion holds without exercising the
> cancel at all.

**Manual on-device QA:** [`docs/qa/auto-failover-qa.md`](../qa/auto-failover-qa.md). The give-up
posture, the notification/tile controls in each state, the full "disconnect now, stop if the re-arm
fails" lifecycle, and the release-APK bridge check are all hardware-only and live there.
