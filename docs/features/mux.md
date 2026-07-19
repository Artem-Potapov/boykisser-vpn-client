# Mux.Cool: Global VLESS Multiplexing Overlay

Maintainer reference for the Tunnel → Mux.Cool setting, its persisted model, and the
`ConfigBuilder.applyMux` overlay.

## Runtime behavior

Mux is global and off by default. When enabled, `applyMux` replaces the selected proxy outbound's
`mux` object with:

```json
{
  "enabled": true,
  "concurrency": 8,
  "xudpConcurrency": 16,
  "xudpProxyUDP443": "reject"
}
```

It applies only when all of these are true:

- the selected proxy outbound is VLESS;
- its user `flow` is blank (XTLS Vision is incompatible with Mux.Cool);
- the transport is `tcp`, `ws`, `grpc`, `h2`, or `httpupgrade` (blank means TCP).

It is skipped for XTLS Vision, xhttp (which uses XMUX), kcp/quic, Hysteria2, and non-VLESS
outbounds. A disabled global setting is a no-op and deliberately preserves an imported config's own
`mux` object.

The overlay runs after fragmentation and before DNS/routing/core settings:
`applyFragmentation → applyMux → applyDns → applyRouting → applyCoreSettings`. Fragmentation's
`sockopt` and the secure-DNS `ForceIP` value therefore coexist with Mux.Cool.

## Model, persistence, and UI

`MuxSettings.OFF` defaults to disabled, concurrency 8, XUDP concurrency 16, and QUIC handling
`BLOCK`. `QuicHandling` maps to Xray's `xudpProxyUDP443` values:

- Block → `reject`
- Allow → `allow`
- Skip → `skip`

`MuxPreferences` stores `mux_enabled`, `mux_concurrency`, `mux_xudp_concurrency`, and
`mux_quic_handling` in `xray_prefs`. Load clamps concurrency to `1..1024`, clamps XUDP concurrency to
at least zero, and falls back safely for an unknown enum.

`MuxSettingsActivity` validates the same bounds while enabled. Save is allowed while disabled even if
an inert text field is malformed; in that case the last saved numeric value is retained rather than
parsing invalid input.

## Session semantics

`XrayVpnService` reads `MuxPreferences` into `sessionTuning` once when a connection is admitted. The
same immutable tuning snapshot is reused by kill-switch revive and reset on full teardown. A setting
change therefore applies on the next full connection, not in the middle of a running session.

Latency probes use `TuningSettings.NONE`; they do not inherit the global mux overlay.

## Testing and manual gate

`ConfigBuilderMuxTest` covers eligible VLESS/TCP, Vision and transport exclusions, disabled
preservation, QUIC-policy mapping, and composition with fragmentation/ForceIP. `MuxSettingsTest` and
`MuxPreferencesTest` cover defaults, wire values, persistence, fallback, and clamping.

On-device, test an eligible non-Vision VLESS profile and confirm connectivity after reconnect. Also
open Config sanitization with an ineligible Vision/xhttp/Hysteria2 profile and confirm the enabled
setting reports `NotApplicable`.
