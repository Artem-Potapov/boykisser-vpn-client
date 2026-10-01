# QA — 2B ConfigBuilder DNS-Leak Enforcement (manual on-device)

**Build under test:** branch `feat/2a-failclosed-protect-startup`, HEAD `6f418b7` (versionName 1.0.4DEV), debug.
**Already installed** on the attached device (`adb` shows it). Reinstall the current build with:
`./gradlew.bat :app:installDebug`

**What 2B does (the behavior you're verifying):** every config the app runs is normalized to a secure DNS posture — a DoH-only resolver (Cloudflare `https://1.1.1.1/dns-query`, plaintext resolvers stripped), all port-53 traffic routed into Xray's DNS module (`dns-out`), and the **proxy server's own hostname resolved over DoH** via `sockopt.domainStrategy = "ForceIP"` (the bootstrap-leak fix). Pasted dirty configs warn; subscription dirty configs are auto-fixed and badged.

**Already auto-verified (host-side, no action needed):** the build installs over an existing install and launches with no crash; the Room v3 schema opens cleanly; full unit suite + `assembleRelease` (R8 + lint-vital) are green.

---

## Test 1 — ForceIP DNS-leak proof (the authoritative test) ★ most important

**Why:** This is the core 2B guarantee and the one thing unit tests can't prove. It must be observed on the **network**, not in the app.

**Prerequisite:** a working profile whose server is specified by a **HOSTNAME**, not a raw IP (e.g. `vless://…@my.server.example:443?…`). If your server is reached by IP there is nothing to resolve and this test is N/A.

**Setup — pick ONE observation method:**

- **Method A — controlled resolver (easiest definitive):** point the device's Wi-Fi DNS at a resolver whose query log you can read (Pi-hole, AdGuard Home, or `dnsmasq --log-queries`). Make sure the device actually uses it (disable "Private DNS"/DoT in Android settings → it would bypass your resolver).
- **Method B — packet capture (gold standard):** capture on the router / a mirrored port (Wireshark/`tcpdump`), display filter `dns`. Do NOT use an on-device capture app (PCAPdroid etc.) — it occupies the VPN slot and conflicts.

**Steps:**
1. Add the hostname profile (paste the `vless://` link → it should add with **no** dialog, since generated/clean configs are SECURE).
2. Clear your resolver log / start the capture.
3. **Connect** the VPN. Wait until State: Connected and traffic flows (open a website).

**PASS:**
- The VPN connects and browses normally, AND
- **No plaintext DNS query for your server's hostname** appears in the resolver log / capture. (You SHOULD see a TLS/HTTPS connection to `1.1.1.1:443` instead — that's the DoH query.)

**FAIL:**
- Your server's hostname appears as a cleartext DNS query (UDP/TCP 53) in the log/capture → ForceIP/DoH not taking effect.
- Connection fails entirely even though `1.1.1.1` is reachable → ForceIP may be failing closed incorrectly; capture logs and report.

**Optional A/B (demonstrates the fix):** install a pre-2B build (`git stash` aside, `git checkout 6e74691 -- .` is NOT needed — just build from before `c499cd0`), repeat with the same hostname server, and confirm the hostname **does** appear as a cleartext query. Then reinstall `6f418b7` and confirm it no longer does.

**Quick structural pre-check (no capture needed):** after adding the hostname profile, open its edit/details screen and confirm the stored JSON contains: `"dns":{"servers":["https://1.1.1.1/dns-query","https://1.0.0.1/dns-query"]…}` (primary + bootstrap-free failover endpoint), an outbound `{"tag":"dns-out","protocol":"dns"}`, a routing rule `{"port":53,"outboundTag":"dns-out"}` placed **first**, and on the proxy outbound `"streamSettings":{…"sockopt":{"domainStrategy":"ForceIP"}}`. This proves the config shape; Test 1 proves it works at runtime.

---

## Test 2 — Migration 2→3 with real data

**Why:** the auto-check only proved the schema opens; preserving existing profiles across the upgrade needs a populated **v2** database.

**Steps:**
1. Install a **pre-2B** build (any build before commit `c499cd0` — that DB is at version 2). Easiest: `git worktree`/checkout a pre-2B commit in a scratch clone, or use a previously-archived APK.
2. In that build, add **2–3 profiles** (manual + a subscription) and confirm they appear.
3. Without uninstalling, install the current build over it: `./gradlew.bat :app:installDebug`.
4. Launch the app.

**PASS:** app launches with no crash; **all profiles from step 2 are still present**; their `sanitizedDns` badge state is `false` (they predate flagging).
**FAIL:** crash on launch (Room migration error), or the profile list is empty / reset (data loss).

> Note: a *fresh* install (no prior data) does NOT test this — it creates the DB at v3 directly. You must upgrade a populated v2 install.

---

## Test 3 — Pasted dirty-DNS → warn-and-fix dialog

**Why:** verifies the user-facing gate for actively-leaking pasted configs.

**Dirty sample to paste** (routes port-53 to a direct outbound — replace `YOUR_SERVER`/`YOUR_UUID` only if you want it connectable; the dialog fires regardless):

```json
{"outbounds":[{"protocol":"vless","tag":"proxy","settings":{"vnext":[{"address":"demo.example","port":443,"users":[{"id":"11111111-1111-1111-1111-111111111111","encryption":"none"}]}]}},{"protocol":"freedom","tag":"direct"}],"routing":{"rules":[{"type":"field","port":53,"outboundTag":"direct"}]}}
```

**Steps & expected:**
1. Copy the JSON above, in-app use the paste/add path (the `+` or clipboard import).
2. **Expected:** the **"Unsafe DNS in this config"** dialog appears with **Fix it** / **Don't add**.
3. Tap **Fix it** → **PASS:** a profile is added and shows the **"DNS fixed"** badge under its name.
4. Paste the same JSON again → this time tap **Don't add** → **PASS:** no profile is added and a toast ("Not added — this config would leak DNS") shows.
5. Switch the app language to Russian (Settings) and repeat once → **PASS:** dialog/badge/toast strings are Russian (Небезопасный DNS…, DNS исправлен, etc.) — confirms the `values-ru` strings.

**FAIL:** no dialog appears (a leaking config was silently added), or the dialog appears but "Fix it" adds a profile without the badge, or strings are missing/English in RU mode.

**Clean control:** paste a normal clean `vless://` link → **PASS:** added with **no** dialog and **no** badge.

---

## Test 4 — Subscription auto-fix + badge

**Why:** subscriptions have no user present, so dirty entries are auto-fixed and flagged.

**Steps:**
1. Host a subscription body containing at least one **raw-JSON entry with the dirty pattern** above (port-53 → direct) plus one clean `vless://` entry. (Any URL the app can fetch; base64-wrapped is fine.)
2. Add the subscription (cloud icon) and let it refresh.

**PASS:** the dirty entry becomes a profile carrying the **"DNS fixed"** badge; the clean `vless://` entry has **no** badge; both are connectable.
**FAIL:** the dirty entry is dropped, unbadged, or badged when it shouldn't be.

---

## Test 5 — No regression on normal connect

**Steps:** connect to a normal working profile (one that connected fine before 2B).
**PASS:** connects and browses exactly as before — no dialog, no badge, no added latency beyond first-resolve.
**FAIL:** connection breaks, or DNS resolution stops working (sites don't resolve).

> Reminder of an accepted limitation (not a bug): while connected, **LAN / `.local` names won't resolve via a local resolver** — all port-53 is hijacked to DoH by design. Flag it only if it's a problem for your use.

---

## Report back

For each test: PASS / FAIL + any capture excerpt or screenshot. Test 1 (DNS-leak proof) and Test 3 (dialog) are the two that most directly validate 2B; Tests 2/4/5 are important but lower-risk. Once you're satisfied, tell me and I'll re-run `assembleRelease` as a final formality and we proceed to the 2A+2B merge on your go-ahead.
