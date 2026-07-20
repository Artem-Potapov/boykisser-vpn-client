# XRAY Settings: Core Runtime Overlay and Version Surface

Maintainer reference for Advanced → XRAY: MTU, IPv6, sniffing, routing domain strategy, and the linked
xray-core version display.

## Core settings

`XrayCoreSettings.DEFAULT` preserves prior behavior: MTU 1400, IPv6 on, sniffing off, and domain
strategy “From config.” `applyCoreSettings` is the last runtime-config overlay:

- **MTU** — range `1280..1500`; a non-default value rewrites the canonical tun inbound's
  `settings.MTU`. `XrayVpnService.Builder` also uses the same captured MTU.
- **IPv6** — when off, injects `::/0 → block` immediately after the mandatory port-53 rule and
  force-writes DNS `queryStrategy=UseIPv4`. The VpnService still establishes its IPv6 address/route,
  so IPv6 is blocked inside Xray rather than escaping around the tunnel; its IPv6 DNS server is
  omitted from the Android VPN builder. Turning IPv6 off also **degrades a v6-only custom DNS
  resolver** to the Cloudflare v4 preset at the `applyDns` chokepoint, so a resolver reachable only
  over IPv6 can't strand DNS — see [dns-leak-enforcement.md](dns-leak-enforcement.md).
- **Sniffing** — when enabled, writes one route-only tun-inbound block with
  `destOverride = [http, tls, quic]`.
- **Domain strategy** — “From config” is a no-op; explicit values overwrite
  `routing.domainStrategy` with `AsIs`, `IPIfNonMatch`, or `IPOnDemand`.

Domain/geosite routing rules force sniffing even if the user's switch is off. The XRAY screen
re-reads routing preferences on resume, renders the switch forced-on and disabled, and explains why;
the screen still **autosaves the user's own `sniffing` value**, not the forced-display state (there is
no Save button).

The complete order is
`forceLog → applyFragmentation → applyMux → applyDns → applyRouting → applyCoreSettings`.
Last-writer behavior is intentional: IPv6-off overrides the DNS screen's query strategy, and forced
sniffing incorporates routing requirements.

## Persistence and session semantics

`XrayCorePreferences` stores MTU, IPv6, sniffing, and domain strategy in `xray_prefs`. Load/save clamp
MTU to `1280..1500`; an unknown strategy falls back to the default.

`XrayVpnService` captures these preferences once in `sessionTuning` alongside every other connection
overlay. Runtime JSON construction, Android `VpnService.Builder`, and kill-switch revive use that same
snapshot. Changes apply on the next full connection.

Latency probes pass `TuningSettings.NONE`, so they have no tun inbound and do not inherit core
overrides.

## Linked xray-core version

The screen calls `XrayBridge.xrayVersion()` on `Dispatchers.IO`. This reflection surface resolves the
gomobile `XrayVersion`/`xrayVersion` zero-argument method; Go returns `core.Version()` without touching
the global instance or lifecycle mutex. A bridge failure (for example a stale AAR or reflection
failure) renders “unknown” and leaves a `LogRepository` breadcrumb. A successful but blank result also
renders “unknown,” but does not produce that failure log.

The off-main call is required because the first bridge touch loads `gojni` and initializes the Go
runtime. `XrayVersion` is covered by the existing `-keep class xraybridge.**` rule, but release-device
verification remains required because debug compilation does not prove the reflected method survives
R8 and packaging.

## Testing and manual gate

`XrayCoreSettingsTest`, `XrayCorePreferencesTest`, and `ConfigBuilderCoreTest` cover defaults,
wire values, clamping, MTU, IPv6 blocking/order, forced sniffing, domain strategy, and no-op defaults.

On a release APK, confirm the linked core version is shown, then exercise a non-default MTU, IPv6 off,
and routing-forced sniffing after reconnect. Human review is required for the bridge and VPN-service
parts of this feature before merge.
