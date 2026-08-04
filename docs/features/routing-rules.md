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

The health-probe carve-out is its structural twin, and it is **two** rules —
`domain: full:<ConfigBuilder.HEALTH_PROBE_HOST> → proxy` and `ip: <ConfigBuilder.HEALTH_PROBE_IPS> →
proxy` — so auto-failover's watchdog measures the **proxy** rather than whatever else would have carried
the probe. Without it the probe returned 204 with the proxy dead, so failover could never rotate. Both
rules sit ahead of the LAN and ads rules on purpose — an ads → blackhole match would turn the probe
into a permanent failure — and ahead of the preserved config rules, because Xray's router is
first-match.

**Balancer target.** When the imported config routes tun traffic via a `balancerTag` (see inboundTag
reconciliation below), both carve-out halves name that `balancerTag` instead of the first proxy
outbound. Naming a single server would let the watchdog probe server #1 while user traffic rides the
balancer — a healthy probe that proves nothing about the path traffic takes. Ordering is unchanged.

Unlike the DoH guard, the carve-out is emitted in **every** mode. `BLOCKED_ONLY`'s direct catch-all is
only one of three ways the probe can go direct: `EXCEPT_COUNTRY` emits country **direct** rules
(`geoip:ru` can match a Cloudflare anycast address), and every mode preserves the imported config's own
rules, which may route direct too. In `EXCEPT_COUNTRY` the carve-out therefore **deliberately overrides
the user's country-direct policy for that one hostname** — the probe is the app's own diagnostic traffic
and is meaningless unless it traverses the proxy. It only ever moves traffic toward the proxy, so it
costs nothing where it is not needed.

### InboundTag reconciliation (tun-only rewrite)

`replaceJsonInboundsWithTun` replaces every inbound with a single `tun-in`. Provider configs of the
"balancer over N servers" shape key ordinary traffic on `inboundTag: ["socks","http"]`, which can
never match after that rewrite. Leaving those rules in the runtime config is worse than dropping them:
they look healthy, match nothing, and traffic falls to Xray's default outbound (first in the array).

`reconcileInboundTagRules` runs as part of that rewrite:

- Rules that move traffic **toward** the proxy (`balancerTag`, or `outboundTag` naming a non-helper
  outbound) have their `inboundTag` rewritten to `["tun-in"]` so the balancer (or proxy outbound)
  matches again.
- Rules that would send traffic to direct/block are **dropped**, never rewritten. Rewriting them onto
  `tun-in` would activate a previously-dead direct exception and move traffic **away** from the
  proxy — forbidden for this chokepoint.

**Why two rules.** The `domain` rule matches only while sniffing is on: with a tun inbound the
destination is an IP, and nothing supplies a domain unless the inbound sniffs one. `BLOCKED_ONLY`,
`EXCEPT_COUNTRY` and ad-blocking all force sniffing; `PROXY_ALL` with ads off and the user's XRAY
sniffing toggle off does not, and there the `domain` rule was emitted but **inert** — a preserved
imported direct rule claimed the probe and the watchdog read healthy with the proxy dead. The `ip` rule
closes that: it matches `Outbound.Target`, which a tun inbound always populates, so it needs no
sniffing in any posture. (`applyCoreSettings` only ever writes `routeOnly: true` sniffing, which leaves
`Outbound.Target` an IP, so the two coexist.)

The `domain` rule stays because it is the half that survives an address change: the probe URL is a
**hostname**, so DNS follows the host wherever Cloudflare moves it. The remaining residual is therefore
an address change *and* sniffing off, which degrades to the old `domain`-only behaviour rather than
breaking anything. Refresh `HEALTH_PROBE_IPS` to close it. Two things that look like fixes and are not:
forcing sniffing (a global inbound switch with no per-rule scoping — it would also activate the pasted
config's own `domain` rules and send real traffic direct from the user's IP; built, analysed, and
abandoned) and a broad Cloudflare CIDR (routes a large share of the web through the proxy and overrides
the user's country-direct policy far beyond one diagnostic hostname). See
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
DoH guards (including pinned and bracketed-IPv6 resolvers), both halves of the health-probe carve-out
(presence in every mode, proxy direction, both address families, survival of the ping config's geo
stripping, and position ahead of every rule that could divert it — including the imported config's own
direct rules, which its fixture now actually carries), the default posture matching without sniffing,
inboundTag reconciliation (toward-proxy rules retargeted to `tun-in`, direct inboundTag rules dropped),
balancer-aware carve-out targeting, the unsupported-country degrade emitting the `PROXY_ALL` rule set,
helper-outbound safety, and probe stripping.

On-device, exercise each buildable mode with the corresponding geo assets, verify LAN/ad toggles, and
confirm DNS still traverses the selected secure resolver. Missing-asset fallback should remain
connectable and fail closed.
