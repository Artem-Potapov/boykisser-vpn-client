# QA Sheet — Settings Hub + Logs Screen (branch `feat/settings-hub-logs`)

Manual/on-device checklist for the dedicated Logs screen, the sectioned Settings hub, and the
kill-switch concurrency hardening. **JVM unit tests already cover** the pure logic (config log-forcing,
buffer coercion, timestamp stripping, session-lifecycle predicates) — this sheet is for what tests
**can't** reach: real device behavior, UI, and the async kill-switch/VPN lifecycle.

Legend: `[ ]` to do · **(RISK)** = concurrency/safety path, do not skip · **(unit-tested too)** = a
green JVM test exists but confirm the real path anyway.

---

## A. Kill-switch + VPN lifecycle — the high-risk paths (test these first)

Setup: arm the kill-switch for at least one "controlled" app (Kill switch settings → pick an app),
connect the VPN, and have a second non-controlled app handy to switch to.

- [✔] **(RISK) Basic kill:** open the controlled app while connected → tunnel pauses, loud "exposed"
  heads-up notification fires, status shows paused.
- [✔] **(RISK) Basic revive:** leave the controlled app (go home / open a non-controlled app) → tunnel
  reconnects automatically, exposed heads-up is dismissed, status returns to connected.
- [✔] **(RISK) Kill *during* revive (the original Important #1):** open controlled app (pause) → leave
  it (revive starts) → **quickly re-open the controlled app while it is reconnecting** → tunnel must
  end **PAUSED with the exposed heads-up**, NOT connected-with-controlled-app-foreground. Repeat a few
  times; the revive window is short, so bounce quickly.
- [✔] **(RISK) Disable kill-switch *during* revive (re-review Important #1):** trigger pause → leave the
  app so a revive starts → **turn the kill-switch feature off (or clear its app list) while it is
  reviving** → tunnel must end **connected/normal**, NOT stranded PAUSED. If it parks PAUSED with no way
  back, that's the bug — report it.
- [?] **(RISK) Kill queued as feature is disabled (the `killSwitchMonitor` gate):** open controlled app
  and immediately toggle the kill-switch **off** → the tunnel must NOT be torn down for the now-disabled
  feature (log shows "ignoring queued kill … (feature disabled)"). - The tunnel does NOT get torn down (success), but nothing in logs (maybe we are capturing XRAY logs only?...)
- [✔] **(RISK) Rapid app bouncing:** with kill-switch armed, rapidly alternate controlled ↔
  non-controlled app ~10×. Tunnel must always end **paused while the controlled app is foreground** and
  connected otherwise — never the reverse.
- [✔] **(RISK) Disconnect during connect (Fix B / ANR):** start a connection and press **Disconnect
  while it is still connecting** (geo-file load makes this window longer on first connect) → the UI must
  respond **instantly** (no freeze/ANR), and the tunnel must tear down cleanly (no orphaned
  notification, no half-connected state).
- [✔] **(RISK) Screen off/on during connect:** connect, then toggle the screen off and on during the
  connect → no freeze, no crash, kill-switch polling resumes correctly.
- [✔] **Kill-switch re-enable recovery:** disable the feature while paused → tunnel auto-revives
  (no manual stop/start needed).

## B. Logs screen

Reach it via Settings → Diagnostics → Logs.

- [?] **(RISK) Redaction on Copy/Share/Export:** connect with a real config, generate some core log
  output, then Copy (and Share, and Export to file) → confirm UUIDs read `<redacted-uuid>` and
  `publicKey`/`shortId` values read `<redacted>` — **never** the raw secret. (This is the security
  boundary; the raw on-disk `xray-core.log` is never what these actions read.)
- [✔] **Level = Debug shows core output:** set level Debug, connect → chatty Xray-core lines appear,
  each with a **single** correctly-formatted `HH:mm:ss.SSS` timestamp (no doubled timestamps).
- Note that some __were__ doubled simply because of how chatty Debug is. Known trade-off because showing logs up to micro/nanoseconds is awful UX.
- [✔] **(unit-tested too) Level is session-stable:** while connected, change the level in the Logs
  screen → the **running** session's verbosity must NOT change. Then disconnect and reconnect → the new
  level now takes effect. (Caption says "Applies from the next connection".)
- [✔] **Level survives kill-switch pause/revive:** connect at Debug, trigger a kill-switch pause/revive →
  log lines keep flowing to the same buffer, level unchanged.
- [✔] **(unit-tested too) Buffer size is live:** change the buffer preset to a smaller value → the
  on-screen list trims **immediately** (no reconnect needed).
- [✔] **Buffer persists:** set a preset, force-stop the app, reopen → the buffer size is remembered.
- [ ] **(RISK, FIXED — re-verify) Copy/Share at the 10 000-line preset with a long-running Debug
  session** → **no crash, VPN stays connected, buffer NOT cleared.** Previously both threw
  `TransactionTooLargeException` and killed the process (tunnel dropped + logs wiped — the two
  consequences you confirmed). Now Copy/Share are byte-bounded (`LogShareBudget`, 256 KiB newest tail):
  - [ ] Copy at 10 000 lines → the **"Log is large" explainer dialog** appears; tap **Copy recent** → a
    toast reads "Copied the most recent N of M lines"; pasting elsewhere shows the newest lines. No crash.
  - [ ] Share at 10 000 lines → same explainer, **Share recent** opens the chooser with the recent tail.
    No crash, VPN stays up.
  - [ ] Under budget (e.g. 1 000-line idle buffer) → Copy/Share act **directly**, no dialog.
  - [ ] **Export as file** still writes the **full** log (it streams — never hit the bug).
- [ ] **Dialog rows fully tappable** → in both the Xray-log-level and Log-buffer-size dialogs, tapping
  the **text label** (not only the radio circle) selects the option.
- [ ] **Clear moved into the ⋮ menu** → the top bar shows only the ⋮ overflow (no bare "X"); the menu
  lists Copy / Share / Export, a divider, then **Clear**. Clear empties the on-screen buffer.
- [✔] **Jump-to-latest FAB** scrolls to the newest line; the list auto-follows when already at the bottom.

## C. Settings hub

- [✔] **Debug build — all 5 sections render:** UI, Tunnel, Advanced, Diagnostics, About.
- [✔] **Debug placeholders look disabled:** each placeholder row is greyed (~38% alpha), shows the
  "DEBUG" badge, has no chevron, and is not clickable.
- [✔] **Real rows navigate:** Language, Split tunnel, Kill switch, Logs, About each open their screen.
- [ ] **Sideload row opens in-place:** it shows the sideload dialog as an overlay, does NOT navigate away.
- [ ] **(RISK) Release build — placeholders GONE:** install the `assembleRelease` APK and confirm ONLY
  the real rows show (Language, Split tunnel, Kill switch, Logs, Sideload warning, About) and the whole
  **Advanced section is absent**. (BuildConfig.DEBUG gating — must not leak into release.)

## D. About screen

- [ ] Version line shows the correct `BuildConfig.VERSION_NAME`.
- [ ] **GitHub link** opens `https://github.com/Artem-Potapov/boykisser-vpn-client` in the browser.
- [ ] License/acknowledgement text renders.

## E. Main screen

- [✔] The old log panel is gone; the profiles list fills the freed space; nothing looks truncated or
  empty where the panel used to be.

## F. Localization

- [ ] Switch app language to Russian (UI settings → Language) → the new Logs screen, Settings hub,
  and About strings are all translated (no raw `settings_*` / `logs_*` keys or English fallthrough).

## G. Build / release sanity

- [ ] `:app:testDebugUnitTest` green.
- [ ] `:app:assembleRelease` green (R8 + lint-vital) and the release APK **installs and runs** — a green
  build does not prove the R8-obfuscated app works; exercise a connect + the Logs screen on the release
  build specifically (the bridge/reflection paths only fail at runtime).

---

### Known deferred (not bugs — decided follow-ups)
- Redaction breadth: `sanitize()` only covers UUID/`publicKey`/`shortId`; a non-UUID secret (e.g. a
  Hysteria2 password) in a raw core line at Debug could reach the shareable buffer. Audit + broaden
  before wide release. (Tracked in `docs/features/logs-screen.md`.)
- Cosmetics left for the IDE / a later pass: unused `Arrangement` import, `mutableIntStateOf` for the
  buffer state, `AboutActivity` `Uri.parse`→`toUri`, "About/About" section-vs-row label.
