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

The default target is `http://cp.cloudflare.com/generate_204`. Success is defined as: **dial the
configured plaintext HTTP target through the outbound, receive HTTP 204, and measure the round-trip
time.** This is the same "real delay" definition used by v2rayNG and NekoBox, and it is the only
definition that catches the REALITY camouflage-fallback case. Any failure — dial error, non-204
status, timeout, or unbuildable config — maps to `N/A`.

### The `http://` constraint

The editable preference accepts any nonempty value beginning with `http://`. The default target omits
its port, so the Go probe uses port 80; an explicit numeric port in the URL is also supported. The
probe connects raw TCP to that host and port via `core.Dial`, then writes a plain HTTP/1.1 `GET` and
requires a 204 response. There is no TLS layer on the target connection, so `https://` is rejected at
the preference layer. The proxy outbound itself may be TLS/QUIC; the final hop to the configured
HTTP/204 endpoint is plaintext on purpose.

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
- **Before any registration in this process:** `currentProtector` is `nil`, so the installed
  controller (if present) is a no-op.
- **After registration:** `currentProtector` remains the most recently registered callback until a
  later `RegisterProtector` call replaces it or the process dies. `StopXray` and VpnService teardown
  do not clear it, so do not document a post-service `nil` transition that the bridge does not
  implement.

This interaction is a **must-verify-on-device point**: run a group test while connected to confirm
probes return results without disturbing the live tunnel (see Testing below).

## Data flow

```
User taps speedometer on group header  (or "Ping test" in long-press island dialog — see [profile-actions-menu.md](profile-actions-menu.md))
        │
VpnViewModel.pingTestGroup(profiles)  /  pingTestProfile(profile)
  ├─ loads PingPreferences fresh at probe admission
  ├─ rebuilds PingTester only when requested concurrency changed
  ├─ emits PingState.Testing for each freshly-accepted id → rows show a spinner
  └─ launches on viewModelScope; launches PingTester.testAll(...)
        │
PingTester  (bounded parallel, Semaphore(freshly loaded concurrency))
  ├─ de-duplicates ids already in flight
  └─ for each id: calls injected probe: suspend (Long) -> PingState; streams result back via onUpdate immediately
     (PingTester never calls ConfigBuilder directly — the probe lambda is probeProfile in VpnViewModel)
        │
ConfigBuilder.toPingTestConfig(storedConfig): String  ← called by probeProfile in VpnViewModel
  └─ calls buildRuntimeConfig (DoH, ForceIP, outbounds preserved)
     then removes the "inbounds" array  ← no tun inbound; probe uses core.Dial
     then stripGeoRoutingRules  ← drops geoip:/geosite:/ext: rules (probe has no geo assets)
        port-53 → dns-out and other non-geo routing rules are kept
        │
probeProfile: viewModelScope.async(Dispatchers.IO) { measureLatency(config, target, timeout) }
  └─ withTimeoutOrNull(backstopFor(timeout)) { await() }  ← configured timeout + 5 s
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

`VpnViewModel.init` also prunes stale entries when the profile set changes (e.g. a subscription
refresh replaces rows with new ids): it collects `profiles` and runs
`_pingStates.update { states -> states.filterKeys { it in ids } }` so orphaned id → `PingState`
mappings do not accumulate. The UI only reads ids present in the current view, so this is
housekeeping rather than user-visible behavior.

## UI entry points

- **Whole-group test:** speedometer icon (`res/drawable/ic_speedometer.xml`) on each group header —
  including "My profiles". While the group is testing the icon shows a spinner; tapping again is a
  no-op (no cancel in v1).
- **Single-server test:** "Ping test" entry in the long-press island dialog (`ProfileActionsDialog` —
  `BasicAlertDialog`, not a bottom sheet; see [profile-actions-menu.md](profile-actions-menu.md)). No
  per-row gauge icon; the result appears as a badge line below the server name (mirroring the
  `sanitizedDns` badge).
- **"My profiles" group:** manually-added profiles (`subscriptionId == null`) are gathered into a
  synthetic expandable group. It has no DB row; the expand-state key is the string literal `"manual"`.
  The group has no refresh action and no "last seen" subtitle, but it does have the speedometer and is
  testable exactly like a real subscription group.
- **Auto-ping on open:** after the asynchronous profile lists become non-empty, `MainActivity` builds
  the same manual + subscription-profile union the render loop uses. If the fresh preference enables
  auto-ping and its Activity-scoped `VpnViewModel` has not consumed the latch, it consumes the latch
  and probes the union. The effect is keyed on list non-emptiness so it does not race the initial Room
  load. It runs at most once during the surviving `MainActivity`/`VpnViewModel` lifetime, so ordinary
  navigation, resume, and recomposition do not repeat it. A new Activity/ViewModel instance can reset
  the latch; it is not process-scoped.

## Preferences and bounds

`PingPreferences` stores four global values in `xray_prefs` and is read **fresh when a probe is
accepted**; unlike VPN connection tuning, ping settings have no session-capture rail.

| Preference | Default | Validation / meaning |
|---|---|---|
| Target URL | `http://cp.cloudflare.com/generate_204` | Must start with `http://` and contain a value after the scheme. It is a plaintext HTTP 204 target by design; `https://` is invalid. |
| Timeout | `10_000 ms` | Clamped and UI-validated to `1_000..30_000 ms`; passed to Go for dial + request. |
| Concurrency | `3` | Clamped and UI-validated to `1..5`; maximum simultaneous group probes. |
| Auto-ping on open | off | Once per surviving `MainActivity`/`VpnViewModel` lifetime, after profiles load; a new instance resets the latch. |

`PingTester.backstopFor(timeoutMs)` derives the Kotlin wall-clock backstop as the selected timeout plus
`BACKSTOP_MARGIN_MS` (5 seconds), preserving the invariant that the backstop is strictly above the Go
deadline. `VpnViewModel` retains the existing `PingTester`/Semaphore while concurrency is unchanged and
reconstructs it only when the freshly-loaded concurrency value changes.

## Components

| File | Responsibility |
|---|---|
| [`state/PingState.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/PingState.kt) | Sealed UI state: `Idle`, `Testing`, `Success(latencyMs: Long)`, `Unavailable`. `fromResult(Result<Long>)` maps `runCatching` outcomes. |
| [`state/PingTester.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/PingTester.kt) | Pure orchestrator: bounded-parallel `testAll` via `Semaphore`; in-flight de-dup; streams results via `onUpdate`; `CancellationException` propagates, all other `Throwable` → `Unavailable`. Companion owns defaults and `backstopFor(timeoutMs)`. |
| [`state/PingPreferences.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/PingPreferences.kt) | `xray_prefs` persistence, `http://` target validation, timeout/concurrency bounds, and defaults tied to `PingTester`. |
| [`state/VpnViewModel.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/VpnViewModel.kt) | `pingStates` (ephemeral); fresh-at-probe preferences; reconstruct-on-concurrency-change tester; `probeProfile(profile, targetUrl, timeoutMs)` with derived backstop; auto-ping latch scoped to this ViewModel instance. |
| [`config/ConfigBuilder.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/config/ConfigBuilder.kt) | `toPingTestConfig(stored: String): String` — calls `buildRuntimeConfig` (DoH, ForceIP, outbounds preserved) then removes the `inbounds` array and `stripGeoRoutingRules` (drops `geoip:`/`geosite:`/`ext:` rules that fail in the geo-asset-less throwaway instance; keeps port-53 → `dns-out` and other non-geo rules). |
| [`MainActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/MainActivity.kt) | Owns `VpnViewModel` through Activity `viewModels()`, renders speedometer/results, wires manual probes, and triggers auto-ping after manual + grouped profiles load. |
| [`ProfileActionsDialog.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/ProfileActionsDialog.kt) | Long-press island menu (`BasicAlertDialog`) with a "Ping test" row (`ic_speedometer`); see [profile-actions-menu.md](profile-actions-menu.md). |
| [`settings/PingTestSettingsActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/settings/PingTestSettingsActivity.kt) | Target, timeout, concurrency, and auto-on-open editor with exact validation bounds. |
| [`bridge/XrayBridge.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/bridge/XrayBridge.kt) | `measureLatency(configJson, targetUrl, timeoutMs: Long): Result<Long>` — reflects `MeasureLatency`/`measureLatency` (3 params); handles gomobile's int/long type variance for the timeout arg; parses the JSON result; logs failures to `LogRepository`. |
| [`xray-go/xray_bridge.go`](../../xray-go/xray_bridge.go) | `MeasureLatency(configJson, targetURL string, timeoutMs int) string` — throwaway `core.Instance`; `core.Dial` to target; raw HTTP 1.1 GET; requires status 204; returns `{"latencyMs":N}` or `{"error":"..."}`. Never locks `mu`; never touches global `instance`. |
| [`res/drawable/ic_speedometer.xml`](../../app/src/main/res/drawable/ic_speedometer.xml) | Custom vector drawable for the group-header speedometer / spinner affordance. `material-icons-extended` was not added for a single glyph. |

## Error handling

| Failure mode | Outcome |
|---|---|
| `toPingTestConfig` throws (bad stored config) | Caught in `probeProfile`; `PingState.Unavailable` + `LogRepository` message. |
| Go returns `{"error":"..."}` (dial fail, write/read fail, non-204, timeout) | `XrayBridge.measureLatency` wraps as `Result.failure`; `PingState.Unavailable`. |
| `MeasureLatency` called with empty config | Returns `{"error":"empty config"}` immediately. |
| `url.Parse` fails or hostname is empty | Returns `{"error":"bad target url: ..."}` immediately → `N/A`. The preference validator checks only the nonempty `http://` prefix, so some malformed values can reach this guard. |
| Target port is non-numeric | Returns `{"error":"bad target port: ..."}` immediately → `N/A`. Explicit numeric ports are supported; malformed port text can pass the preference-layer prefix check. |
| `probeProfile` exceeds `backstopFor(configuredTimeout)` waiting on JNI | `withTimeoutOrNull` returns null; row → `N/A` + `LogRepository` backstop message. The orphaned `async` probe may still finish later; its result is discarded. |
| Persisted target has an invalid scheme or nothing after `http://` | `PingPreferences.load` falls back to the default plaintext `http://` HTTP-204 endpoint. Other malformed `http://` values can pass this layer and fail in Go as described above. |
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
  thrown exception → `PingState.Unavailable`; id already in-flight is skipped; `backstopFor` is
  configured timeout + margin.
- **`PingPreferencesTest` / `AutoPingTest`** (JVM): defaults match live constants; persistence keys
  round-trip; invalid target fallback; timeout `1_000..30_000` and concurrency `1..5` clamp on
  load/save; auto-ping requires enabled + unconsumed; tester reconstruction occurs only when
  concurrency changes.
- **`ConfigBuilderPingTest`** (JVM): `toPingTestConfig_fromVless_hasOutboundsAndNoInbounds` and
  `toPingTestConfig_fromJson_stripsInbounds` (no `inbounds`, outbounds + DNS kept);
  `toPingTestConfig_stripsGeoRoutingRulesButKeepsDnsRoute` (no `geoip:`/`geosite:`/`ext:` rules, port-53 →
  `dns-out` preserved). No Hysteria2-specific `toPingTestConfig` test — Hysteria2 coverage is via the
  on-device matrix.
- **Go / bridge** — no pure unit test is feasible (gomobile + JNI). The AAR build, `javap` inspection
  of the generated class for `MeasureLatency`, and Gradle build checks are complete. No item in the
  device matrix has been verified; all remain manual and outstanding.
- **On-device matrix (manual, outstanding):**
  1. Known-good server → `N ms` displayed.
  2. Deliberately wrong REALITY key/shortId → `N/A` (proves camouflage-fallback detection).
  3. Hysteria2 server → `N ms`.
  4. Test **while connected** to a different server → probes return, live tunnel undisturbed.
  5. Test **while disconnected** → probes return (direct dial).
  6. Whole subscription group → results stream in, no more than the configured `1..5` concurrent dials.
  7. "My profiles" group expands/collapses and runs its group test.
  8. Enable auto-ping and confirm one union probe starts only after profiles load; navigation/resume
     within the surviving Activity/ViewModel must not trigger a second run. Creating a new
     MainActivity/ViewModel may reset the latch and permit another automatic run.
