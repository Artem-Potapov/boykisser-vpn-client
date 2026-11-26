# Dormant and superseded Auto-Failover QA

Companion to [`auto-failover-qa.md`](auto-failover-qa.md). These entries retain their original test
numbers but are **not active acceptance steps**. The active matrix leaves a same-numbered stub and a
link here for each one; do not renumber the remaining tests.

| Entry | Disposition | Evidence / reason |
|---|---|---|
| 5a–5d | Superseded for rotation establishment failure; dormant as an active oracle | Rotation now establishes its unread bridge before releasing the live TUN. A bridge-establish failure aborts the handover into give-up while the previous live/dead TUN remains authoritative, rather than producing the old fresh-blackhole/`UNPROTECTED` sequence. The genuine fresh-establish permission-revocation path instead reports `VPN permission was revoked`. |
| 6 (`UNPROTECTED` variant only) | Dormant / unreachable | The contained-give-up notification-Stop variant remains active in the main matrix. The historical variant required 5a and therefore shares its unreachable precondition. |
| 13g | Dormant / unreachable | Maintainer result 2026-11-23: connect-to-fastest cannot be started while connected, so the required rotation-mid-probe setup cannot be produced through this control. |
| 14 | Dormant / unreachable pending platform investigation | Maintainer reproduced on two phones with different Android versions: the always-on watchdog did not restart after the user pressed Disconnect. It also did not retry with notification permission granted. This is an environmental/oracle blocker, not a demonstrated app PASS or FAIL. |
| 16 | Dormant / unreachable | Its required 5a `UNPROTECTED` state cannot be produced through the current rotation-abort path. |

## Tests 5a–5d — historical `UNPROTECTED` lifecycle

### Current interpretation

Do not use a failed rotation-bridge establishment to score these steps. The current service calls
`shouldEstablishRotationBridgeBeforeTeardown`; if that establishment fails, it logs an abort and enters
give-up without first releasing the previous live TUN. That behavior is deliberately containment-
preserving. The fresh-establish/VPN-permission-revocation route is a different oracle and currently
surfaces `VPN permission was revoked`.

The procedures below are preserved only so the old evidence and terminology remain interpretable.
They are not a request to weaken the bridge or restore release-before-establish behavior.

### How the old oracle forced `establish()` to fail

- **Revoke VPN consent while connected**: Settings → Network → VPN → the app's gear → "Forget"/revoke,
  or `adb shell cmd appops set com.justme.xtls_core_proxy ACTIVATE_VPN deny`. This was the route that
  worked on 2026-08-06. Revoking consent this way did not tear down the existing tunnel.
- Start another VPN app so it takes the single VPN slot, then kill all servers.
- Revoke another permission needed by the builder mid-session.

### 5a — First `UNPROTECTED` give-up

**Historical PASS expectations:**
- Logs: `Failover: blackhole TUN could not be established: …` then
  `Failover: WARNING - no tunnel could be established, traffic is NOT contained`.
- State is **Error**, with the in-app error "We couldn't keep your connection protected. Please turn
  the VPN off and on again, or choose another server."
- Ongoing notification reads "**Not protected — please reconnect**".
- The alert title is "**Connection is not protected**" and its body makes no containment claim.
- The service is still running and Disconnect remains visible.
- Any reassuring containment wording in this state was a failure.

### 5b — The single automatic recovery rotation

From historical 5a, the re-arm timer fired after `rotationWindowMs` (default ten minutes). The old
procedure shortened `failover_rotation_window_ms` in `shared_prefs/xray_prefs.xml` and force-stopped
the app. Values below 60 seconds were coerced to the 60-second floor.

**Historical PASS expectations:** exactly one rotation was attempted (`Failover: rotating …`), not
merely `Failover monitor started`. If a server was reachable again, it connected and cleared the state.
A monitor restart instead of a rotation was a failure because an `UNPROTECTED` probe has no tunnel and
would measure the clear network.

### 5c — The second uncontained give-up stops the service

With every server dead and establishment still failing, the historical 5b rotation also ended
`UNPROTECTED`.

**Historical PASS expectations:**
- Log: `Failover: recovery attempt also failed to bring up a tunnel; stopping the VPN`.
- In-app error: "We couldn't reach any server, so the VPN has been switched off. Choose a server and
  try again."
- Error notification 1102 used the same text and survived foreground-service stopping.
- State became Disconnected, the ongoing notification disappeared, and no VPN remained.
- Stopping on the first `UNPROTECTED`, or continuing to run without protection after the second, was a
  failure.

### 5d — Disable failover mid-`UNPROTECTED`

From historical 5a, auto-failover was disabled before the retry timer fired.

**Historical PASS expectations:**
1. The retry was cancelled (`Failover: retry timer stood down (feature disabled or session ended)` or
   no retry fired), with no later automatic rotation or shutdown.
2. Connect and Disconnect controls remained usable; Connect could start a working server.
3. Alert 1105 and the ongoing not-protected warning stayed visible.
4. State remained `ERROR` / not-protected; disabling the feature did not itself alter the traffic path.

## Test 6 (`UNPROTECTED` variant) — Notification Stop, app closed

**Original procedure:** reach historical 5a, swipe the app away from Recents (not force-stop), pull
down the shade, and tap Stop on the ongoing "Not protected" notification.

**Original PASS expectation:** the VPN stops, no VPN remains in `dumpsys connectivity`, and the ongoing
notification disappears. A missing or ineffective Stop action was a failure.

**Dormancy evidence:** the required historical 5a state cannot currently be produced. The active main
matrix retains only the contained-give-up variant; do not mark this `UNPROTECTED` variant covered by
that run.

## 13g — Rotation mid-probe

**Original procedure:** connect with auto-failover on, kill the server, and start Connect to fastest
so the rotation and probe overlap.

**Original PASS expectation:** no crash or duplicate connect; whichever path wins, the highlighted
server and QS tile agree with the server carrying traffic.

**Dormancy evidence:** the maintainer found on 2026-11-23 that Connect to fastest cannot be used while
connected. This is good control gating, but it makes the requested overlap unreachable through this
entry point. Do not substitute a disconnected run and claim this test passed.

## Test 14 — T10b refused-start rollback

### Purpose

The test guards against "UI names B while traffic flows through A" when a start request for B is
authorized while the VPN is off, then remains pending behind a system dialog while always-on starts A.
`MainActivity` records `pendingProfileId = B` without writing `ActiveProfileRepository`; the eventual
grant dispatches B into a session already running A, and `startVpn` must refuse and roll back the
active-profile write.

Tapping Connect while already connected is not a substitute: `state/connectAction(CONNECTED)` disables
the row, long-press Connect/Connect-to-fastest, and tile start paths.

### Original setup

- Two servers A and B with different exit IPs.
- A is active (connect once, then disconnect).
- Always-on VPN enabled for this app, with VPN consent already granted.
- Notification permission denied so the connect flow stops on a system dialog.

### Original steps and oracle

1. Let always-on connect A; confirm Connected and A highlighted.
2. Tap Disconnect, then immediately Connect on B.
3. Leave the system "Allow notifications?" dialog unanswered.
4. Wait 10–30 seconds for always-on to restart A.
5. Tap Allow, dispatching the pending B start into running A.
6. Expect `VPN already running` in the log.

**Original PASS expectation:** highlight and tile still name A and an IP check still shows A.
Switching either label to B while traffic remains on A was a failure.

The documented fallback was to answer A's connection dialog and tap B while A was still `CONNECTING`.
That is a genuine race and may take several attempts; it is not equivalent to tapping B after A is
connected.

### Dormancy evidence

On 2026-11-23 the maintainer reproduced the watchdog blocker on two phones with different Android
versions. After the user pressed Disconnect, always-on did not appear to restart the tunnel. Granting
notification permission did not make it retry. Until a reliable platform helper or alternative setup
exists, this is dormant rather than an app PASS/FAIL. Any future helper must preserve the original
oracle: pending B plus already-running A, followed by an active-profile rollback check.

The old known residual remains relevant if this is reactivated: a refusal during a rotation that later
fails can leave the repository naming the failed rotation target.

## Test 16 — Connect from `UNPROTECTED` restarts without dropping foreground

### Original procedure and oracle

1. Reach historical 5a `UNPROTECTED` (service running, no tunnel).
2. Restore a working server.
3. Tap Connect on that server.

**Original PASS expectations:**
- Log shows `Restarting the tunnel to recover from an unprotected state (profile id=…)`, then a normal
  `Starting VPN service` / connect sequence with a fresh epoch.
- Connection succeeds and the ongoing notification does not visibly disappear and reappear.
- `dumpsys activity services` shows the service remains foreground across the restart.

The transient `DISCONNECTED` before `CONNECTING` was documented as cosmetic rather than a failure.

### Dormancy evidence

The current pre-teardown rotation bridge means the maintainer cannot produce the required 5a state.
Do not use a contained live-tunnel or blackhole give-up as a substitute: those are Reconnect scenarios,
not this no-TUN recovery restart.
