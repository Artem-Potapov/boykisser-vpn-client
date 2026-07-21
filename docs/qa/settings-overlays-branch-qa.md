# QA Sheet — `feat/settings-overlays` branch

Slotted verification checklist for the settings-overlays branch (Plans 1–3: Mux · DNS · Routing ·
XRAY-core · Config Sanitization · Ping) and the whole-branch-review fixes. Tick `- [x]` as each
scenario is verified; note `❌`/`⚠️` inline with a one-line reason if it fails or is blocked.

**Branch HEAD at authoring:** `c4604e4`. Slots checked below reflect what has actually been run.

---

## 0. Setup & test infrastructure
- [x] **S1** Device/emulator connected (`adb devices`) → SM-S918B (Galaxy S23 Ultra, Android 15)
- [x] **S2** One UI prep: wake + `svc power stayon true` + zero the 3 animation scales → `mWakefulness=Awake`, scales `0` *(left applied on the device)*
- [x] **S3** Unit + lint: `:app:testDebugUnitTest :app:lintDebug` → **445/0**, lint green
- [x] **S4** Instrumented: `:app:connectedDebugAndroidTest` → **36/36, 0 failed, 0 skipped** on SM-S918B / Android 15 (existing-suite regression only; `appops … No UID for androidx.test.services` is a benign orchestrator warning). Filter one class with `-Pandroid.testInstrumentationRunnerArguments.class=<FQN>`, **not** `--tests`
- [x] **S5** Release re-run after the WB-NEW fixes: `:app:assembleRelease` → R8 + lintVital green, 3 signed split APKs + sha256 *(last release build predates the WB-NEW fixes)*

---

## 1. Whole-branch-review fixes (highest priority — freshly landed)

### WB-NEW-1 — IPv6 resolver blocked while IPv6 off (`c4604e4`)
- [V] **N1-a** XRAY → IPv6 **off**; DNS → Custom → `https://[2606:4700:4700::1111]/dns-query` → Save **disabled** + "IPv6 is off…" caption on the URL field
- [V] **N1-b** IPv6 off; hostname URL `https://doh.example.com/dns-query` + IPv6 pin `2606:4700:4700::1111` → Save **disabled** + IPv6-off caption on the pin field
- [V] **N1-c** Same v6 URL but IPv6 **on** → Save **enabled**, no caption; connect → DNS resolves
- [V] **N1-d** v4 custom `https://1.1.1.1/dns-query` with IPv6 off → Save **enabled** (v4 fine with IPv6 off)
- [V] **N1-e (known residual)** v6 resolver saved while IPv6 **on** → XRAY → IPv6 **off** → do **not** reopen DNS → connect → DNS dies (loud/fail-closed/recoverable). Reopening DNS now flags it. *Flagged for maintainer — a full fix needs a gated chokepoint degrade*

### WB-NEW-2 — bracketed-IPv6 custom DoH resolver (`ac68508`)
- [V] **N2-a** DNS → Custom → `https://[2606:4700:4700::1111]/dns-query` (IPv6 on) → **no pin field** (IP literal) → Save → connect → no malformed-bootstrap / core-start failure
- [V] **N2-b** Config Sanitization on a hostname-proxy profile with that resolver → DNS_DOH shows clean `https://[2606:4700:4700::1111]` label, no garbage `[2606` fragment


### WB-NEW-3 — duplicate outbound tag (`ac68508`)
- [V] **N3-a** Import pasted JSON `[vless, {"protocol":"freedom"} (no tag), {"tag":"direct","protocol":"blackhole"}]`, enable a routing mode needing a direct helper (Except-country + bypass LAN) → connect → Xray starts (adopted freedom gets `direct-2`, no duplicate-tag refusal)
  - Verified by importing a pasted JSON whose outbounds are exactly
    `[vless, {"protocol":"freedom"} (no tag), {"tag":"direct","protocol":"blackhole"}]` (inline paste, or
    `scratchpad/n3a-duplicate-tag.json` if saved to disk), then setting Routing = Except-country (RU) +
    bypass LAN and connecting — Xray must start (the adopted `freedom` outbound gets renamed to
    `direct-2`; no duplicate-tag core-start refusal).

### NEW-M2 — localized failure message (`e87215e`)
- [V] **M2-a** App language = Russian → Config Sanitization on a malformed profile → failure text fully Russian ("Конфиг … не удалось обработать."), **no** trailing English "Config could not be processed"
  - Adder-based manual repro (see `docs/features/debug-tools.md`): Settings → About →
    **Debug: unrestricted add** (debug builds only) → name any (e.g. default `DEBUG raw`), config
    `not json` → **Add raw + activate** (waits for insert+activation, then returns to the hub) → set app
    language to Russian (Settings → Language) → open Advanced → **Config sanitization** → failure text
    must be fully Russian ("Конфиг этого профиля не удалось обработать."), no trailing/interpolated
    English tail. Automated equivalent: `SanitizationFailureLocalizationTest`.

---

## 2. Settings overlays — composed chain (end-to-end)
- [V] **C1** Mux ON (VLESS blank-flow tcp) → connect → traffic flows; `mux` block present (verify via Sanitization)
- [V] **C2** Mux ON on an XTLS-Vision / Hysteria2 profile → Sanitization reports Mux `NotApplicable` (not silently applied)
- [V] **C3** DNS preset each of Cloudflare/Google/Quad9/AdGuard → connect → DoH resolves; no plaintext port-53 on LAN (see §3)
!DNS leak test shows DoH queries to Google when Quad9 is active - NOT a guarantee of non-enforcement, but substantiative to look into it. Remote DNS disabled on server - so unlikely to be the suspect; however not fully ruled out. 
UPDATE: evidence points to the fact that Google services/other apps that use DoH are the reasons, and we rightfully don't intercept already secure DoH/DoT (and DoQ, but that's out-of-scope) - Marking GOOD.
- [V] **C4** DNS Custom hostname + Resolve pin → connect → resolves via pinned IP; DoH dialed through proxy
- [V] **C5** Routing Proxy-all / Except-country / Blocked-only(RU) → connect → blocked via proxy, domestic direct, DoH still proxied; no zero-proxy catch-all
- [V] **C6** Blocked-only + **Iran** (no dataset) → degrades to Proxy-all (never all-direct); Sanitization "Proxy everything"
- [V] **C7** XRAY MTU custom (e.g. 1360), IPv6 off, sniffing/domainStrategy toggles → connect → interface MTU == inbound MTU; IPv6 blocked in-tunnel; reflected in Sanitization
- [V] **C8** Stacked: Mux ON + Quad9 + Blocked-only(RU) + bypass LAN + block ads + IPv6 off + sniffing → connect → all compose; no overlay clobbers another

---

## 3. Fail-closed security invariants (must all hold)
- [V] **F1** Paste a config with a `socks`/`http` inbound → connect → rewritten to single `tun` inbound; no local proxy port opened
MINOR - The rewrite happens at paste-time, not build-time, so the sanitization screen tells "already compliant" instead of rewritten.
MINOR 2 - The "api" never gets deleted - not a leak because with XRAY Tun the API is covert.
- [V] **F2** While connected, capture LAN/router traffic during DNS lookups → no plaintext port-53 egress; all DNS is DoH through the proxy - Confirmed with WireShark.
- [⚠ ] **F3** IPv6 off + visit test-ipv6.com while connected → IPv6 **blocked**, not leaked (capture-and-block)
Confirmed, but a super big gotcha - Xray's userspace tun netstack answers ICMP echo locally. So ping/ping6 will always "succeed" while connected, for any address, regardless of whether real traffic can flow — the packets never leave the device (ttl=64, sub-ms RTT is the fingerprint).
!!!ALWAYS use curl/nc TCP to test IPv6 containment - that's the real deal.
- [V] **F4** Paste a config that redirects `log.error` elsewhere → connect → overwritten to app-private `filesDir/logs/xray-core.log`
- [V] **F5** Kill-switch revive (toggle a blocked foreground app) with several overlays enabled → revive reuses the captured snapshot; no mid-session pref bleed

---

## 4. Ping / auto-ping
- [V] **P1** Group ping on a known-good server → `N ms`
- [V] **P2** Ping a server with a wrong REALITY key/shortId → `N/A` (camouflage-fallback: 200≠204)
- [V] **P3** Ping a Hysteria2 server → `N ms`
- [V] **P4** Ping **while connected** to a different server → probes return; live tunnel undisturbed
- [V] **P5** Group ping; change concurrency (1..5) mid-run → ≤ configured concurrent dials; no duplicate probe of an in-flight id after the change
- [V] **P6** Ping settings: out-of-range timeout/concurrency → rejected at `PingPreferences` bounds (1000–30000 / 1–5)
- [V] **P7** Rotate the Ping settings screen with unsaved edits → edits preserved (`rememberSaveable`)
- [V] **P8** Auto-ping on: cold launch with profiles → exactly one union probe after profiles load
- [V] **P9** Auto-ping: rotate / theme change / navigate / resume in same process → **no** second run
- [V] **P10** Auto-ping: force-stop + relaunch → a new automatic run is allowed (process-scoped latch re-armed)
Launches only on "My Profiles" - Subscriptions IGNORED
UPDATE: Race-condition is now fixed and order is top to bottom. Marked GOOD.
- [V] **P11** Ping target validation: `https://…` and bare host rejected; `http://…` (incl. `http://???`) accepted by design → `N/A`

---

## 5. Config Sanitization (read-only diagnostic)
- [V] **SA1** Open on a canonical profile → Security + Global sections; DoH/port-53/ForceIP/forced-log findings present
- [V] **SA2** Change a global setting on a sibling screen → return to the still-alive Sanitization screen → report refreshes (ON_RESUME), Activity not recreated
- [V] **SA3** Open on a profile with UUID / REALITY pbk/sid / credentialed resolver URL → **no** UUID/pbk/sid/user-info/query values rendered (structural labels only)
- [V] **SA4** Confirm **no** Save / Copy / Share / Export action; no writes to prefs/Room/config/session
- [V] **SA5** Hostname-proxy profile lacking the full `https+local` bootstrap pair → DNS_DOH reports `Rewrote` (not `AlreadyCompliant`)
Can confirm that it's rewritten, but the text shows "AlreadyCompliant".
UPDATE: fixed.

---

## 6. Release / R8 / hardware (maintainer hard gate)
- [V] **R1** Install a **release** split APK; open XRAY settings → core-version row shows a real version (proves `xrayVersion` reflection survives R8), not "unknown"
- [V] **R2** Release-APK smoke of all six new screens (Mux/DNS/Routing/XRAY/Sanitization/Ping) + hub Advanced section → render/save under obfuscation; no reflection crash
- [V] **R3** **armv7** hardware (not translated) smoke → app runs; native `libgojni` does not SIGILL (known Houdini-translation history — real armv7 status unverified)
- [V] **R4** Human review of gated files: `ConfigBuilder.kt` (overlay chain + `storedDnsSurvivesPipeline` + WB-NEW-2 `customBootstrapUrl` + WB-NEW-3 `ensureHelperOutbound`), `XrayVpnService.kt`, `XrayBridge.kt`, `xray-go/`

---

## Notes
- The IPv6-off reverse-ordering residual (N1-e) and the whole-branch-review sub-minors (port-range
  parsing in `originalHasPort53Rule`; `routingNeedsDomainRules` raw-mode over-inclusion; main-thread
  `PingPreferences.load` in one LaunchedEffect) are recorded, non-blocking, and left for the
  maintainer's judgment.
- After the WB-NEW fixes, re-run `:app:assembleRelease` before release (S5) — the last release build
  predates them.
