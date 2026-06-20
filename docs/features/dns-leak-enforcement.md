# DNS-Leak Enforcement (2B)

Maintainer reference for the application-layer DNS-leak guarantee: every config the app runs is
normalized to a single secure DNS posture, regardless of where it came from. Its socket-layer
counterpart is [Fail-Closed Startup](failclosed-startup.md) (2A — `protect()` + whole-app tunneling);
the two ship and reason together.

## Why this exists

This client runs Xray's **own** TUN (no sing-box, no `tun2socks`), so nothing in the stack
automatically guarantees DNS is tunneled or encrypted. Two leaks are possible:

1. A config that routes port-53 to a `direct`/`freedom` outbound leaks **every domain the user
   visits** in cleartext to the local network.
2. With a hostname-specified proxy server, Xray resolves the **server's own hostname** via the OS/LAN
   resolver unless told otherwise — leaking the server's identity (and defeating the proxy's purpose).

For an anti-surveillance tool both are "deadly" failures. 2B makes
[`ConfigBuilder`](../../app/src/main/java/com/justme/xtls_core_proxy/config/ConfigBuilder.kt) the
single chokepoint that guarantees a safe DNS posture for **every** config, from any source.

## The governing principle: normalize everything, classify only for UX

```
every config ─────► makeSecureDns(config)  ─► canonical secure shape (ALWAYS applied, idempotent)
                          │
            dnsDiagnosis(config) ─► DnsStatus  ─► drives UX ONLY (dialog / badge), never the fix
```

`makeSecureDns` is what makes a config safe; `dnsDiagnosis` only decides whether to *interrupt the
user*. Splitting them is what lets the policy be both safe (the fix always runs) and quiet (a config
that's merely missing DoH is fixed silently, with no nag).

## The canonical secure shape

`makeSecureDns` rewrites any config to:

```jsonc
"dns": { "servers": ["https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query"], "queryStrategy": "UseIP" }
"outbounds": [
  { "tag": "proxy", "protocol": "vless" | "hysteria", ...,
    "streamSettings": { ..., "sockopt": { "domainStrategy": "ForceIP" } } },  // server-name bootstrap → DoH
  ... ,
  { "tag": "dns-out", "protocol": "dns" }
]
"routing.rules": [
  { "type": "field", "port": 53, "outboundTag": "dns-out" },                 // FIRST: hijack all DNS
  ...existing non-port-53 rules preserved (e.g. geoip:private → direct)...
]
```

Three invariants, each closing one leak:

1. **`dns.servers` is DoH-only.** Keeps any already-present secure resolver (prefix-matched against
   `https://`, `tls://`, `quic://`, `h3://`, `h2c://` — e.g. a user's Yandex DoH), **strips every
   plaintext resolver**, and injects Cloudflare **only if none survive**. There is no plaintext
   fallback — fail-closed (see *Why DoH-by-IP*).
2. **port-53 → `dns-out`, placed first.** Every app DNS query is intercepted into Xray's DNS module
   regardless of which resolver the app aimed at. Pre-existing port-53 rules are dropped and this one
   is prepended.
3. **`sockopt.domainStrategy: ForceIP` on the proxy outbound.** Forces the **server's own hostname**
   to resolve through the DNS module (→ DoH), failing closed if DoH can't resolve it. This is
   load-bearing: verified against pinned Xray source
   (`transport/internet/dialer.go:251`), the dialer resolves a domain via Xray's DNS module **only
   when `sockopt.DomainStrategy.HasStrategy()` is true** — with the default `AsIs`, the server
   hostname goes to the OS/LAN resolver. `ForceIP` is *merged* into any existing `sockopt`, not
   clobbered. Servers pinned by raw IP are unaffected (nothing to resolve).

`ForceIP` (not `UseIP`) on the bootstrap is deliberate: `UseIP` falls back to `AsIs` (OS resolver)
when DoH fails, reintroducing the LAN leak; `ForceIP` fails the connection instead — consistent with
the fail-closed resolver.

## Why DoH-by-IP (and not a `hosts` bootstrap map)

`https://1.1.1.1/dns-query` is the **bootstrap-free canonical form**, not a shortcut. Cloudflare's TLS
cert carries `IP Address:1.1.1.1` / `1.0.0.1` as SANs, so the bare IP validates cryptographically and
there is **no hostname to resolve** — the chicken-and-egg "I need DNS to find my DNS server" problem
is sidestepped, not worked around. A `hosts` static map (`cloudflare-dns.com → 1.1.1.1`) is only
*required* when you choose a **hostname** endpoint; e.g. Google's `https://8.8.8.8/dns-query` passes
TLS but returns HTTP 400 because its front-end demands `Host: dns.google`, forcing the hostname +
hosts-map path. We avoid that entirely.

**`1.0.0.1` is a bootstrap-free failover.** Injected as the second server so Xray's `serialQuery`
(servers tried in priority order, fall back on failure; `enableParallelQuery` stays **off**) reaches it
if `1.1.1.1` is unreachable. It is the **same operator**, so it covers an IP/route outage, *not*
Cloudflare-wide blocking — true cross-operator failover would reintroduce the hostname/hosts-map
machinery and was judged not worth it.

## Sources and data flow

| Source | Behavior |
|---|---|
| Generated `vless://` | Secure **by construction** — `buildXrayJson` delegates its final shape to `makeSecureDns`. |
| Pasted / manual / clipboard | `addProfile` gates on `dnsDiagnosis`: **DIRTY** → warn-and-fix dialog (*Fix it* = `makeSecureDns` + insert `sanitizedDns=true`; *Don't add* = nothing inserted, toast); ABSENT/SECURE → `makeSecureDns` silently, insert `sanitizedDns=false`. All three `addProfile` call sites flow through this one gate. |
| Subscription | No user present → dirty entries are **auto-fixed** at parse and the resulting profile carries `sanitizedDns=true` (a "DNS fixed" badge), so a malicious subscription is visible. |
| Connect (runtime) | `buildRuntimeConfig → fromJson` always normalizes (sanitize inbounds → `makeSecureDns` → assert), so **pre-2B stored profiles and any directly-inserted config are auto-secured on connect** — no data migration of stored configs needed. |

## The `DnsStatus` classifier and its deliberate asymmetry

`dnsDiagnosis` returns `DIRTY` **only** when a port-53 rule targets a `direct`/`freedom` outbound (the
"actively leaking" signal). It does **not** flag a plaintext-but-merely-present `dns` block as DIRTY.
This is intentional and documented in the code KDoc: `makeSecureDns` neutralizes *all* port-53 routing
regardless, so the runtime is always safe; widening the classifier would only expand the user-facing
nag/badge without improving safety. **Do not widen `dnsDiagnosis` to mirror `makeSecureDns`.**

## Components

| File | Change |
|---|---|
| [`config/ConfigBuilder.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/config/ConfigBuilder.kt) | The heart: `enum DnsStatus`, `dnsDiagnosis`, `makeSecureDns`, `replaceJsonInboundsWithTun`, `const CLOUDFLARE_DOH` + `CLOUDFLARE_DOH_SECONDARY`; `buildXrayJson` ends with `makeSecureDns`; `fromJson` does sanitize-inbounds → normalize → assert. Top-level `DirtyDnsException`. |
| [`db/Profile.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/db/Profile.kt) | `sanitizedDns: Boolean = false`. |
| [`db/AppDatabase.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/db/AppDatabase.kt) | Version **2 → 3** + additive `MIGRATION_2_3` (`ALTER TABLE profiles ADD COLUMN sanitizedDns INTEGER NOT NULL DEFAULT 0`). |
| [`subs/SubscriptionBodyParser.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/subs/SubscriptionBodyParser.kt) | `ParsedConfig.sanitizedDns`; per-entry diagnose → normalize → flag. **Whole-JSON body support** (see below). |
| [`subs/SubscriptionRefreshCoordinator.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/subs/SubscriptionRefreshCoordinator.kt) | Propagates `sanitizedDns` into the built `Profile`. |
| [`state/VpnViewModel.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/VpnViewModel.kt) | `dnsWarning: StateFlow<DnsWarning?>`, `confirmDnsFixAndAdd()`, `dismissDnsWarning()`; `addProfile` gates on DIRTY. |
| [`MainActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/MainActivity.kt) | Observes `dnsWarning` → AlertDialog; "DNS fixed" badge in `ProfileRow`. |
| `res/values/strings.xml`, `res/values-ru/strings.xml` | Dialog + badge copy (en + ru; ru mandatory or lint-vital fails). |

### Bundled concern: inbound sanitization

`replaceJsonInboundsWithTun` strips whatever inbounds a config ships (real-world 3x-ui panel configs
carry local `socks`/`http`/`mixed` inbounds and no tun inbound) and forces the canonical tun inbound.
This **replaced** the old `rejectLocalProxyInbounds`, which *rejected* socks/http — so such configs now
import instead of erroring. Folded into 2B deliberately because it shares the `fromJson` path.

### Subscription parsing fixes (surfaced during on-device QA)

- **Whole-JSON bodies.** `parseBody` was line-oriented (`split("\n")`, keep lines with `{`), which
  shatters a single pretty-printed JSON config into per-line fragments that each fail to parse →
  **zero profiles**. `extractCandidates`/`wholeJsonCandidates` now detect a whole-body JSON document
  (`{…}` single config / `[…]` array of configs) **before** the line split and keep it intact;
  malformed JSON falls back to the line path. Pre-existing parser limitation, not a 2B regression — it
  was masked because every prior JSON test used single-line JSON.
- **`remarks` as display name.** `deriveJsonDisplayName` now prefers a config's top-level `remarks`
  field (e.g. `"🇵🇱Польша PL-W1"`) over the generic first-outbound tag (`"proxy"`).

## Error handling

- **Runtime backstop — normalize, then assert.** `fromJson` always `makeSecureDns`-es, then throws
  `DirtyDnsException` **only** if the result is still DIRTY. Because `makeSecureDns` drops every
  port-53→freedom rule, that throw is **unreachable** in normal operation — it is a pure **regression
  tripwire** that fires only if `makeSecureDns` itself regresses. It does not break a user's connection
  over an upstream config quirk; it fixes the config and runs it.
- **Pasted decline is fail-safe** — nothing inserted, no half-state.
- **Migration is additive** (`sanitizedDns` defaults to `0`), so an upgrade cannot fail on existing
  data; existing profiles read back `sanitizedDns=false`.

## Known limitations

- **LAN / `.local` names don't resolve while connected** (accepted). The port-53-first rule hijacks
  *all* DNS to DoH, so a local resolver can't be reached. Acceptable for the threat model; flagged for
  users who need LAN name resolution.
- **`UseIPv4` not used.** `queryStrategy` is `UseIP` (dual-stack). `UseIPv4` would suppress AAAA for
  v4-only servers — a latency/cleanliness tweak, not a leak — deferred.
- **Failover is same-operator only** (see *Why DoH-by-IP*).

## Testing

- **[`ConfigBuilderDnsTest`](../../app/src/test/java/com/justme/xtls_core_proxy/ConfigBuilderDnsTest.kt)**
  — `dnsDiagnosis` ABSENT/SECURE/DIRTY (incl. port string-range `"53-54"`); `makeSecureDns` is SECURE,
  DoH-only (plaintext stripped), preserves an existing DoH resolver, injects **both** Cloudflare
  servers when none survive, `dns-out` present, port-53→`dns-out` first, idempotent, ForceIP on proxy;
  `buildRuntimeConfig` integration incl. dirty-JSON → normalized-SECURE (not throwing).
- **[`ConfigBuilderTest`](../../app/src/test/java/com/justme/xtls_core_proxy/ConfigBuilderTest.kt)** —
  inbound sanitization (socks/http → single tun inbound; `replaceJsonInboundsWithTun` coverage).
- **[`SubscriptionBodyParserTest`](../../app/src/test/java/com/justme/xtls_core_proxy/subs/SubscriptionBodyParserTest.kt)**
  — dirty raw-JSON entry flagged+secured; clean `vless://` not flagged; multi-line pretty-JSON body;
  JSON array body; `remarks` wins over outbound tag.
- **DB migration** — Room validates the schema at `assembleDebug`; the populated v2→v3
  upgrade-with-data check is **manual** (instrumented Room migration testing is a justified TDD
  exception).
- **ForceIP DNS-leak proof** — the sockopt is a structural unit assertion, but that it *actually*
  routes the server-name bootstrap over DoH is Xray runtime behavior, confirmed **on-device**: connect
  a hostname-specified server, confirm no plaintext DNS for that hostname appears on the LAN. Manual
  gate — see [`docs/qa/2b-dns-enforcement-qa.md`](../qa/2b-dns-enforcement-qa.md).

## Future work

- An **"enable sniffing" toggle** / remote (server-side) DNS for DNS-poisoning resistance when a user
  runs a controlled resolver. In a TUN setup it needs `sniffing` (`destOverride`) to recover domains
  from TLS SNI. Deliberate future work, explicitly out of 2B scope.
