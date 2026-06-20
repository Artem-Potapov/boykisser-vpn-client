# Hysteria2 Support

Maintainer reference for first-class Hysteria2 support: standard share links, subscription entries,
generated Xray JSON, a protocol-aware simple editor, Salamander obfuscation, and the common FinalMask
QUIC controls. Hysteria2 is a **second proxy protocol beside VLESS**; it does not change the runtime
safety model. Every Hysteria2 profile still flows through
[`ConfigBuilder`](../../app/src/main/java/com/justme/xtls_core_proxy/config/ConfigBuilder.kt) and is
normalized to the canonical tun inbound + secure-DNS posture, exactly like VLESS and raw JSON. Its
safety counterparts are [DNS-Leak Enforcement](dns-leak-enforcement.md) (2B) and
[Fail-Closed Startup](failclosed-startup.md) (2A).

> **Support status: implemented and confirmed connecting on-device.** A real Hysteria2 server connects
> and egresses through the proxy on a release build — which proves the QUIC/UDP sockets are covered by the
> global `protect()` dial controller (otherwise the dial would loop and fail; see
> [DNS and `protect()`](#dns-and-protect)). The remaining manual QA items below (LAN DNS-leak capture,
> Salamander, FinalMask, subscription-while-connected) are still recommended before a release.

## Supported inputs

- `hysteria2://` links.
- `hy2://` links (alias).
- Plain and base64-wrapped subscription lists containing Hysteria2 links (mixed with VLESS links and
  JSON configs is fine).
- Full Xray JSON configs containing a `protocol: "hysteria"` outbound with version `2`. The outbound
  does **not** have to be first — the codec scans the `outbounds` array for the first matching one.

Unsupported in this release (explicit non-goals):

- Clash / mihomo YAML subscription bodies.
- `hysteria2+realm://` links.
- Gecko obfuscation. **Salamander is the only supported obfs**; any other `obfs` value is a hard
  validation failure (silently dropping a required obfs would import a profile that cannot connect).

## Architecture

Hysteria2 gets its own model + codec beside the VLESS codec; the VLESS path is untouched. The VPN
service, bridge API, and Room schema are unchanged — Hysteria2 profiles are stored as Xray JSON, the
same opaque `Profile.config` string as converted VLESS links and pasted configs, so **no DB migration**
is needed.

```
share link / JSON ─► detect kind ─► VLESS codec ┐
                                  ├─ Hysteria2 codec ┼─► makeSecureDns (shared) ─► XrayVpnService ─► bridge ─► Xray
                                  └─ raw JSON ────────┘
```

`ConfigBuilder.buildRuntimeConfig` and `toProfileStorageConfig` dispatch by source shape:
`vless://` → VLESS builder, `hysteria2://`/`hy2://` → Hysteria2 builder, everything else → raw JSON.
Both share the `makeSecureDns` / `replaceJsonInboundsWithTun` chokepoint.

## Xray config shape

Xray represents Hysteria2 as `protocol: "hysteria"` with `version: 2` in **both** the outbound
settings and `streamSettings.hysteriaSettings`:

```json
{
  "tag": "proxy",
  "protocol": "hysteria",
  "settings": { "version": 2, "address": "example.com", "port": 443 },
  "streamSettings": {
    "network": "hysteria",
    "security": "tls",
    "tlsSettings": { "serverName": "example.com", "alpn": ["h3"] },
    "hysteriaSettings": { "version": 2, "auth": "password" }
  }
}
```

Generated configs carry the same helper outbounds and routing shape as generated VLESS configs:
`proxy` (Hysteria2) + `direct` (freedom) + `block` (blackhole), with `dns-out`, the port-53-first
rule, and `geoip:private → direct` added by the shared DNS normalizer. SNI defaults to the host when
omitted; ALPN defaults to `h3`.

## Share-link mapping

```
hysteria2://[auth@]host[:port-or-port-list]/?[key=value]&...#fragment
hy2://     [auth@]host[:port-or-port-list]/?[key=value]&...#fragment
```

| URI element | Xray JSON destination |
|---|---|
| user info (`auth`) | `streamSettings.hysteriaSettings.auth` (**required**) |
| host | `settings.address` (**required**) |
| single port | `settings.port` (default `443` if omitted; must be 1–65535) |
| multi-port expr (e.g. `123,5000-6000`) | `settings.port` = first concrete port; full expr → `streamSettings.finalmask.quicParams.udpHop.ports` |
| `sni` | `tlsSettings.serverName` (defaults to host) |
| `alpn` | `tlsSettings.alpn` (defaults to `h3`) |
| `insecure=1` | `tlsSettings.allowInsecure` |
| `pinSHA256` | `tlsSettings.pinnedPeerCertSha256` |
| `obfs=salamander` | `streamSettings.finalmask.udp[].type` |
| `obfs-password` | `streamSettings.finalmask.udp[].settings.password` |
| fragment | display name only — never runtime JSON |

The authority is parsed **manually**, not via `URI.host`/`URI.port`: Java's server-based authority
parser returns `null` for a standard multi-port authority like `example.com:123,5000-6000`. Bracketed
IPv6 (`[addr]:port`) is supported. The `#fragment` is stripped before URI parsing (it is display-name
only), so links with unencoded spaces/emoji in the name still parse. Validation failures (missing auth,
missing host, invalid port, invalid port-hop token, unsupported obfs, or `obfs=salamander` without an
`obfs-password`) throw `IllegalArgumentException` and surface as the existing
invalid/unsupported import UX.

## FinalMask and Salamander

The simple editor exposes the common FinalMask fields directly; the rest stays in a raw JSON escape
hatch. Structured fields map into `streamSettings.finalmask`:

```json
{
  "udp": [{ "type": "salamander", "settings": { "password": "obfs-password" } }],
  "quicParams": {
    "congestion": "brutal",
    "brutalUp": "100mbps",
    "brutalDown": "100mbps",
    "udpHop": { "ports": "20000-50000", "interval": "30" }
  }
}
```

The raw FinalMask JSON field represents the **whole** `streamSettings.finalmask` object. On extract,
known keys populate the structured controls and the full object is kept in the raw field. On save, the
build **starts from the raw object and merges** the structured fields over it, so unknown / newly-added
FinalMask keys (e.g. `maxIdleTimeout`) are preserved, not discarded. This raw-passthrough is a
deliberate hedge against upstream Xray FinalMask churn.

## Protocol-aware settings editor

`ServerSettingsActivity` detects the editable protocol of the current config and renders the matching
simple editor (VLESS or Hysteria2), falling back to **Advanced JSON only** for unknown/mixed/unsupported
configs. Advanced JSON stays available for every profile.

`detectEditableServerProtocol` **delegates to the same codec parse calls the editor uses to populate its
fields** — `ProfileConfigCodec.extractVlessProfile`, then `Hysteria2ConfigCodec.parseUri` /
`parseProfileFromJson` — establishing the invariant: **a protocol is offered iff its form can actually
be built.** It does *not* hand-inspect `outbounds[0]`. This matters because:

- VLESS / Hysteria2 JSON whose proxy outbound is **not first** (e.g. `direct`/`block` listed before it)
  is still editable, because the codecs scan all outbounds.
- A `protocol: "hysteria"` outbound that fails the version-2 / required-field check resolves to
  `ADVANCED_ONLY` instead of opening a **blank** Hysteria2 form (the field-population path swallows parse
  failures into defaults, so without this gate malformed JSON would look like an empty new profile).

Switching Advanced → Simple is gated on a successful re-parse; if the JSON can't be downgraded to a
simple form, the user stays in Advanced with a validation message rather than getting a wrong/empty
simple form.

Hysteria2 simple editor fields: host, UDP port, auth/password, SNI, ALPN, allow-insecure, pinned cert
SHA-256, Salamander password, congestion, upload bandwidth, download bandwidth, UDP hop ports, UDP hop
interval, and the raw FinalMask JSON escape hatch.

## DNS and `protect()`

Hysteria2 configs pass through `ConfigBuilder.makeSecureDns` unchanged: DoH-only resolver, `dns-out`,
port-53-first rule, and `sockopt.domainStrategy: ForceIP` merged onto the Hysteria2 proxy outbound — so
the server hostname bootstrap resolves over DoH, failing closed. Hysteria2 adds **no** DNS policy of its
own; `makeSecureDns` remains the single source of truth.

**`protect()` over QUIC/UDP — confirmed on-device.** Loop-avoidance relies on the Go bridge's one global
`RegisterDialerController`, which only covers sockets dialed by Xray's **default system dialer**
([failclosed-startup.md](failclosed-startup.md)). Whether Xray's Hysteria2 QUIC/UDP outbound dials
through that default dialer — and is therefore `protect()`'d out of the tun — cannot be proven by the JVM
test suite, so it was the highest runtime unknown. It is now **confirmed on a release build against a real
Hysteria2 server**: traffic egresses through the proxy with no dial-to-self loop, which is only possible if
the QUIC/UDP sockets are `protect()`'d. Re-verify on-device if the bridge or xray-core's dialer changes.

## Testing

JVM unit tests (all pure, run under `:app:testDebugUnitTest`):

| Test | Covers |
|---|---|
| [`Hysteria2ConfigCodecTest`](../../app/src/test/java/com/justme/xtls_core_proxy/Hysteria2ConfigCodecTest.kt) | URI parsing (`hysteria2://`/`hy2://`, multi-port), validation failures (missing auth, unsupported obfs, invalid port), URI→Xray JSON shape, `pinSHA256` mapping, Salamander + FinalMask write paths, extract/merge preserving unknown FinalMask keys, simple-fields round-trip. |
| [`settings/EditableServerProtocolTest`](../../app/src/test/java/com/justme/xtls_core_proxy/settings/EditableServerProtocolTest.kt) | `detectEditableServerProtocol`: proxy-not-first VLESS/Hysteria2 JSON, malformed/non-v2 Hysteria2 → `ADVANCED_ONLY`, URI + blank + unknown-protocol guards. |
| [`ConfigBuilderTest`](../../app/src/test/java/com/justme/xtls_core_proxy/ConfigBuilderTest.kt) / [`ConfigBuilderDnsTest`](../../app/src/test/java/com/justme/xtls_core_proxy/ConfigBuilderDnsTest.kt) | `toProfileStorageConfig`/`buildRuntimeConfig` accept Hysteria2 links; tun inbound + secure DNS + `ForceIP` applied to the Hysteria2 outbound. |
| [`add/ClipboardAddRouterTest`](../../app/src/test/java/com/justme/xtls_core_proxy/add/ClipboardAddRouterTest.kt) | Clipboard classifies valid Hysteria2 links as `ClipboardKind.Hysteria2`; missing-auth / bad-obfs → `Invalid`. |
| [`subs/SubscriptionBodyParserTest`](../../app/src/test/java/com/justme/xtls_core_proxy/subs/SubscriptionBodyParserTest.kt) | Plain and base64 Hysteria2 link lists; display name from fragment, host/port, and JSON `settings.address:port`. |

Manual device QA (release build, real Hysteria2 server):

1. ✅ Install the **release** APK (exercises R8 / reflection paths).
2. ✅ Add a baseline Hysteria2 profile from a `hysteria2://` / `hy2://` link; connect; confirm traffic
   exits through the proxy (perceived public IP is the server's) — **confirmed**, which also proves
   `protect()` covers the QUIC/UDP sockets.
3. Capture LAN DNS and confirm **no plaintext query for the Hysteria2 server hostname** appears.
4. Refresh a subscription while connected; confirm it rides the tunnel.
5. Connect a Salamander profile.
6. Connect a FinalMask QUIC-params profile (port hop and/or bandwidth fields).
7. Explicit stop, then reconnect — normal lifecycle still works.

Items 1–2 are confirmed on-device; 3–7 remain recommended before a release.

## Known limitations

- Only Salamander obfs; only the listed FinalMask fields are structured (everything else via raw JSON).
- No multi-port runtime fan-out beyond what Xray's `udpHop` does — the first concrete port seeds
  `settings.port` and the full expression is handed to `udpHop.ports`.
