# DNS Settings: Secure Resolver Overlay

Maintainer reference for Advanced → DNS. This feature selects the resolver used after the mandatory
secure-DNS normalization documented in [dns-leak-enforcement.md](dns-leak-enforcement.md); it does not
weaken or bypass that chokepoint.

## Resolver choices

`ConfigBuilder.makeSecureDns` first removes plaintext resolvers, installs the port-53 → `dns-out`
rule, and sets `ForceIP` on the proxy outbound. `applyDns` then runs as a global overlay:

- **From config** is a no-op over that already-secured result. It does not mean “trust plaintext
  imported DNS”; an unsafe or absent resolver has already become the canonical Cloudflare pair.
- Cloudflare, Google, Quad9, and AdGuard replace the unscoped resolver list with their primary and
  secondary IP-literal HTTPS endpoints.
- Custom accepts one `https://` URL. A hostname endpoint requires a pinned IP; the screen can resolve
  it as a convenience and flags a missing pin, but there is no Save gate — the URL and pin **autosave
  on every change**. `applyDns` re-checks the persisted draft, but the URL and the pin are **not**
  handled alike: an invalid URL no-ops, a missing pin does not. See the precise rules below before
  assuming a backstop covers both.

For a hostname-addressed proxy, `makeSecureDns` creates two domain-scoped `https+local://` bootstrap
entries. A preset swap rewrites those entries pairwise to the selected resolver's primary/secondary
IPs, so bootstrap failover remains intact and the proxy hostname is not leaked to the previous
resolver. Config-owned domain-scoped secure resolvers are preserved.

For custom DNS, an invalid or blank HTTPS URL makes `applyDns` return without changing the
already-canonical secure resolver. Hostname-pin handling is different: the settings screen flags a
missing IP-literal pin, but `DnsPreferences` loads persisted URL and pin strings verbatim and the
runtime only checks whether the pin is nonblank. With a valid hostname URL and valid pin, `dns.hosts`
maps the hostname to that pin and the scoped bootstrap preserves the custom URL's port and path. A
blank persisted pin still installs the custom URL as the unscoped resolver while retaining the
existing scoped bootstrap; a nonblank malformed pin is applied as supplied. Do not describe corrupt
pin state as an unconditional fail-closed no-op.

## Query strategy and overlay order

An explicit resolver can select `UseIP`, `UseIPv4`, or `UseIPv6`; `applyDns` overwrites
`dns.queryStrategy`. “From config” keeps the secured config's strategy and disables the strategy
control in the UI.

Core IPv6-off is the final writer: `applyCoreSettings` forces `UseIPv4` regardless of the DNS-screen
choice. The screen re-reads the core IPv6 preference on resume and disables the strategy control when
that final override is active.

DNS runs before routing because `BLOCKED_ONLY` needs DoH-guard rules derived from the effective
resolver:
`applyFragmentation → applyMux → applyDns → applyRouting → applyCoreSettings`.

## IPv6-off degrade of a v6-only custom resolver

When IPv6 is off (XRAY screen) and the custom resolver is reachable only over IPv6 — the DoH URL host
is an IPv6 literal, or its host is a hostname pinned to an IPv6 literal — the DNS screen shows an
**informational heads-up caption, not a blocking gate**. The value still autosaves, and the runtime
**degrades** it to the Cloudflare v4 preset at the `applyDns` chokepoint so DNS never strands. This
holds at connect time from the captured session snapshot even if the user turned IPv6 off later and
never reopened the DNS screen. See
[dns-leak-enforcement.md](dns-leak-enforcement.md) for the chokepoint and the residual it closes.

## Persistence and session semantics

The DNS screen **autosaves** — the resolver/strategy controls persist immediately and the custom
URL/pin persist on every change, with no Save button; the `‹` arrow is plain back navigation.
`DnsPreferences` stores resolver, custom URL, pinned IP, and strategy in `xray_prefs`; unknown resolver
or strategy names fall back to their respective `DnsSettings.FROM_CONFIG` defaults, while custom URL
and pin strings are not validated during load. `XrayVpnService` captures the loaded value once into
`sessionTuning`, reuses it across kill-switch revive, and resets it on full teardown. Changes apply on
the next full connection.

Latency probes use `TuningSettings.NONE`, retaining the canonical secure-DNS posture without applying
the user's resolver overlay.

## Testing and manual gate

`ConfigBuilderDnsResolverTest` covers no-op “From config,” preset replacement, pairwise bootstrap,
custom pinning with port/path preservation, config-owned scoped resolvers, and strategy override.
`DnsSettingsTest`, `DnsPreferencesTest`, and `DohUrlTest` cover models, persistence, URL validation,
and resolution helpers. Routing tests verify custom hostname pins and IPv6 resolver addresses are
guarded in `BLOCKED_ONLY`.

On-device, reconnect with each resolver class and confirm name resolution. For a hostname custom DoH
endpoint, verify the pin path and confirm no plaintext proxy-hostname DNS appears on the LAN.
