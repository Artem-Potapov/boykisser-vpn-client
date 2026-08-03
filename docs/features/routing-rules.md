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
sending all traffic direct. Unavailable UI choices are labeled, and the screen autosaves — every
change persists the sanitized value (there is no Save button).

## Rule construction and ordering

`applyRouting` owns the baked `geoip:private` LAN rule: it removes that exact owned shape, then
re-adds it only when the global toggle is on. It preserves imported non-owned rules. The generated
order is:

1. mandatory port-53 → `dns-out`;
2. DoH guard for `BLOCKED_ONLY`;
3. health-probe carve-out, **every mode**;
4. optional LAN direct;
5. optional ads → blackhole;
6. mode-specific country rules;
7. preserved config rules;
8. final direct catch-all, only for supported `BLOCKED_ONLY`.

The DoH guard keeps the effective resolver on the proxy side despite the mode's direct default. It
covers resolver IPs, resolver hostnames, and `dns.hosts` pinned IPs. Redirecting `freedom` outbounds
are not reused as the direct helper.

The health-probe carve-out is its structural twin: `domain: full:<ConfigBuilder.HEALTH_PROBE_HOST> →
proxy`, so auto-failover's watchdog measures the **proxy** rather than whatever else would have carried
the probe. Without it the probe returned 204 with the proxy dead, so failover could never rotate. Both
rules sit ahead of the LAN and ads rules on purpose — an ads → blackhole match would turn the probe
into a permanent failure.

Unlike the DoH guard, the carve-out is emitted in **every** mode. `BLOCKED_ONLY`'s direct catch-all is
only one of three ways the probe can go direct: `EXCEPT_COUNTRY` emits country **direct** rules
(`geoip:ru` can match a Cloudflare anycast address), and every mode preserves the imported config's own
rules, which may route direct too. In `EXCEPT_COUNTRY` the carve-out therefore **deliberately overrides
the user's country-direct policy for that one hostname** — the probe is the app's own diagnostic traffic
and is meaningless unless it traverses the proxy. It only ever moves traffic toward the proxy, so it
costs nothing where it is not needed.

One residual, stated rather than papered over: it is a `domain` rule, so it matches only while sniffing
is on. `BLOCKED_ONLY`, `EXCEPT_COUNTRY` and ad-blocking all force sniffing; `PROXY_ALL` with ads off and
the user's XRAY sniffing toggle off does not, and there the rule is emitted but **inert**. See
[`auto-failover.md`](auto-failover.md).

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
DoH guards (including pinned and bracketed-IPv6 resolvers), the health-probe carve-out (presence in
every mode, proxy direction, and position ahead of every rule that could divert it), the
unsupported-country degrade emitting the `PROXY_ALL` rule set, helper-outbound safety, and probe
stripping.

On-device, exercise each buildable mode with the corresponding geo assets, verify LAN/ad toggles, and
confirm DNS still traverses the selected secure resolver. Missing-asset fallback should remain
connectable and fail closed.
