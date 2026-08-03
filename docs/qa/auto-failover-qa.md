# QA — Auto-Failover (manual on-device)

**Build under test:** branch `feat/auto-failover-core`, HEAD `ea60354` (versionName `2.3.0R`,
versionCode 4). Reinstall with `./gradlew :app:installDebug`.

**What auto-failover does (the behaviour you're verifying):** while connected, the app probes the
**live tunnel** with an HTTP 204 request through the tun. After `failureThreshold` consecutive
failures it rotates to another server in the same pool (the same subscription, or the "My profiles"
partition). When it runs out of servers it **gives up fail-closed** — it re-establishes a *blackhole*
TUN (an fd nobody reads) rather than releasing traffic to the clear network — and reports which of
three physically different situations it left behind. Feature reference:
[`docs/features/auto-failover.md`](../features/auto-failover.md).

**Default state is OFF.** Everything below assumes you turn it on at Settings → Auto-failover first.

---

## Already verified — do NOT re-run these

Recorded so you don't spend hardware time on them.

| Item | Status |
|---|---|
| `:app:assembleRelease` | **PASS** (6m07s). R8 minify + shrinkResources, `lintVitalRelease`, release signing all green. `mapping.txt` is 38 MB, so R8 *did* obfuscate. One deliberate compile warning: `NetworkAvailability.kt:26 allNetworks is deprecated` — see Test 12, do **not** "fix" it. |
| `:app:testDebugUnitTest` | **PASS** — 603 tests, 0 failures. |
| `:app:lintDebug` | **PASS** — 0 errors, 56 warnings, 0 `MissingTranslation`. |
| Full instrumented suite | **57/57 PASS** on SM-S918B / Android 15 / arm64-v8a. |
| The three previously-never-executed classes | `FailoverPoolResolverTest` (2), `FailoverSettingsPersistTest` (1), `FailoverNotificationTest` (6) — all **PASS**, including both channel-importance assertions and `invalidInterval_doesNotVetoEnableToggle`. |
| Settings hub → Auto-failover navigation | **PASS** on device — the row renders in the Auto section beside "Kill on foreground" and lands on `.failover.FailoverSettingsActivity`. (A direct `am start` is **refused** with "not exported" — that is correct, `android:exported="false"` matches every sibling settings screen. Do not "fix" it.) |
| T1 defaults reaching the T9 screen | **PASS** — screen opens showing interval 15000, timeout 5000, threshold 2, max switches 3. |
| Timeout-ceiling validation, both directions | **PASS** on device — interval 10000 + timeout **9500** shows the error; interval 10000 + timeout **9000** (the exact ceiling) does not. |
| Per-control autosave | **PASS** on device via `shared_prefs/xray_prefs.xml` — editing interval and timeout persisted both without vetoing the untouched fields, and an invalid value fell back to last-good rather than being stored. |

> Two device-state notes from that session: animation scales may still be at `0/0/0` — restore to
> `1.0/1.0/1.0` when you are done with instrumented runs. The device was left with
> `failover_probe_interval_ms=10000` / `failover_probe_timeout_ms=9000` persisted; reset via the
> screen if you want clean defaults.

---

## Setup for everything below

- **Two or more servers in the SAME subscription**, at least one of which you can kill on demand.
  "Kill on demand" options, easiest first:
  - stop the proxy process on your own VPS (`systemctl stop xray`),
  - or point a scratch profile at a **reachable host with no proxy listening** on that port,
  - or firewall the server's port from the device's network.
  Rotation only considers siblings in the same pool, so two manual ("My profiles") servers work too —
  they form their own pool.
- **Shorten the timings** so tests take seconds, not minutes: Settings → Auto-failover → interval
  `5000`, timeout `2000`, threshold `2`, max switches `3`. That gives a ~10 s detection window.
  Remember `rotationWindowMs` is **not** editable in the UI and stays at 600 000 ms (10 min) — that is
  the re-arm delay, so tests that wait for a re-arm need ten minutes of patience or a
  `shared_prefs` edit + force-stop.
- **Watch the logs**: `adb logcat | grep -i "Failover\|Kill-switch\|VPN"`, plus the in-app Logs screen
  (Settings → Diagnostics → Logs), which shows the same sanitized buffer.
- **Note on the in-app error line:** it persists until the next transition to `CONNECTING`. A rotation
  therefore clears it. If you are checking an error message, read it before the next rotation starts.

---

## Test 1 — Basic rotation ★

**Why:** the happy path; nothing else matters if this doesn't work.

**Steps:**
1. Enable auto-failover. Connect to server **A** (a working one) in a subscription that also has **B**.
2. Confirm the tunnel works (load a page).
3. Kill A's proxy.
4. Wait ~`threshold × interval` (10 s at the suggested settings).

**PASS:**
- Logs show `Failover: tunnel unhealthy after N consecutive probe failures`, then
  `Failover: rotating A -> B`.
- The state line flickers **Connecting** ("Switching to another server…" on the notification), then
  **Connected**.
- A notification "**Switched server** — A stopped responding. Now connected to B." appears (default
  importance: it should appear in the shade, not heads-up over your screen).
- The highlighted server in the list is now **B**, and the QS tile label says **B**.
- Traffic works again.

**FAIL:** no rotation at all (feature not armed — see Test 8); the state stays "Connected" through the
teardown gap (the gap must be announced); the list still highlights A after a successful rotation.

---

## Test 2 — Fail-closed give-up, all servers dead ★★ MANDATORY BEFORE MERGE

**Why:** this is *the* fail-closed claim, and the one thing no automated test can prove. It must be
observed as "the internet does not work", not assumed.

**Steps:**
1. Enable auto-failover. Connect to A. Confirm traffic works.
2. Kill **every** server in that pool (A, B, and any others).
3. Wait for the rotation attempts to exhaust the pool.
4. **With the VPN still showing a running state, open an IP-check site** (`ifconfig.me`,
   `whatismyipaddress.com`) in a browser, and try a second app too (not just the browser).

**PASS:**
- Logs show rotations through each candidate, each failing, then
  `Failover: giving up (no healthy candidate left in pool)` and
  `Failover: blackhole TUN established with fd=…` followed by
  `Failover: traffic is held in an unread tunnel; nothing leaks to the open network`.
- State line reads **"No server connection"** (not "Error", not "Disconnected").
- Ongoing notification (1101) reads "No server connection — paused to keep you protected".
- A **high-importance** alert appears: "**No server connection** — None of your servers responded, so
  your connection is paused on purpose…".
- **The IP-check site does NOT load. In any app.** Not "loads and shows your real IP" — does not load.
- `adb shell dumpsys connectivity | grep -i vpn` (or Settings → Network) still shows a VPN present.

**FAIL — report immediately:**
- The IP-check site loads at all, **especially** if it shows your real IP. That is the leak this whole
  design exists to prevent.
- The state says "Error"/"Not protected" instead of "No server connection" — that means the blackhole
  `establish()` failed and you are in the `UNPROTECTED` outcome. That is a *legitimate* state (see
  Test 5), but it should not happen on a normal device with VPN consent already granted; capture logs.

**Also check the wording (both locales):** no user-visible string anywhere in this flow may contain
"blackhole", "traffic blocked", or similar jargon. Switch the app language to Russian and repeat step
4 briefly — the Russian copy must convey (a) no server responded, (b) the pause is deliberate,
(c) what to do next.

---

## Test 3 — Give-up with the tunnel STILL UP (`CONTAINED_BY_LIVE_TUNNEL`)

**Why:** the three give-up outcomes must never share one message. This is the outcome where telling
the user "your traffic is blocked" would be simply false.

**Steps:**
1. Put **one single server** in "My profiles" (no siblings), or use a one-server subscription.
2. Enable auto-failover, connect to it, then kill it.
3. Wait for detection.

**PASS:**
- Logs: `Failover: giving up (no healthy candidate left in pool)` then
  `Failover: no server to switch to; the current tunnel is still up and traffic stays inside it`.
- Ongoing notification reads "**Server is not responding** — no other server to try" (i.e.
  `vpn_status_no_response`), **not** the "paused to keep you protected" line.
- The alert title is "Server is not responding" and its body says you are **still protected but pages
  may not load** — not "your connection is paused".
- The tunnel is still up: `dumpsys connectivity` still shows the VPN, and traffic to a *different*
  (reachable) destination still fails only because the proxy is dead, not because the fd is unread.

**FAIL:** the blackhole copy ("paused on purpose", "nothing leaks") is shown for a tunnel that is
still proxying — that is the message-sharing defect this outcome exists to prevent.

**Then verify recovery:** bring the server back up. Within one probe interval, logs must show
`Failover: tunnel is passing traffic again; clearing the give-up state`, the state returns to
**Connected**, and the alert is cancelled. **FAIL** if the app stays stuck in the give-up state over a
working connection.

---

## Test 4 — Thrash cap

**Why:** a flapping server must not put the app in an endless rotation loop.

**Steps:**
1. Set max switches to `2`, interval `5000`, threshold `1`.
2. With a pool of ≥3 servers, kill them one at a time so each rotation lands on another dead one.

**PASS:** exactly `maxRotations` rotations are admitted, then
`Failover: giving up (thrash cap reached)` and one of the give-up outcomes above (which one depends on
whether a tunnel was owned at that moment — Test 3's outcome if the current tunnel survived, Test 2's
if it did not).

**FAIL:** rotations continue past the cap.

---

## Test 5 — The `UNPROTECTED` give-up and the full "disconnect now, stop if the re-arm fails" lifecycle ★★

**Why:** **this entire path has never executed anywhere** — not in unit tests, not in the instrumented
suite, not on hardware. It needs `Builder.establish()` itself to fail, which cannot be staged
off-device.

**How to make `establish()` fail** — try these in order, and note which one worked:
- **Revoke VPN consent while connected**: Settings → Network → VPN → the app's gear → "Forget"/revoke,
  or `adb shell cmd appops set com.justme.xtls_core_proxy ACTIVATE_VPN deny`. This is the most likely
  route to a genuine `establish()` failure.
- **Start another VPN app** so it takes the single VPN slot, then kill all your servers.
- **`adb shell pm revoke`/`cmd appops`** any permission the builder needs, mid-session.

If none of these produce an `UNPROTECTED` outcome, **record that in this file rather than deleting the
test** — "we could not force it" is a useful result and the path stays unproven.

### 5a — First `UNPROTECTED` give-up

**PASS:**
- Logs: `Failover: blackhole TUN could not be established: …` then
  `Failover: WARNING - no tunnel could be established, traffic is NOT contained`.
- State is **Error**, and the in-app error line reads "We couldn't keep your connection protected.
  Please turn the VPN off and on again, or choose another server."
- Ongoing notification reads "**Not protected — please reconnect**".
- The alert title is "**Connection is not protected**", and its body does **not** claim anything is
  contained.
- **The service is still running** — the Disconnect button is visible in the app.

**FAIL — report immediately:** any containment wording ("paused on purpose", "nothing leaks") in this
state. That is telling the user their traffic is safe at the exact moment it is not.

### 5b — The single automatic recovery rotation

Stay in 5a's state. The re-arm timer fires after `rotationWindowMs` (default **10 minutes**; shorten
it by editing `failover_rotation_window_ms` in `shared_prefs/xray_prefs.xml` and force-stopping the
app, since the screen has no control for it).

**PASS:** exactly **one** rotation is attempted (a rotation, **not** a monitor restart — the log must
show `Failover: rotating …`, not `Failover monitor started`). If a server is reachable again by then,
it connects and everything clears.

**FAIL:** the log shows only `Failover monitor started` — that would mean the re-arm went back to the
monitor path, which out of `UNPROTECTED` can never produce a rotation (there is no tunnel, so the probe
travels the clear network and always succeeds).

### 5c — The second uncontained give-up stops the service

Keep every server dead and keep `establish()` failing, so the 5b rotation also ends `UNPROTECTED`.

**PASS:**
- Log: `Failover: recovery attempt also failed to bring up a tunnel; stopping the VPN`.
- The in-app error reads "We couldn't reach any server, so the VPN has been switched off. Choose a
  server and try again."
- A **1102 error notification** with that same text appears **and survives** the foreground service
  stopping (it has its own id).
- State is **Disconnected**, the ongoing notification is gone, and `dumpsys connectivity` shows **no**
  VPN.

**FAIL:** the service keeps running in a state where it protects nothing; or it stops on the *first*
`UNPROTECTED` (that would forfeit the automatic recovery and switch the VPN off without the user
asking).

### 5d — Disable failover mid-`UNPROTECTED` leaves a *usable* state

From 5a, turn auto-failover **off** at the settings screen before the timer fires.

**PASS, all four:**
1. The pending retry is cancelled — log shows
   `Failover: retry timer stood down (feature disabled or session ended)`, or nothing fires at all.
   **No automatic rotation and no automatic VPN shutdown may happen after the user disabled the
   feature.**
2. **The controls are still alive** — this is the check that matters and it is the mirror of 5e.3.
   The main button still offers **Connect** and is **tappable**; tap it and a working server comes up
   (the service restarts rather than refusing with "VPN already running"). **Disconnect** also still
   works.
3. The **1105 alert stays up** and the ongoing line still reads the "not protected" copy. Unlike 5e,
   nothing here is contained, so retracting the warning would leave the user with no indication that
   they are on the clear network.
4. The state stays `ERROR` / not-protected. There is no TUN to preserve, so nothing about the traffic
   path changes at the moment of the toggle.

**FAIL:** Connect does nothing / logs "VPN already running" while the service keeps running with no
TUN — the user is unprotected with only Disconnect working and the loudest warning retracted; or the
1105 alert disappears.

### 5e — Disable failover while `BLACKHOLED` leaves a *usable* state

From Test 2 or Test 3's give-up (state `BLACKHOLED`), turn auto-failover **off** at the settings
screen. Disabling cancels the re-arm timer, which was the only automatic way out — so the release of
the episode state is what stops this stranding the user.

**PASS, all four:**
1. The **1105 give-up alert is cancelled** (it no longer claims a repair is pending).
2. The connection state **stays `BLACKHOLED`** and the **TUN stays up**. This is deliberate: tearing
   it down would drop the user onto the clear network as a side effect of a settings change, and
   switching to `ERROR` would offer a plain Connect that `startVpn` refuses with "VPN already
   running" — a dead button one state over.
3. The main button therefore still reads **"Reconnect"** and is **tappable** — tap it and a working
   server comes up (Test 19's criteria).
4. **Disconnect** also still works.

**FAIL:** the alert stays up; or the controls go dead so neither Reconnect nor Disconnect is
available; or the TUN is torn down and traffic goes to the clear network on a settings change.

---

## Test 6 — The notification Stop action, app closed, from `UNPROTECTED` ★

**Why:** in `UNPROTECTED` the copy tells the user to turn the VPN off, and the ongoing notification is
the only surface guaranteed present when the app UI is closed. Never exercised on hardware.

**Steps:**
1. Reach the 5a state.
2. **Swipe the app away from Recents** (do not force-stop — that kills the service too).
3. Pull down the shade and tap **Stop** on the ongoing "Not protected" notification.

**PASS:** the VPN stops. `dumpsys connectivity` shows no VPN, the ongoing notification disappears.
**FAIL:** the action is missing, does nothing, or the service stays alive.

**Also:** repeat with the app closed from the **`BLACKHOLED`** state (Test 2). The Stop action must be
present and work there too.

---

## Test 7 — The QS tile from `BLACKHOLED` ★

**Why:** the tile renders `STATE_ACTIVE` for `BLACKHOLED`, and the Stop gate is duplicated in **two**
places (`decideTileClick` and `handleClick`'s no-IO fast path). A mismatch would make the tile look
live and be dead — strictly worse than looking dead.

**Steps:**
1. Add the app's tile to Quick Settings.
2. Reach the `BLACKHOLED` state (Test 2).
3. Pull down QS. The tile must be **ON/active** and labelled "No server connection".
4. Tap it.

**PASS:** the VPN stops (state → Disconnected, tile → inactive).
**FAIL:** tapping does nothing and the log says "VPN already running" — that is the dead-control
regression; report it.

**Also test from a locked screen** (the tile defers Start behind unlock but Stop should not need it).

---

## Test 8 — Process-fresh arm (the silently-dead-feature guard) ★

**Why:** `FailoverPreferences.state` is a process-global `MutableStateFlow(DEFAULT)`. If the seeded
`FailoverPreferences.load` in `startVpn` were missing, failover would work **only** after visiting its
settings screen in that process — silently dead on every process-fresh connect, with no crash and no
error log.

**Steps:**
1. Enable auto-failover.
2. `adb shell am force-stop com.justme.xtls_core_proxy` — so no settings Activity has run in the new
   process.
3. Connect **from the QS tile**, not from the app.
4. Kill the server.

**PASS:** rotation still happens.
**FAIL:** nothing happens until you open the app's Auto-failover screen.

**Variant worth doing:** enable Always-on VPN, reboot, then kill the server. Same expectation.

---

## Test 9 — Failover without the kill-switch (the default pairing) ★

**Why:** the screen receiver used to belong entirely to the kill-switch. With failover on and the
kill-switch **off** — which is most users — no receiver existed at all.

**9a:** kill-switch **off**, failover **on**. Connect. Screen off for >1 min, then screen on.
**PASS:** `adb logcat` shows probing genuinely paused while the screen was off and resumed on wake
(the probe log lines stop and restart). **FAIL:** probes continue through screen-off, or never resume.

**9b:** both features on. Same screen off/on cycle → same expectation.

**9c:** both on, then turn the **kill-switch off mid-session** while connected. Screen off/on again.
**PASS:** failover's screen pausing still works. **FAIL:** it stopped — the kill-switch's teardown tore
the shared receiver out from under failover.

---

## Test 10 — Failover survives a kill-switch cycle ★

**Why:** if the monitor is never restarted after a revive, failover is dead for the rest of the
session — invisibly.

**Steps (both features on):**
1. Connect. Foreground a kill-listed app → tunnel goes **Paused**.
2. Wait **longer than `failureThreshold × probeIntervalMs`** (>40 s at defaults; >10 s at the
   suggested test timings).
3. Leave that app → tunnel returns to **Connected**.
4. Now kill the server.

**PASS:**
- **No** `Failover: tunnel unhealthy` line was logged while paused (the monitor is stopped, not
  paused — pausing would preserve the failure count and trip instantly on revive).
- Killing the server **after** the revive still triggers a rotation.

**FAIL:** either half. Failing the second means the monitor was never restarted.

**Also test the reverse interleave:** trigger a kill-switch event **during** a rotation (foreground a
kill-listed app right as the server dies). **PASS:** the kill is deferred and replayed once the
rotation commits — the tunnel ends **Paused** with the exposed alert, not Connected with the
kill-listed app in the foreground.

**And the third exit — a rotation that GIVES UP.** Same interleave, but kill **every** server first so
the rotation cannot commit (as in Test 2). The deferred kill must be **dropped, and announced**:

**PASS:**
- Log: `Kill-switch: dropping the kill deferred for <app>`.
- A **"VPN is still on"** notification naming that app (id 1106, on the same high-importance channel
  as the exposure alert — it may heads-up).
- The session lands in the give-up state (**No server connection**), **not** `Paused`, and **no**
  ⚠️ "VPN is OFF — you're exposed" alert appears.
- **Then leave it alone for a full `rotationWindowMs`** (set it short for the test, or wait out the
  10 min default) and let the re-arm rotation succeed by bringing a server back. **Nothing** must
  replay: no `Paused`, no exposure alert, no teardown of the just-restored tunnel. This is the merge
  blocker the drop exists to prevent.

**FAIL:** an exposure alert or a `Paused` state at any point in this variant — especially the delayed
one, which is the whole defect.

**Note:** on the `UNPROTECTED` give-up (no tunnel at all) the 1106 notice is deliberately **absent** —
there the app really is not behind a VPN, and that outcome reports itself.

### 10a — A kill-switch pause must retract the give-up alert (1103 vs 1105)

**Why:** both are high-importance heads-up alerts and they contradict each other. This is reachable
for **all three** give-up outcomes, because a give-up leaves the session in `CONNECTED` and never
touches the kill-switch monitor.

**Steps (both features on), run once per outcome:** reach a give-up — Test 2 (`CONTAINED_BY_BLACKHOLE`),
Test 3 (`CONTAINED_BY_LIVE_TUNNEL`), Test 5a (`UNPROTECTED`) — then foreground a kill-listed app.

**PASS:**
- The give-up alert (**1105**) is **gone** the moment the ⚠️ "VPN is OFF — you're exposed" alert
  (**1103**) appears. Pull the shade down: exactly one of them is present.
- State is **Paused**.
- Leave the kill-listed app → the tunnel revives to **Connected**, and 1105 does **not** come back.

**FAIL:** both alerts visible together at any point — in particular "your connection was paused to
keep you protected" sitting beside "the VPN is OFF and you're exposed".

---

## Test 11 — Live settings edits mid-session

**Steps (connected, failover on):**
1. Change the probe **interval** → log must show `Failover: rebuilding monitor for updated probe
   timings`.
2. Change something that is *not* a timing field (toggle nothing, just re-save by editing and
   restoring a value) → **no** rebuild line for an unchanged tuple. **FAIL** if the monitor rebuilds on
   every save: that restarts the poll cycle continuously and the tunnel is never actually observed.
3. Toggle failover **off** mid-session → the monitor stops; killing the server does nothing.
4. Toggle it back **on** → the monitor restarts and rotation works again.

**Also:** open **Diagnostics → Ping test** and change the target URL. It is the **same** target the
health probe uses. Confirm the failover probe now hits the new target (`adb logcat`, or point it at
something that returns non-204 and watch failover start reporting the tunnel unhealthy).

---

## Test 12 — Release APK install and bridge exercise ★★ MANDATORY BEFORE SHIPPING

**Why:** `assembleRelease` passing proves the **pipeline builds**. It does **not** prove that
`xraybridge.**` / `go.**` survived R8 obfuscation — and `mapping.txt` is 38 MB, so obfuscation is
definitely on. AGENTS.md is explicit that a green build proves nothing here; the breakage would show
up only at runtime.

**Steps:**
1. `./gradlew :app:assembleRelease` (already done — artifacts are in
   `app/build/outputs/apk/release/`; rebuild only if you changed code since).
2. Uninstall the debug build (**back up first if the device holds real profiles** — a signature
   mismatch cannot be bypassed), then
   `adb install app/build/outputs/apk/release/boykisser-arm64-v8a-release-2.3.0R.apk`.
3. On the **release** build, exercise every reflection/JNI path:
   - **Connect** (`StartXray` + `RegisterProtector`) — traffic flows, IP is the proxy's.
   - **Disconnect** (`StopXray`).
   - **Ping test** on a server and on a whole group (`MeasureLatency`).
   - **About screen / linked core version** (`XrayVersion`).
   - **A full auto-failover rotation** (Test 1) — this exercises `StopXray` + `StartXray` back-to-back
     inside one session, which is the sequence most likely to expose a stripped or renamed symbol.
   - **Connect to fastest** (Test 13).
4. Watch `adb logcat` for `ClassNotFoundException` / `NoSuchMethodException` / `UnsatisfiedLinkError`.

**PASS:** every path works on the release APK with no reflection failure in logcat.
**FAIL:** any of the above — capture the stack trace and deobfuscate it with
`app/build/outputs/mapping/release/mapping.txt`.

---

## Test 13 — Connect to fastest ★

Long-press a server → **Connect to fastest**. It probes that profile's whole pool and connects to the
fastest responder.

### 13a — Happy path
With several reachable servers in one subscription and the VPN **disconnected**, long-press one and
tap Connect to fastest.
**PASS:** a progress row appears ("Finding the fastest server…" + Cancel), the pooled servers' rows
show ping spinners, and the app connects to the one with the lowest latency. The connected highlight
and the QS tile both name that server.

### 13b — Cancel
Start a run, tap **Cancel** while spinners are still going.
**PASS:** the run stops, the progress row disappears, and **no row is left spinning on "Testing"
forever** — every one of *this run's* pool ids returns to its normal state. Any *other* group test
running concurrently must keep its own spinners.

### 13c — Background then resume (the consumption-side re-gate) ★★
This is the sequence that produced "the UI names server B while traffic flows through server A".
1. VPN **disconnected**. Long-press a server in a multi-server pool → Connect to fastest.
2. **Immediately background the app** (Home).
3. While it is backgrounded, **connect via the QS tile** to some server **A**.
4. Return to the app.

**PASS:** the winner is **discarded**, the error line reads "The connection changed while
connect-to-fastest was running, so it was cancelled. Try again.", and the highlighted server + tile
**both still say A**.
**FAIL — report immediately:** the app highlights the probe's winner (B) while traffic actually flows
through A. Verify which server traffic is really on via an IP-check site if the two servers have
different exit IPs.

### 13d — Foreground state change (the production-side re-gate)
Start a run, and **while it is running** tap Connect on a different server row (rows are not disabled
during a run).
**PASS:** same `STATE_CHANGED` message; the manually-chosen server is the one that stays connected.

### 13e — Same-subscription supersede
Start a run on server X, then — before it finishes — long-press server Y **in the same subscription**
and start another. The two runs have **identical** pools.
**PASS:** only one run is in flight; the progress row does not disappear when the superseded run
unwinds; exactly one winner is delivered.

### 13f — The busy path
Enable auto-ping on open (Settings → Ping test), relaunch the app so the launch-time auto-ping is
running, and immediately start Connect to fastest on a pool whose servers are already being probed.
**PASS:** if no winner results, the message is the **BUSY** one ("Some of these servers were already
being tested elsewhere…"), not the NO_RESPONSE one.
**Note the known limitation:** cross-run dedup can also hand the picker a *stale* `Success` from an
earlier run, so a winner may be chosen from non-fresh data with no message at all. If you see a
suspiciously instant winner, that is the known cause, not a new bug.

### 13g — Rotation mid-probe
Connect with auto-failover on, kill the server, and start a Connect-to-fastest run so the rotation and
the probe overlap.
**PASS:** no crash, no duplicate connect; whichever path wins, the highlighted server and the tile
agree with the server traffic actually flows through.

### 13h — Russian rendering of the three long error strings
Switch the app language to Russian and provoke each of the three connect-to-fastest errors
(13c/13d → `state_changed`, a dead pool → `no_response`, 13f → `busy`).
**PASS:** all three render fully and legibly in the error line under the state row. The `Text` has no
`maxLines`, so long strings **wrap** rather than truncate — check they do not push the server list off
the screen or overlap the progress row on a small display.

---

## Test 14 — T10b: the refused-start rollback ★★

**Why:** ruled explicitly into this matrix instead of being checked over the SSH-tunnelled adb link
(which likely would not survive an active tun). This closes "UI names B while traffic flows through A"
on the refused-start path.

**You cannot reach this by tapping Connect while connected.** Every such control is gated by the same
rule — `state/connectAction(state)`, which returns `UNAVAILABLE` in `CONNECTED` (it replaced the old
boolean `canConnect`): the per-server row is disabled, the long-press dialog's **Connect** and
**Connect to fastest** rows are disabled by the *same* value (`ProfileActionsDialog`), and the QS tile
returns **Stop** while `CONNECTED`. A tester who tries that route finds every control dead and records
a meaningless PASS. (Note this is specifically `CONNECTED`. In `BLACKHOLED` the same rule returns
`RECONNECT` and the controls **are** live — that is Test 19, not this one.)

The reachable window is the one T10b actually guards: a start request for **B** is *authorised while
the VPN is off*, then sits behind a system dialog while **A** comes up underneath it. `MainActivity`'s
`onConnect` records `pendingProfileId = B` and writes **nothing** to `ActiveProfileRepository` until
the dialog is answered — so A can take the session in between, and the grant then dispatches a start
for B that `startVpn` refuses.

**Setup:**
- Two servers **A** and **B** with different exit IPs.
- **A** is the *active* profile (connect to A once, then disconnect — always-on and the tile both
  bring up whatever `ActiveProfileRepository` names).
- **Always-on VPN enabled** for this app (Settings → Network → VPN → gear → Always-on). This also
  means VPN consent is already granted, so `VpnService.prepare()` returns null later.
- **Notification permission DENIED** for the app (Settings → Apps → *this app* → Notifications → off).
  This is what makes the connect flow stop on a system dialog.

**Steps:**
1. Let always-on bring the VPN up on **A**. Confirm **Connected** and that A is highlighted.
2. Tap **Disconnect**. The per-server Connect rows become enabled again.
3. Immediately **tap Connect on server B**. The system **"Allow notifications?"** dialog appears.
   **Do not answer it yet.**
4. Wait ~10–30 s with the dialog open for the system's always-on watchdog to restart the VPN — it
   comes up on the *active* profile, **A**, because step 3 wrote nothing. (Pull the shade down over
   the dialog if you need to see the ongoing notification say **Connected**.)
   *If always-on does not restart it on your device*, use the alternative in the note below.
5. Now tap **Allow**. This runs `requestVpnPermissionAndConnect()` → `viewModel.connect(this, B)`,
   which writes active = **B** and dispatches `ACTION_START` for B into a session already running A.
6. Read the log: it should say `VPN already running`.

**PASS:** **both** the in-app highlight **and** the QS tile label still say **A**, and an IP check
still shows A's exit. The rollback fired.
**FAIL:** the highlight or the tile switches to B while the tunnel is still A's — the misreported
active server this rollback exists to prevent.

**Alternative if always-on will not restart on demand (step 4):** with the app disconnected, tap
**Connect on A**, answer its dialog, and *while A is still `CONNECTING`* tap **Connect on B**. The row
disables itself as soon as `CONNECTING` is published, so this is a genuine race and may need several
attempts — the always-on route above is the deterministic one. Do not substitute "tap Connect on B
while connected": that control is disabled and proves nothing.

**Known residual (expected, not a failure):** if the refusal lands while a rotation is in flight
**and** that rotation then fails, the repository can be left naming the failed rotation target. Note it
if you see it; it is a follow-up item, not a regression.

---

## Test 15 — ERROR Disconnect on an already-dead session ★

**Why:** the Disconnect button now also renders for a genuinely *dying* session, where the service has
already `stopSelf()`'d. That tap dispatches `startForegroundService(ACTION_STOP)`, which **creates**
the service. It is safe only because the stop path reaches `stopVpn`'s early return well inside
Android's ~5 s `startForeground` deadline — miss it and you get
`ForegroundServiceDidNotStartInTimeException`.

**Steps:**
1. Produce a genuine start failure that lands in `ERROR` with the service stopping — easiest is a
   profile whose config cannot build (edit a server's config to malformed JSON) or a revoked VPN
   consent at connect time.
2. With the state showing **Error**, tap **Disconnect**.

**PASS:** nothing bad happens — state goes/stays Disconnected, **no crash**, and in particular no
`ForegroundServiceDidNotStartInTimeException` in logcat.
**FAIL:** an ANR or that exception. Report it — it means something now awaits on the ACTION_STOP path.

---

## Test 16 — Connect from `UNPROTECTED` restarts without dropping foreground ★

**Why:** the recovery restart calls `stopVpn(stopService = false)` specifically so the FGS promotion
stays continuous. A flicker out of foreground would risk the service being killed mid-restart.

**Steps:**
1. Reach the 5a `UNPROTECTED` state (service running, no tunnel).
2. Bring a server back up.
3. From the app, **tap Connect on a server** (the copy is telling the user to do exactly this).

**PASS:**
- Log shows `Restarting the tunnel to recover from an unprotected state (profile id=…)`, then a normal
  `Starting VPN service` / connect sequence with a **fresh epoch**.
- The connection succeeds.
- The ongoing notification does **not** visibly disappear and reappear.
- `adb shell dumpsys activity services com.justme.xtls_core_proxy | grep -i foreground` shows the
  service stays foreground across the restart.

**Known cosmetic issue (expected):** the restart publishes `DISCONNECTED` before `CONNECTING`, so the
UI and tile may flicker to Disconnected/inactive for that gap. Noise, not a lie — there really is no
tunnel at that instant.

---

## Test 17 — Offline guard (no false rotations)

**Why:** without it, losing signal makes every probe fail and the engine thrashes through the whole
server list blaming servers for the phone being offline.

**Steps:**
1. Connect with failover on. Confirm it is healthy.
2. Turn on **airplane mode** (or otherwise drop all connectivity) for >`threshold × interval`.
3. Turn connectivity back on.

**PASS:** **no** rotation is attempted while offline, and none fires immediately on reconnect (the
failure counter is reset, not merely paused).
**FAIL:** rotations start while the phone has no network, or one fires the instant signal returns.

---

## Test 18 — Teardown hygiene

1. **Stop the VPN** from a `BLACKHOLED` state → the 1105 alert must be **cancelled** (it does not
   survive; only 1102-class error notifications do).
2. **Stop and restart** the VPN after a give-up → the new session must start with a clean slate: the
   first rotation must be admitted (no stale thrash count) and no server must be skipped as a stale
   episode failure.
3. **Kill-switch pause while `BLACKHOLED`** — known contradictory pair: 1105 ("paused to keep you
   protected") can sit next to 1103 ("VPN is OFF — you're exposed"). Confirm whether you see it and
   note it; it is a ledgered known limitation, not a new defect.
4. **The 1106 "kill-switch not applied" notice is retracted.** Provoke it first: with the kill-switch
   armed, open a controlled app *during a rotation* so the kill is deferred, then let the rotation
   give up (Test 3) — the notice appears on the high-importance channel. Now check **both** exits:
   **(a)** stop the VPN → 1106 disappears (it claims in the present tense that the app is still going
   through the VPN, and `setAutoCancel` alone would only clear it on a tap); **(b)** with 1106
   showing, bring the controlled app to the foreground so the kill-switch fires → 1106 must be gone
   **before** the 1103 "VPN is OFF — you're exposed" alert appears. **FAIL:** the two heads-up alerts
   are visible together, even briefly — they contradict each other on the same channel.
5. **Uninstall/reinstall or clear data** → auto-failover is **off** again (default), and the settings
   screen shows interval 15000 / timeout 5000 / threshold 2 / max switches 3.

---

## Test 19 — Reconnect from a contained give-up, repeatedly ★★

**Why:** `BLACKHOLED` used to disable every connect control; it now renders **"Reconnect"** and routes
through `ReconnectFlow`'s stop → settle → start → verify sequence, which is entirely off-device logic
until a real Xray core has to be closed. Both contained outcomes share this one path, so both must be
exercised. **Repeatedly**, because the in-flight guard is released in a `finally` — if it ever wedged
shut, Reconnect would work exactly once per ViewModel and look intermittent rather than broken.

**Before you start:** confirm the control really is enabled in this state — an earlier revision of
this matrix told the tester to use a control that was greyed out, and the test passed vacuously. In
`BLACKHOLED` the main button must read **"Reconnect"** and be tappable, and the long-press dialog's
**Connect** row must read "Reconnect" and be tappable too. If either is greyed out, that is a FAIL of
this test, not a reason to skip it.

**19a — `CONTAINED_BY_LIVE_TUNNEL` (a live core is closed).** Reach Test 3's state (give-up with the
tunnel still up: no candidate or thrash cap). The main button reads **Reconnect**.
1. Tap **Reconnect**, having first restored at least one working server.
2. Watch the button: it must read **"Connecting…"** and be **non-tappable** during the window.

**PASS:** the session tears down and a new one comes up on the chosen server — state reaches
`CONNECTED`, `dumpsys connectivity` shows a VPN, and traffic exits via the new server's IP. The 1105
give-up alert clears. Log shows a stop followed by a start, **not** "VPN already running".
**FAIL:** "VPN already running" in the log with no new session; or the VPN ends up **off** and stays
off; or the button stays enabled while reading "Connecting…".

**19b — `CONTAINED_BY_BLACKHOLE` (no core to close).** Reach Test 2's state (all servers dead,
blackhole established). Restore one server, then tap **Reconnect**. Same PASS criteria.

**19c — Repeat 19a three times in a row** (give up → Reconnect → give up → Reconnect → …).
**PASS:** the third Reconnect behaves exactly like the first. **FAIL:** the button is dead, or the tap
does nothing, on the second or third attempt — that is the guard wedging shut.

---

## Test 20 — A second Reconnect tap during the window ★

**Why:** the teardown can run for **seconds** while the state is still `BLACKHOLED`, so the affordance
keeps rendering and a re-tap is the natural user response. The rule is **first request wins**: a
second request is refused *and reported*, never queued — a second stop landing after the first flow's
start would kill the very session the user asked for.

**Steps:**
1. Reach 19a's state with a working server available.
2. Tap **Reconnect**, then **immediately tap the same control again** (and, if you can, a *different*
   server's Connect row in the long-press dialog).

**PASS:** every control is disabled the moment the first tap lands, so the second tap cannot be
delivered at all. If your device is fast enough to land one anyway, it must be **refused with the
"request superseded" message** — and the session that comes up is the one from the **first** tap.
**FAIL:** the second tap tears down the session the first one started (VPN ends up off); or a
*different* server ends up connected than the one first tapped; or a second tap is silently swallowed
with no message.

**Also check the label scoping:** while a Reconnect for server **A** is in flight, only **A**'s control
may read "Connecting…". Every *other* server's row must be disabled but must **not** claim to be
connecting. (A single flag here previously made thirty unrelated servers all claim to be connecting.)

---

## Test 21 — A tile / notification Stop overrides a Reconnect in flight ⚠ KNOWN LIMITATION

**Why:** this is a **ledgered limitation, not a defect to file** — the point of the test is to confirm
the blast radius is what the docs claim, and to keep it visible to whoever next edits the tile or the
notification Stop path. `ReconnectFlow` watches `LogRepository.connectionState`, which is
**source-blind**: it cannot tell its own stop from anyone else's. The in-app Disconnect is handled (it
calls `cancelReconnect()`); the QS tile and the ongoing notification's Stop action are **not** — both
dispatch `ACTION_STOP` straight to the service.

**Steps:**
1. Reach 19a's state with a working server available.
2. Tap **Reconnect**, then **background the app** (Home — do *not* swipe it from Recents).
3. Within the next few seconds, pull down the shade and tap **Stop** on the ongoing notification (and
   separately, on another run, tap the **QS tile**).

**EXPECTED (the known limitation):** the VPN **comes back up** rather than staying off — the flow read
your `DISCONNECTED` as its own teardown completing and dispatched its start. Window is up to ~10 s
(`STOP_TIMEOUT_MS` 8 s + `START_VERIFY_MS` 2 s).
**Record which surface you used and how long it took.** Then stop the VPN again — the second Stop must
work normally, since no reconnect is in flight by then.

**Contrast — the in-app Disconnect MUST win.** Repeat from step 2 but stay in the app and tap
**Disconnect**. **PASS:** the VPN goes off and **stays** off. **FAIL:** it comes back up — that would
mean `cancelReconnect()` is no longer wired to the in-app Disconnect, which *is* a real defect.

---

## Test 22 — A Reconnect in flight does not survive the Activity being finished

**Why:** `ReconnectFlow.run` is launched on `viewModelScope`. Rotation and backgrounding keep the
ViewModel alive; a genuine **finish** does not. The accepted behaviour is fail-safe — it fails to "VPN
off", never to a silently unprotected tunnel — but a tester should know it is expected.

**Steps:**
1. Reach 19a's state with a working server available.
2. Tap **Reconnect**, then **immediately swipe the app away from Recents** (or press Back out of it).

**EXPECTED:** the VPN ends up **off** and stays off, with no VPN key in the status bar and no VPN in
`dumpsys connectivity`. It must **not** end up in a state that looks connected while carrying no
traffic.
**FAIL:** a tunnel is left up that does not carry traffic, or the app reports `CONNECTED` with no VPN.

**Then confirm the two non-cases:** repeat with a **screen rotation** instead of a finish, and again
with **Home** (background) instead. In both, the reconnect must **complete normally** — those keep the
ViewModel alive and are not affected.

---

## Result log

Record the outcome here as you go, so a partial run is still useful to the next person.

| Test | Result | Notes |
|---|---|---|
| 1 Basic rotation | | |
| 2 Fail-closed give-up ★★ | | |
| 3 Live-tunnel give-up | | |
| 4 Thrash cap | | |
| 5a/b/c/d UNPROTECTED lifecycle ★★ | | which method forced `establish()` to fail? |
| 6 Notification Stop, app closed | | |
| 7 Tile Stop from BLACKHOLED | | |
| 8 Process-fresh arm | | |
| 9a/b/c Failover without kill-switch | | |
| 10 Survives kill-switch cycle | | |
| 11 Live settings edits | | |
| 12 Release APK + bridge ★★ | | |
| 13a–h Connect to fastest | | |
| 14 T10b refused-start rollback ★★ | | |
| 15 ERROR Disconnect, dead session | | |
| 16 Connect from UNPROTECTED | | |
| 17 Offline guard | | |
| 18 Teardown hygiene | | |
| 19a/b/c Reconnect from a contained give-up ★★ | | 19c is the repeat-three-times guard |
| 20 Second Reconnect tap during the window ★ | | first request must win |
| 21 Tile/notification Stop overrides a Reconnect ⚠ | | known limitation — record surface + timing |
| 22 Reconnect does not survive an Activity finish | | rotate/background must still complete |
