# Changelog

All notable changes to XTLS Core Proxy are documented here. The format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions track the app's `versionName`.

> **Note - `1.0.4` (the `1.0.4DEV` / `1.0.4Release`) was never published** — its
> changes are folded into **2.0.0PRE** `1.0.2R` is the last released version.

## [2.0.0PRE] — 2026-06-20

Tag: `2.0.0-PreRelease`. Major release.

## First-class Hysteria2 support + fail-closed leak-proofing overhaul 
(secure DNS + socket-level loop-avoidance) and a batch of import/UX/packaging improvements.

### Added
- **First-class Hysteria2 (HY2) support.** Import `hysteria2://` / `hy2://` share links from the
  clipboard, manual paste, or subscriptions, or paste raw Xray JSON whose proxy outbound is
  `protocol: "hysteria"` (version 2). A protocol-aware simple editor exposes host, UDP port,
  auth/password, SNI, ALPN, allow-insecure, and pinned certificate SHA-256, plus **Salamander**
  obfuscation and common **FinalMask** QUIC controls (congestion, upload/download bandwidth, UDP
  port-hopping, hop interval) with a raw FinalMask JSON escape hatch. Standard multi-port / port-hop
  authorities are supported. Confirmed connecting on-device against a real Hysteria2 server.
- **Subscription import improvements.** Whole-document JSON subscription bodies (single object or array)
  are parsed intact instead of being shattered line-by-line; base64-wrapped bodies and per-line base64
  are handled; display names prefer the URI fragment, then `host:port`, then a config's top-level
  `remarks`.
- **Kill-on-foreground: consent gate + exposed-state alert.** Enabling the kill switch now requires an
  explicit consent dialog, and while the tunnel is paused/exposed a high-importance notification
  surfaces that state.
- **Per-ABI release packaging.** Release APKs are split per ABI (x86/x86_64 dropped for release;
  emulator x86_64 support retained), named `boykisser-<abi>-<buildType>-<version>.apk`, with
  `.sha256sum` files emitted. Windows AAR build fixed (`checklinkname`) and xray-core bumped.

### Changed
- **Accepted inputs widened** to VLESS, Hysteria2, or raw Xray JSON (previously VLESS + JSON).
- **Foreign inbounds are sanitized into the canonical `tun` inbound** rather than rejected, so
  real-world panel exports carrying local `socks`/`http`/`mixed`/`dokodemo` inbounds now import instead
  of erroring. Applied at **both** storage and connect, so the stored config is already canonical.
- **TUN MTU lowered 1500 → 1400**, via a single shared constant for both the OS TUN interface and the
  Xray tun inbound, leaving headroom for outbound encapsulation (notably Hysteria2 QUIC/UDP +
  Salamander) so inner packets stop fragmenting under DF.

### Fixed
- **Hysteria2 links with unencoded spaces/emoji in the `#name` fragment** are no longer rejected on
  clipboard add, silently dropped on subscription import, or forced into Advanced mode in the editor.
- **`obfs=salamander` without an `obfs-password`** is now a hard validation failure instead of silently
  building a no-obfs config that cannot connect.
- **Protocol-aware editor detection** now matches the codecs: VLESS/Hysteria2 JSON whose proxy outbound
  is not listed first is still editable, and malformed / non-v2 Hysteria2 JSON opens Advanced mode
  instead of a blank simple form.
- **Hysteria2 simple-editor saves** preserve `sockopt` (the secure-DNS `ForceIP`) and unknown
  `streamSettings` / FinalMask keys when merging edits back into JSON.
- VPN foreground-service notification shows immediately; re-posts on Android 14+ swipe-dismissal; a
  dismiss/re-post race is serialized; the notification icon is unified.

### Security
- **Fail-closed secure DNS (DNS-leak enforcement).** Every config is normalized to a DoH-only resolver
  (Cloudflare `1.1.1.1` + `1.0.0.1`, bootstrap-free, injected only when no secure resolver survives),
  all port-53 traffic is hijacked into Xray's DNS module (`dns-out`, rule placed first), and
  `sockopt.domainStrategy: ForceIP` is forced onto the proxy outbound so the server's own hostname
  resolves over DoH and fails closed if it can't. A warn-and-fix dialog flags configs that ship
  actively-leaking DNS on paste; subscriptions auto-fix and badge them. Adds a `Profile.sanitizedDns`
  column (Room migration 2 → 3).
- **Socket-level loop-avoidance via `VpnService.protect()`.** A single global Xray dial controller (Go
  bridge) carves Xray's own sockets out of the tun, so the **whole app** is tunneled — subscription
  fetches and update checks no longer bypass the tunnel in cleartext. Confirmed on-device to also cover
  Hysteria2's QUIC/UDP sockets.
- **Resilient, fail-closed startup.** `onStartCommand` returns `START_REDELIVER_INTENT` and reconnects
  the active profile after a process crash or always-on boot; routing is a total decision so an
  Android 14+ notification dismissal can't trigger a spurious auto-connect.

## [1.0.2R] and earlier

Last published release before the 2.0.0 line. See the git history for details; this changelog begins
tracking notable changes at 2.0.0PRE.
