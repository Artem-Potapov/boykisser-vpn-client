# Routing Rules: Global Geo/LAN/Ads Overlay

Maintainer reference for Advanced → Routing rules and `ConfigBuilder.applyRouting`.

## Modes and defaults

The persisted user default is Proxy everything, country RU, LAN bypass on, ad blocking off. Routing
has three exclusive modes:

- **Proxy everything** — no country split; optional LAN direct and ads block rules still apply.
- **Proxy all except country** — selected country lists route direct, everything else uses the proxy.
- **Proxy only blocked in country** — selected blocked lists use the proxy and a final
  `tcp,udp → direct` catch-all handles everything else.

RU has both direct and blocked datasets. IR currently has direct lists but no blocked dataset, so
`BLOCKED_ONLY + IR` is unsupported.

## Fail-closed availability handling

`RoutingPreferences.load` and the settings screen derive required `.dat` files from the actual rule
tables and sanitize unbuildable choices:

- missing `geoip.dat` turns LAN bypass off;
- missing `geosite.dat` turns ad blocking off;
- a mode whose required general or country-specific file is absent falls back to Proxy everything;
- unsupported IR `BLOCKED_ONLY` also falls back to Proxy everything.

`ConfigBuilder.effectiveRoutingMode` repeats the critical unsupported-country backstop. It never emits
the direct catch-all unless blocked data exists; otherwise an unavailable list would fail open by
sending all traffic direct. Unavailable UI choices are labeled, and Save persists the sanitized value.

## Rule construction and ordering

`applyRouting` owns the baked `geoip:private` LAN rule: it removes that exact owned shape, then
re-adds it only when the global toggle is on. It preserves imported non-owned rules. The generated
order is:

1. mandatory port-53 → `dns-out`;
2. DoH guard for `BLOCKED_ONLY`;
3. optional LAN direct;
4. optional ads → blackhole;
5. mode-specific country rules;
6. preserved config rules;
7. final direct catch-all, only for supported `BLOCKED_ONLY`.

The DoH guard keeps the effective resolver on the proxy side despite the mode's direct default. It
covers resolver IPs, resolver hostnames, and `dns.hosts` pinned IPs. Redirecting `freedom` outbounds
are not reused as the direct helper.

Domain and geosite rules require sniffing. `routingNeedsDomainRules` therefore forces the final core
overlay to enable route-only sniffing for every non-Proxy-all mode and for ad blocking. The XRAY screen
shows that forced-on state while preserving the user's own sniffing preference.

## Probe and session behavior

The overlay order is
`applyFragmentation → applyMux → applyDns → applyRouting → applyCoreSettings`. Core IPv6-off inserts
its `::/0 → block` rule immediately after port 53.

`XrayVpnService` captures `RoutingPreferences` into `sessionTuning` once per full connection and
reuses it on kill-switch revive. Changes require a reconnect. Ping probes pass no routing overlay and
`toPingTestConfig` strips imported `geoip:`, `geosite:`, and `ext:` rules because the throwaway probe
core has no geo-asset directory.

## Testing and manual gate

`RoutingSettingsTest` covers list tables, required-file derivation, blocked support, sniffing
requirements, and availability fallback. `RoutingPreferencesTest` covers persisted sanitization.
`ConfigBuilderRoutingTest` covers rule order, LAN ownership, supported/unsupported `BLOCKED_ONLY`,
DoH guards (including pinned and bracketed-IPv6 resolvers), helper-outbound safety, and probe stripping.

On-device, exercise each buildable mode with the corresponding geo assets, verify LAN/ad toggles, and
confirm DNS still traverses the selected secure resolver. Missing-asset fallback should remain
connectable and fail closed.
