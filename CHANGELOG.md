# Changelog

All notable changes to XTLS Core Proxy are documented here. The format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions track the app's `versionName`.

## [2.2.1R] — 2026-07-16

Tag: `2.2.1-Release`. Minor release.

### Fixed
- DNS enforcement and bootstrapping follow-ups from the 2.2.0PRE cycle.
- VPN lifecycle concurrency hardening: stale-callback gating, kill-switch mid-revive
  replay, and user-stop path moved off the lifecycle lock to prevent ANR.

### Changed
- The main screen no longer hosts a log panel; profiles list fills the reclaimed space.
- `ConfigBuilder` forces the `log` object on every runtime config (level + app-private
  error-file path), overwriting rather than merging.

## [2.2.0PRE] — 2026-07-14

Tag: `2.2.0-PreRelease` (pending). Major release.

## Dedicated Logs screen + a HAPP/Hiddify-style Settings hub, VPN-lifecycle concurrency hardening,
and a crash fix for sharing large logs.

### Added
- **Dedicated Logs screen** (Settings → Diagnostics → Logs). Xray-core now writes its own error log to
  an app-private file, which the app tails into the on-screen buffer — previously only app-authored
  lines were visible and diagnosing a failed connection meant reaching for `adb logcat`. The screen
  offers a **session-stable log level** (Debug / Info / Warning / Error; captioned "Applies from the
  next connection", so a running tunnel's verbosity can't shift mid-flight) and a **live
  log-buffer-size** picker (1 000 / 2 000 / 5 000 / 10 000 lines) that trims the on-screen list
  immediately, plus **Copy / Share / Export** actions.
- **Sectioned Settings hub** (inspired by HAPP / Hiddify): UI, Tunnel, Advanced, Diagnostics, and About
  sections built from reusable `SettingsSectionHeader` / `SettingsRow` components. Debug builds show
  greyed placeholder rows for planned settings; release builds hide them — and the whole Advanced
  section — entirely.
- **About screen**: app version, purpose, a GitHub source-code link, and license / acknowledgements.

### Changed
- **The main screen no longer hosts a log panel** — the profiles list fills the reclaimed space; logs
  moved to their dedicated screen.
- **`ConfigBuilder` now forces the `log` object** on every runtime config (level + app-private
  error-file path), overwriting rather than merging, so a pasted or subscription-sourced config cannot
  redirect Xray's own log writes elsewhere. This joins secure-DNS and inbound sanitization as a third
  fail-closed normalization on the same chokepoint.

### Fixed
- **Sharing or copying a large log no longer crashes the app or drops the VPN.** Copy and Share inlined
  the whole buffer through a single ~1 MB Binder transaction; at the 10 000-line preset this threw
  `TransactionTooLargeException`, and because the VPN service shares the app process the uncaught throw
  killed the process — dropping the tunnel and wiping the log buffer. Copy/Share are now byte-bounded to
  the newest lines that fit (with a "log is large" explainer offering to include just the recent tail),
  while **Export streams the full log unbounded**. A defensive guard keeps any unexpected share failure
  from taking down the tunnel.
- **VPN kill-switch / lifecycle concurrency hardening.** Async lifecycle callbacks are gated on a
  monotonic session epoch, so a stale callback from a torn-down session can't publish or tear down a
  newer session's tunnel. A kill-switch event that lands mid-revive is now deferred and replayed instead
  of being lost by the edge-triggered monitor; disabling the kill switch mid-revive no longer strands
  the tunnel paused; and a queued kill is ignored once the feature is turned off. The user-stop path was
  moved off the lifecycle lock to prevent a UI freeze / ANR when disconnecting during connect.

### Security
- **Log redaction boundary.** The on-disk `xray-core.log` is raw and app-private; **every** user-facing
  surface — the on-screen list, Copy, Share, and Export — reads exclusively from the in-memory
  `LogRepository` buffer, which redacts UUID / `publicKey` / `shortId` values before a line is ever
  shown or shared. No code path reads or shares the raw file directly, and the large-log fix narrows the
  Copy/Share payload without changing that source.
- **Known follow-up (tracked in `docs/features/logs-screen.md`):** `sanitize()` currently recognizes the
  app's own secret shapes; broaden it to cover non-UUID credentials (e.g. a Hysteria2 password) that raw
  core output could surface at Debug before wide release.

## [2.0.1R] - 2026-06-25

Tag: `2.0.1Release`. Minor release.

### Fixed
When connecting to a domain as the address, ForceIP option told XRAY the address must be
resolved first, but because of the new DNS-proofing the only option is the XRAY's DNS,
which.. required an active tunnel.
Now the domain is 'surgically extracted' and is being bootstrapped (the only thing that
doesn't go through the XRAY's DNS) via DoH. 

## [2.0.0R] - 2026-06-21

No bugs found, exactly the same as 2.0.0R, except for the tag.
Tag: `2.0.0-Release`.

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
