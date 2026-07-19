# Fragmentation: Global Anti-DPI `sockopt.fragment` Overlay (TCP-only)

Maintainer reference for the fragmentation feature: a global, opt-in overlay that splits the outbound
TLS ClientHello (and early TCP writes) to defeat DPI signature-matching. Covers the `ConfigBuilder`
overlay (a security-chokepoint change), how it's persisted and captured once per session in
`XrayVpnService`, why it's TCP-only, the XHTTP-over-HTTP/3 carve-out, and the settings screen.

## Why this exists

Some censors fingerprint the TLS ClientHello (SNI, cipher list, length) in a single contiguous read.
Xray-core's `streamSettings.sockopt.fragment` splits the outbound TCP write stream — with
`packets = "tlshello"` it specifically fragments the ClientHello record — so the fingerprint spans
multiple segments the DPI box can't cheaply reassemble. This feature exposes that knob as a **global**
(app-wide, not per-server) setting, off by default, with presets and a custom mode.

`sockopt.fragment` is a **TCP-dialer knob**: it splits socket writes on a TCP connection. It has no
effect on QUIC/UDP transports (Hysteria2, `quic`, `kcp`, or XHTTP running over HTTP/3), so the overlay
is applied only to TCP-based outbounds — see the gate below.

## The tuning model

[`config/TuningSettings.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/config/TuningSettings.kt)
holds all connection overlay inputs. It deliberately mirrors `LogSettings`: one immutable value
captured once per session and threaded into `ConfigBuilder`, never re-read mid-session. In addition to
fragmentation it now carries Mux, DNS, routing, and XRAY-core settings.

```kotlin
data class TuningSettings(
    val fragmentation: FragmentationSettings = FragmentationSettings.DISABLED,
    // mux, dns, routing, core ...
) {
    companion object { val NONE = TuningSettings() }
}

data class FragmentationSettings(
    val enabled: Boolean, val packets: String, val length: String, val interval: String,
) {
    companion object {
        val DISABLED = FragmentationSettings(false, "tlshello", "100-200", "10-20")
    }
}
```

The three value fields are Xray's raw `fragment` strings (`packets` `tlshello`, `length` `100-200`,
`interval` `10-20`). `TuningSettings.NONE` → `FragmentationSettings.DISABLED` (`enabled = false`), so the
default path is a guaranteed no-op.

## The `ConfigBuilder` overlay — a security-chokepoint change

[`config/ConfigBuilder.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/config/ConfigBuilder.kt)'s
`buildRuntimeConfig` takes a **3rd parameter** (default preserves every existing call site):

```kotlin
fun buildRuntimeConfig(
    input: String,
    log: LogSettings = LogSettings(XrayLogLevel.WARNING, null),
    tuning: TuningSettings = TuningSettings.NONE,
): String {
    ...
    val withLog = forceLog(base, log)
    val withFragmentation = applyFragmentation(withLog, tuning.fragmentation)
    val withMux = applyMux(withFragmentation, tuning.mux)
    val withDns = applyDns(withMux, tuning.dns)
    val withRouting = applyRouting(withDns, tuning.routing)
    val forceSniffing = tuning.core.sniffing || routingNeedsDomainRules(tuning.routing)
    return applyCoreSettings(withRouting, tuning.core, forceSniffing)
}
```

Two invariants make this safe to add to the fail-closed chokepoint (see
[`dns-leak-enforcement.md`](dns-leak-enforcement.md)):

1. **It runs after mandatory shaping, and only adds.** `applyFragmentation` runs *after* the
   secure-DNS shaping (each `from*` builder ends in `makeSecureDns`) and *after* `forceLog`, but before
   the Mux/DNS/routing/core overlays. It touches only the first proxy outbound's
   `streamSettings.sockopt`, and only ever adds a `fragment` sub-object — it never edits the `dns`
   block, the port-53→`dns-out` routing rule, the tun inbound, or the `log` object.
2. **It MERGES, never overwrites, the sockopt.** `makeSecureDns` already wrote
   `sockopt.domainStrategy = "ForceIP"` (the server-name-bootstrap that stops the proxy's own hostname
   leaking to a plaintext resolver) onto that same outbound. `applyFragmentation` fetches the existing
   sockopt (`ss.optJSONObject("sockopt") ?: JSONObject().also { … }`) and puts `fragment` alongside, so
   `domainStrategy=ForceIP` and `fragment` coexist. `disabled → return configJson` short-circuits before
   any JSON parse, so a disabled overlay is a byte-for-byte no-op.

### Why TCP-only, and the XHTTP-over-HTTP/3 carve-out

`applyFragmentation` writes the fragment only when `isTcpBasedOutbound(proxy)` is true:

```kotlin
private val TCP_NETWORKS = setOf("tcp", "ws", "grpc", "h2", "httpupgrade", "xhttp")

private fun isTcpBasedOutbound(outbound: JSONObject): Boolean {
    if (outbound.optString("protocol").lowercase().startsWith("hysteria")) return false
    val ss = outbound.optJSONObject("streamSettings")
    val net = ss?.optString("network")?.lowercase().orEmpty().ifBlank { "tcp" }
    if (net !in TCP_NETWORKS) return false
    if (net == "xhttp" && isHttp3(ss)) return false   // xhttp over HTTP/3 is QUIC — fragment is inert
    return true
}

/** True only for an HTTP/3 (QUIC) transport: security==tls AND tlsSettings.alpn contains "h3". */
private fun isHttp3(streamSettings: JSONObject?): Boolean { … }
```

- Hysteria2 (`protocol` starts with `hysteria`), and the standalone `quic` / `kcp` transports (not in
  `TCP_NETWORKS`; a blank/absent `network` is treated as `tcp`) are skipped — they're UDP, where the
  TCP fragment knob does nothing.
- **XHTTP is the one `TCP_NETWORKS` member that can also ride HTTP/3 (QUIC/UDP).** It's decidable from
  config because **QUIC mandates TLS 1.3** and HTTP/3 always negotiates ALPN token `"h3"` (RFC 9114),
  which is never the ALPN default — you must opt in. So `isHttp3` returns true only when
  `security == "tls"` **and** `tlsSettings.alpn` contains `"h3"`. Every other xhttp — `security: "none"`
  (can't be QUIC, no TLS), REALITY (TCP-only in mainline Xray, no QUIC-REALITY), or `tls` without `h3`
  in ALPN — is TCP and gets fragmented.
- This guard is intentionally **more conservative** than Xray's own trigger (Xray uses h3 only when
  ALPN is exactly `["h3"]`; we skip on *any* ALPN containing `h3`). The divergence only ever causes a
  benign *over-skip* on a multi-ALPN config (lost anti-DPI benefit, never a leak). Applying fragment to
  a genuine QUIC flow would only be inert, never a leak, because fragment is a TCP-write splitter and
  traffic still routes through the tun regardless. This is an anti-DPI *optimization*, not a leak gate.

### The ping path stays clean

`toPingTestConfig` calls `buildRuntimeConfig(stored, LogSettings(XrayLogLevel.NONE, null))` with only two
args, so `tuning` defaults to `NONE` and probe configs never carry a `fragment` block — keeping
latency probes comparable across servers regardless of the user's fragmentation setting.

## Persistence

[`config/FragmentationPreferences.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/config/FragmentationPreferences.kt)
persists the four fields in the shared `xray_prefs` store (same store as `LogPreferences`,
`AppearanceRepository`, `PromoGate`), mirroring the `LogPreferences` idiom:

| Key | Type | Default |
|---|---|---|
| `frag_enabled` | `Boolean` | `false` |
| `frag_packets` | `String` | `tlshello` |
| `frag_length` | `String` | `100-200` |
| `frag_interval` | `String` | `10-20` |

`load(context)` returns a `FragmentationSettings`; `save(context, settings)` writes all four.

## Session capture in `XrayVpnService` — no mid-session leak

[`vpn/XrayVpnService.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt) applies
the **exact same once-per-session discipline** to tuning that it already applies to `sessionLog`:

```kotlin
@Volatile private var sessionTuning: TuningSettings = TuningSettings.NONE
```

- **Captured once**, under `lock`, in the connect path next to `sessionLog = initialLog`; the
  `TuningSettings` snapshot loads fragmentation together with Mux, DNS, routing, and core preferences.
- **Read as a field** by `bringUpTunnel` — `buildRuntimeConfig(profile.config, log, sessionTuning)` — so
  both the initial connect **and** the kill-switch revive path (which also calls `bringUpTunnel`) use the
  value captured at connect time. It is never re-read from `FragmentationPreferences` mid-session.
- **Reset to `NONE`** on teardown, next to `sessionLogFile = null`.

The consequence: toggling fragmentation in Settings while connected cannot change the live tunnel, and a
kill-switch revive re-uses the connect-time value rather than picking up a mid-session edit — so a stale
or in-flight preference change can't leak into a running/revived session. (The read is outside `lock`,
but the config is consumed inside the `synchronized(lock)` transition that re-checks ownership and
throws `StaleSessionException` on a racing teardown, so a concurrent stop can never bring up a tunnel
with stale tuning — fail-closed, identical to `sessionLog`.)

## The settings screen

[`settings/FragmentationSettingsActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/settings/FragmentationSettingsActivity.kt)
is a `LocalizedComponentActivity` reached from the Settings hub's Tunnel section. It loads the current
`FragmentationSettings` once, edits it locally, and persists on Save:

- **Enable switch.**
- **Preset dropdown** ([`ui/components/DropdownField.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/ui/components/DropdownField.kt)):
  - *TLS ClientHello* = `DISABLED.copy(enabled = true)` → `tlshello` / `100-200` / `10-20`.
  - *Aggressive* → `1-3` / `10-20` / `10-20`.
  - *Custom* — free-edit the three fields; editing any field flips the selector to Custom.
  - The initial preset is derived by matching the loaded values; unmatched values → Custom.
- **Custom fields** (packets / length / interval) with **input validation** (added so a bad value can't
  brick every connect: an invalid string written into `sockopt.fragment` makes Xray reject the config,
  failing every connection with a generic error and no signpost):
  - `packets` must match `^(tlshello|\d+(-\d+)?)$`; `length`/`interval` must match `^\d+(-\d+)?$`.
  - Invalid fields show `isError` + `supportingText`; **Save is gated `enabled = !enabled || inputsValid`**
    — always allowed when fragmentation is off (the values are inert until re-enabled, which routes back
    through this validated screen), blocked only when the feature is on and any field is invalid. Input
    is never silently rewritten. Every preset's values pass the regexes, so a preset selection can never
    leave a stuck-invalid state.

## Components

| File | Role |
|---|---|
| [`config/TuningSettings.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/config/TuningSettings.kt) | `TuningSettings`/`FragmentationSettings` value model (+ `NONE`/`DISABLED`); mirrors `LogSettings`. |
| [`config/ConfigBuilder.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/config/ConfigBuilder.kt) | `buildRuntimeConfig` 3rd `tuning` param; `applyFragmentation` (merge-into-sockopt, TCP-only), `isTcpBasedOutbound`/`isHttp3` gate, `TCP_NETWORKS`. |
| [`config/FragmentationPreferences.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/config/FragmentationPreferences.kt) | `xray_prefs` `frag_*` load/save. |
| [`vpn/XrayVpnService.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt) | `@Volatile sessionTuning` captured once per connection, read by `bringUpTunnel`, reset on teardown. |
| [`settings/FragmentationSettingsActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/settings/FragmentationSettingsActivity.kt) | Settings screen: enable switch, presets, validated custom fields. |
| `res/values/strings.xml`, `res/values-ru/strings.xml` | `settings_fragmentation_*`, `fragmentation_*` (incl. `fragmentation_error_*`); en + ru (ru mandatory or release lint fails). |

## Known limitations

- **TCP-only by design.** A Hysteria2/QUIC/kcp server, or an xhttp-over-HTTP/3 server, gets no
  fragmentation even with the feature enabled — the knob is inert on UDP transports. The screen's hint
  string states this.
- **No per-server override.** Fragmentation is global; there is no per-profile fragmentation setting.
- **No live re-apply.** A change while connected takes effect on the next connect (by design — see the
  session-capture section).

## Testing

- **JUnit4 (JVM)** — [`ConfigBuilderFragmentTest.kt`](../../app/src/test/java/com/justme/xtls_core_proxy/ConfigBuilderFragmentTest.kt)
  (10 tests): fragment applied to a TCP VLESS outbound with `domainStrategy=ForceIP` preserved (merge);
  skipped for Hysteria2 and `quic`; disabled tuning is a no-op; the ping path carries no fragment; and
  the xhttp matrix — skipped for xhttp+tls+`[h3]` and xhttp+tls+`[h3,h2]`, applied for xhttp+tls+`[h2]`,
  xhttp+REALITY, and plaintext xhttp.
- **On-device (manual)**: enable fragmentation with the TLS ClientHello preset, connect to a VLESS/TLS
  or REALITY server, confirm connectivity (optionally verify `sockopt.fragment` in the runtime config);
  connect to a Hysteria2 server and confirm it's unaffected; enter an invalid custom value and confirm
  Save is blocked while enabled.

## Related docs

- [`dns-leak-enforcement.md`](dns-leak-enforcement.md) — the secure-DNS chokepoint the fragment overlay
  composes with (the shared proxy-outbound `sockopt` carrying `domainStrategy=ForceIP`).
- [`settings-hub.md`](settings-hub.md) — the Tunnel-section hub row that opens the screen.
- [`hysteria2-support.md`](hysteria2-support.md) — why Hysteria2 (QUIC) is skipped by the TCP-only gate.
