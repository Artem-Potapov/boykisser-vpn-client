# Ping Test — Handshake-Latency Probe per Server and Group

Maintainer reference for the ping-test feature: per-server and per-group latency probes that measure an
end-to-end proxied HTTP 204 handshake and display the result inline in the server list.

## Why end-to-end 204, not a raw TCP/TLS probe

A raw Kotlin TCP+TLS connection to a server's host and port is not a faithful measurement for two
reasons rooted in how this app's supported protocols behave:

1. **REALITY camouflage-fallback.** When the REALITY public key or shortId is wrong, the server does
   not reject the connection — it silently forwards the client to the camouflage site it fronts. A raw
   TLS handshake therefore *succeeds* against the camouflage certificate and would report a misleading
   green latency. The only way to confirm that traffic actually egressed *through* the proxy is to send
   a real request through it and verify the expected response.
2. **Hysteria2 is QUIC/UDP.** The JVM cannot speak QUIC natively; only xray-core can dial a Hysteria2
   outbound.

Success is therefore defined as: **dial `http://cp.cloudflare.com/generate_204` through the
outbound, receive HTTP 204, measure the round-trip time.** This is the same "real delay" definition
used by v2rayNG and NekoBox, and it is the only definition that catches the REALITY camouflage-fallback
case. Any failure — dial error, non-204 status, timeout, or unbuildable config — maps to `N/A`.

### The `http://` constraint

`PING_TEST_TARGET` **must remain an `http://` (port 80) URL.** The Go probe connects raw TCP to the
target host via `core.Dial`, then writes a plain HTTP/1.1 `GET` request and reads the response — there
is no TLS layer on the connection to the target. `https://` would cause the HTTP read to fail because
the server would respond with a TLS handshake, not an HTTP response. The proxy outbound itself may be
TLS/QUIC; the last-mile hop to `cp.cloudflare.com` is plain HTTP on purpose.

## Transient instance and global `protect()` interaction

`MeasureLatency` in [`xray-go/xray_bridge.go`](../../xray-go/xray_bridge.go) builds a throwaway
`core.Instance` using `core.New` + `Start`, dials via `core.Dial`, and closes the instance when done.
It **never locks `mu` and never reads or writes the global `instance`** that `StartXray`/`StopXray`
manage, so a probe runs safely alongside a live tunnel without disturbing it.

Socket protection is free: 2A's `RegisterProtector` installs **one process-global**
`internet.RegisterDialerController` callback under `sync.Once` (see
[`docs/features/failclosed-startup.md`](failclosed-startup.md)). That controller covers every socket
dialed by xray-core's default dialer — including the throwaway probe instance. Concretely:

- **Tunnel up:** the probe's sockets are automatically carved out of the tun by the already-registered
  `protect()` callback and egress directly to the proxy server. No new protection wiring is needed.
- **No tunnel:** `currentProtector` is `nil` (either never set this process, or cleared after the
  VpnService went away). The once-installed dial controller is still present but becomes a no-op when
  `currentProtector` is `nil`, so the probe's sockets dial the proxy directly — there is no tun to
  bypass. Also correct — the same path the real tunnel's connect call would take on first start.

This interaction is a **must-verify-on-device point**: run a group test while connected to confirm
probes return results without disturbing the live tunnel (see Testing below).

## Data flow

```
User taps speedometer on group header  (or "Ping test" in long-press bottom sheet)
        │
VpnViewModel.pingTestGroup(profiles)  /  pingTestProfile(profile)
  ├─ emits PingState.Testing for each freshly-accepted id → rows show a spinner
  └─ launches on viewModelScope; launches PingTester.testAll(...)
        │
PingTester  (bounded parallel, Semaphore(DEFAULT_PING_CONCURRENCY))
  ├─ de-duplicates ids already in flight
  └─ for each id: calls injected probe: suspend (Long) -> PingState; streams result back via onUpdate immediately
     (PingTester never calls ConfigBuilder directly — the probe lambda is probeProfile in VpnViewModel)
        │
ConfigBuilder.toPingTestConfig(storedConfig): String  ← called by probeProfile in VpnViewModel
  └─ calls buildRuntimeConfig (DoH, ForceIP, outbounds, routing all preserved)
     then removes the "inbounds" array  ← no tun inbound; probe uses core.Dial
        │
XrayBridge.measureLatency(configJson, targetUrl, timeoutMs): Result<Long>
  └─ reflection facade, same pattern as startXray; parses {"latencyMs":N} | {"error":"..."}
        │
xray-go: MeasureLatency(configJson, targetURL, timeoutMs) string
  └─ throwaway core.Instance → core.Dial(ctx, inst, dest)
     → raw HTTP GET /generate_204 → require 204 → return {"latencyMs":N}
        │
pingStates: StateFlow<Map<Long, PingState>>  (ViewModel, ephemeral)
  └─ each result written immediately (streaming); rows recompose as results arrive
```

### Why results are ephemeral

`pingStates` lives in `VpnViewModel` — in-memory, keyed by profile id, cleared on process death. There
is no DB column and no Room migration. This is intentional: a latency measured on a different network
or at a different time can mislead rather than inform. Fresh state on every app start avoids
stale-trust.

## UI entry points

- **Whole-group test:** speedometer icon (`res/drawable/ic_speedometer.xml`) on each group header —
  including "My profiles". While the group is testing the icon shows a spinner; tapping again is a
  no-op (no cancel in v1).
- **Single-server test:** "Ping test" entry in the long-press bottom sheet. No per-row gauge icon; the
  result appears as a badge line below the server name (mirroring the `sanitizedDns` badge).
- **"My profiles" group:** manually-added profiles (`subscriptionId == null`) are gathered into a
  synthetic expandable group. It has no DB row; the expand-state key is the string literal `"manual"`.
  The group has no refresh action and no "last seen" subtitle, but it does have the speedometer and is
  testable exactly like a real subscription group.

## Tunables

All tunables live in `PingTester.Companion`:

| Constant | Value | Meaning |
|---|---|---|
| `DEFAULT_PING_CONCURRENCY` | `3` | Max dials in flight at once across a group test. |
| `PING_TIMEOUT_MS` | `10_000L` | Per-probe timeout, 10 s; deadline applied inside Go (dial + request). |
| `PING_TEST_TARGET` | `"http://cp.cloudflare.com/generate_204"` | The HTTP 204 endpoint; must remain `http://` — see constraint above. |

## Components

| File | Responsibility |
|---|---|
| [`state/PingState.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/PingState.kt) | Sealed UI state: `Idle`, `Testing`, `Success(latencyMs: Long)`, `Unavailable`. `fromResult(Result<Long>)` maps `runCatching` outcomes. |
| [`state/PingTester.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/PingTester.kt) | Pure orchestrator: bounded-parallel `testAll` via `Semaphore`; in-flight de-dup; streams results via `onUpdate`; `CancellationException` propagates, all other `Throwable` → `Unavailable`. Injected `probe` makes it JVM-unit-testable with a fake dialer. Companion holds the three tunables. |
| [`state/VpnViewModel.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/VpnViewModel.kt) | `pingStates: StateFlow<Map<Long, PingState>>` (ephemeral); `pingTestGroup(profiles)` / `pingTestProfile(profile)`; wires `ConfigBuilder.toPingTestConfig` + `XrayBridge.measureLatency` (IO dispatcher); `PingTester` instance held as a field. |
| [`config/ConfigBuilder.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/config/ConfigBuilder.kt) | `toPingTestConfig(stored: String): String` — calls `buildRuntimeConfig` (preserving DoH, ForceIP, outbounds, routing) then removes the `inbounds` array so there is no tun inbound and no fd is required. |
| [`bridge/XrayBridge.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/bridge/XrayBridge.kt) | `measureLatency(configJson, targetUrl, timeoutMs: Long): Result<Long>` — reflects `MeasureLatency`/`measureLatency` (3 params); handles gomobile's int/long type variance for the timeout arg; parses the JSON result; logs failures to `LogRepository`. |
| [`xray-go/xray_bridge.go`](../../xray-go/xray_bridge.go) | `MeasureLatency(configJson, targetURL string, timeoutMs int) string` — throwaway `core.Instance`; `core.Dial` to target; raw HTTP 1.1 GET; requires status 204; returns `{"latencyMs":N}` or `{"error":"..."}`. Never locks `mu`; never touches global `instance`. |
| [`res/drawable/ic_speedometer.xml`](../../app/src/main/res/drawable/ic_speedometer.xml) | Custom vector drawable for the group-header speedometer / spinner affordance. `material-icons-extended` was not added for a single glyph. |

## Error handling

| Failure mode | Outcome |
|---|---|
| `toPingTestConfig` throws (bad stored config) | Caught in `probeProfile`; `PingState.Unavailable` + `LogRepository` message. |
| Go returns `{"error":"..."}` (dial fail, write/read fail, non-204, timeout) | `XrayBridge.measureLatency` wraps as `Result.failure`; `PingState.Unavailable`. |
| `MeasureLatency` called with empty config | Returns `{"error":"empty config"}` immediately. |
| `url.Parse` fails or hostname is empty (malformed `PING_TEST_TARGET`) | Returns `{"error":"bad target url: ..."}` immediately → `N/A`. Unreachable with the current constant but guards against future `PING_TEST_TARGET` changes. |
| REALITY wrong key — camouflage-fallback HTML page | HTTP status is 200 (or similar), not 204 → `{"error":"unexpected status N"}` → `N/A`. This is the key correctness guarantee. |
| Profile already `Testing` when a re-test arrives | `PingTester` de-duplicates in-flight ids; the duplicate is silently skipped. |

## R8 / minification note

`MeasureLatency` is reached by reflection from `XrayBridge.measureLatency`. It is covered by the
existing `-keep class xraybridge.**` in `app/proguard-rules.pro`. No new keep rule is required, but
**verify on a release build**: run `:app:assembleRelease`, `javap` the generated bridge class to
confirm `MeasureLatency` is present, then exercise the ping path on the release APK. A green debug
build does not prove the release path.

## Testing

- **`PingTesterTest`** (JVM, `kotlinx-coroutines-test`): at most `DEFAULT_PING_CONCURRENCY` dials in
  flight at once; results stream regardless of completion order; `Success` outcome → `PingState.Success`;
  thrown exception → `PingState.Unavailable`; id already in-flight is skipped.
- **`ConfigBuilderTest`** (JVM): `toPingTestConfig` for VLESS, Hysteria2, and raw-JSON inputs each
  produces a config with no `inbounds` key and intact outbounds / DNS / routing.
- **Go / bridge** — no pure unit test is feasible (gomobile + JNI). Verified by building the `.aar`,
  `javap` inspection of the generated class for `MeasureLatency`, and the on-device matrix below.
- **On-device matrix:**
  1. Known-good server → `N ms` displayed.
  2. Deliberately wrong REALITY key/shortId → `N/A` (proves camouflage-fallback detection).
  3. Hysteria2 server → `N ms`.
  4. Test **while connected** to a different server → probes return, live tunnel undisturbed.
  5. Test **while disconnected** → probes return (direct dial).
  6. Whole subscription group → results stream in, no more than 3 concurrent dials.
  7. "My profiles" group expands/collapses and runs its group test.
