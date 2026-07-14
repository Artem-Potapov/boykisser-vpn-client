# Logs Screen: File+Tail Pipeline, Session-Stable Level, Redaction Boundary

Maintainer reference for the dedicated Logs screen: how Xray-core's own log output reaches the app,
why the log level can't change mid-connection, and the hard boundary between the raw on-disk log and
the redacted in-app buffer that Copy/Share/Export actually read.

## Why this exists

Before this feature, `LogRepository` only held app-authored lines (`LogRepository.append(...)` calls
sprinkled through `XrayVpnService`); Xray-core's own log output (handshake errors, protocol-level
noise) never reached the user. Debugging a failed or flaky connection required `adb logcat` against a
release build, which most users can't do. This feature makes Xray-core write its own log file, tails
that file into the same redacting `LogRepository` buffer the UI already renders, and exposes a level +
buffer-size picker as a dedicated screen reachable from the Settings hub (see
[`settings-hub.md`](settings-hub.md)) → Diagnostics → Logs.

## The pipeline

```
ConfigBuilder.buildRuntimeConfig(input, log)
        │  forceLog(): overwrites the config's "log" object
        ▼
runtime config: { "log": { "access": "none", "loglevel": <level>, "error": <filesDir>/logs/xray-core.log } }
        │  Go bridge / Xray-core writes to that path
        ▼
filesDir/logs/xray-core.log   (app-private, RAW/unredacted)
        │  XrayCoreLogTailer polls every 400 ms, tracks a byte offset, strips Xray's own timestamp
        ▼
LogRepository.append(line)    (timestamps + REDACTS, caps at maxLines)
        │
        ▼
LogsScreen (Compose) / Copy / Share / Export
```

### `ConfigBuilder.forceLog` — the config-side chokepoint

[`config/ConfigBuilder.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/config/ConfigBuilder.kt)'s
`buildRuntimeConfig(input, log = LogSettings(XrayLogLevel.WARNING, null))` runs `forceLog(base, log)` as
the last step, after the protocol-specific builder (`fromVlessUri` / `fromHysteria2Uri` / `fromJson`) has
already produced a config. `forceLog` is private and **overwrites** the `log` object outright — it does
not merge — specifically so a pasted/imported config cannot redirect Xray's own log writes elsewhere:

```kotlin
private fun forceLog(configJson: String, log: LogSettings): String {
    val root = JSONObject(configJson)
    val logObj = JSONObject()
        .put("access", "none")
        .put("loglevel", log.level.wire)
    if (log.errorFilePath != null) logObj.put("error", log.errorFilePath)
    root.put("log", logObj)
    return root.toString()
}
```

`access` is hard-coded to `"none"` (Xray's per-request access log is never enabled — it isn't tailed and
would just be noise/PII in the file). When `log.errorFilePath == null`, no `error` key is emitted at
all, so Xray's error log has nowhere configured to go (this is the shape `toPingTestConfig` uses, via
`LogSettings(XrayLogLevel.NONE, null)` — the throwaway probe core never writes a log file).

This is a third fail-closed normalization alongside secure-DNS
([`dns-leak-enforcement.md`](dns-leak-enforcement.md)) and inbound sanitization: every runtime config,
regardless of source, gets its `log` object forced to the caller-supplied `LogSettings` — a pasted or
subscription-sourced config cannot opt out of it or point logging somewhere else.

### `LogSettings` and `XrayLogLevel`

[`config/LogSettings.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/config/LogSettings.kt):

```kotlin
enum class XrayLogLevel(val wire: String) {
    DEBUG("debug"), INFO("info"), WARNING("warning"), ERROR("error"), NONE("none");
    companion object {
        fun fromName(name: String?): XrayLogLevel =
            entries.firstOrNull { it.name == name } ?: WARNING
    }
}

data class LogSettings(val level: XrayLogLevel, val errorFilePath: String?)
```

- The ladder mirrors Xray's own `log.loglevel` values exactly (`wire` is the literal string Xray
  expects) — Xray has no `trace` level, so there are only these five.
- `NONE` is an internal-only value: it turns logging off entirely and is never offered in the level
  picker's UI list (see `LogsActivity` below); it exists only for `toPingTestConfig`'s throwaway probe
  core.
- `fromName` is the fallback path for a persisted (`SharedPreferences`) enum name: unknown, legacy, or
  `null` values fall back to `WARNING`, which is also `buildRuntimeConfig`'s own default-parameter
  value — the "quiet by default" posture is consistent whether the caller omits `log` entirely or reads
  a corrupted/missing preference.

### `filesDir/logs/xray-core.log` and `XrayCoreLogTailer`

[`vpn/XrayVpnService.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt)'s
`startVpn` creates the log file once per connection attempt:

```kotlin
val logFile = File(filesDir, "logs/xray-core.log").apply { parentFile?.mkdirs() }
runCatching { logFile.writeText("") }   // truncate once per session (best-effort)
    .onFailure {
        LogRepository.append(
            "Core log file truncate failed; Xray-core logs may be unavailable this session"
        )
    }
sessionLogFile = logFile
sessionLog = LogSettings(LogPreferences.getLogLevel(this@XrayVpnService), logFile.absolutePath)
```

This runs **once**, before the first `bringUpTunnel(profile)` call — not inside it — so the file is
truncated exactly once per `startVpn` session, and the same `File` handle (`sessionLogFile`) is reused
by `reviveTunnel()`'s later `bringUpTunnel(profile)` calls (kill-switch pause/revive re-runs
`bringUpTunnel`, not `startVpn`). The file is **not** re-truncated on revive — a paused/resumed session
keeps appending to the same file.

[`log/XrayCoreLogTailer.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/log/XrayCoreLogTailer.kt)
polls that file on a `Dispatchers.IO` coroutine every **400 ms** (`POLL_MS`), tracking a byte offset so
it only reads newly-appended bytes (`RandomAccessFile.seek(offset)`). Each poll reads at most
`MAX_READ_PER_POLL` (64 KiB) into a **fixed** buffer — it never allocates `ByteArray(len - offset)`
for an arbitrarily large append. Incomplete lines are held as raw bytes by
[`BoundedLogLineAccumulator`](../../app/src/main/java/com/justme/xtls_core_proxy/log/BoundedLogLineAccumulator.kt)
(capped at `MAX_PENDING_LINE_BYTES` = 64 KiB); decoding to UTF-8 happens only for complete
(`\n`-terminated) lines, so a multibyte character split across poll/chunk boundaries is never forced
through a partial decode (no spurious U+FFFD). If the file shrinks between polls (rotation or the
core reopening it truncated) the offset resets to 0 and the accumulator is `reset()` — this is the
only case that re-reads from the start.

**Overflow policy (deterministic, non-secret-leaking):** if an unterminated pending line would exceed
`MAX_PENDING_LINE_BYTES`, the pending bytes are discarded and further input is skipped until the next
`\n` (resync). The oversized fragment is **never emitted**, including as a truncated prefix — so a
malformed/oversized line cannot retain or leak secrets through the log path.

Each emitted line is first passed through `stripXrayTimestamp`, which strips Xray's own leading
`2006/01/02 15:04:05(.000000)`-style stamp via a regex
(`^\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}(\.\d+)?\s+`) before handing the line to `LogRepository.append`,
which stamps its **own** `HH:mm:ss.SSS` timestamp — this avoids every Xray-core line showing two
timestamps. Non-matching lines pass through unchanged.

`XrayVpnService` assigns a monotonically increasing **session epoch** under its lifecycle `lock` when
`startVpn` admits a fresh connection. A background initial-start callback owns only the epoch captured
at admission; `running` alone is not sufficient because a full stop can be followed by another start
before that callback arrives. `stopVpn` clears the active epoch under the same lock before teardown.

The tailer starts only when `running` is true **and** the callback epoch equals the active epoch.
The same identity guard applies to initial-start failure state/errors, CONNECTED, notification,
kill-switch monitor/observer installation, and their stale callbacks. Global TUN/Xray startup and
full teardown also run under the lifecycle lock, so a stale session cannot publish or tear down a
newer session's resources. The active session also owns an explicit tunnel transition state:
`STARTING → CONNECTED → PAUSED → REVIVING → CONNECTED`; full stop resets it to `STOPPED`. A revive
atomically reserves `PAUSED → REVIVING` before its asynchronous database/config work, so a duplicate
same-epoch request sees `REVIVING` and does nothing. Before `Builder.establish()`, the service requires
both the matching epoch/expected transition and `tunInterface == null`.

**Deferred kill during revive (safety).** A kill-switch event can only tear down a `CONNECTED` tunnel.
If a kill lands while the same session is mid-revive (`REVIVING`), `killTunnel` does **not** drop it —
the foreground monitor is edge-triggered (`if (previous == newForeground) return`) and would never
re-fire it, so the revive would otherwise commit `CONNECTED` with the kill-listed app foregrounded and
no pause. Instead the label is recorded in `pendingKillLabel` (mutated only under `lock`) and, once the
revive commits `CONNECTED`, the success path clears it and re-dispatches `killTunnel(epoch, label)` on
`tunnelOpScope` so the normal pause + exposed heads-up runs. `pendingKillLabel` is cleared on **every**
path that voids the deferred event: `stopVpn` teardown (so it can't replay into a later session) **and**
the kill-switch-disable branch of `applyKillSwitchPreferences` (so turning the feature off mid-revive
doesn't replay a now-stale kill — which, with the monitor already gone, would strand the tunnel `PAUSED`
with nothing left to revive it). One deliberate trade remains in the replay design: if the kill-listed
app *leaves* the foreground during the revive window, the commit still replays the deferred kill and the
tunnel ends `PAUSED` with the exposed heads-up until that app next foregrounds and leaves again —
fail-closed, and the monitor stays alive. The predicate lives in `SessionLifecycleDecision.kt`
as `shouldDeferKillDuringRevive(...)` (current session AND `tunnelState == REVIVING`). `reviveTunnel`'s
coroutine body is wrapped in the same `try/catch(Throwable) → failRevive(...)` shape `killTunnel` uses,
so an unexpected throw from its `getById`/`append`/`bringUpTunnel` can't escape into the SupervisorJob
scope and crash the process.

```kotlin
bringUpTunnel(
    profile = profile,
    log = initialLog,
    sessionEpoch = sessionEpoch,
    expectedState = SessionTunnelState.STARTING,
).onSuccess {
    val committed = synchronized(lock) {
        if (!ownsTunnelTransitionLocked(sessionEpoch, SessionTunnelState.STARTING)) {
            false
        } else {
            sessionLogFile?.let { f ->
                if (logTailer == null) {
                    logTailer = XrayCoreLogTailer(f).also { it.start() }
                }
            }
            sessionTunnelState = SessionTunnelState.CONNECTED
            true
        }
    }
    if (!committed) return@onSuccess // stale callback never tears down another session
}
```

`stopVpn` extracts and stops `logTailer` under that same lock (`tailerToStop = logTailer;
logTailer = null`) — **not** in the kill-switch pause path (`killTunnel` /
`tearDownTunnelLocked`). This is deliberate: the tailer (and the underlying log file) survive
kill-switch pause/revive, so log lines continue flowing to the same `LogRepository` buffer across a
pause — only a full VPN stop tears the tailer down. The kill-switch listener/revive callback carries
that same session epoch and reuses its captured `LogSettings`, so revive remains part of the original
session and cannot adopt a newer session's profile or log posture. `XrayCoreLogTailer`'s own class
doc states this explicitly: "Deliberately survives kill-switch pause/revive."

## Session-stable log level

The log level a connection runs with is captured **once**, in `startVpn`, from
`LogPreferences.getLogLevel(this)` at the moment the session starts — not re-read on every
`bringUpTunnel` call:

```kotlin
@Volatile private var sessionLog: LogSettings = LogSettings(XrayLogLevel.WARNING, null)
...
sessionLog = LogSettings(LogPreferences.getLogLevel(this@XrayVpnService), logFile.absolutePath)
```

`bringUpTunnel(...)` receives this captured session log posture (the initial call passes
`initialLog`; revive passes its captured session value) and always builds the runtime config with it:

```kotlin
val configJson = ConfigBuilder.buildRuntimeConfig(profile.config, log)
```

Because `reviveTunnel()` calls `bringUpTunnel(...)` with the same captured log posture (not
`startVpn`), a kill-switch pause/revive cycle reuses `sessionLog` unchanged — **the level cannot change
mid-session**, even if the user opens the Logs screen and picks a different level while connected. A
level change made in the UI only takes effect on the **next** `startVpn` call (i.e. the next full
connect), because that's the only place `sessionLog` is re-derived from the persisted preference. This
is the load-bearing invariant behind the Logs screen's "Applies from the next connection" caption (see
below).

## The redaction boundary

**The on-disk log file (`filesDir/logs/xray-core.log`) is raw and unredacted.** It is Xray-core's own
error-log output, written directly by the Go/native layer with no sanitization pass — it can contain
whatever Xray itself puts in its log lines. Its only protection is that it lives in the app's private
storage (not shared storage, not world-readable), same trust boundary as the rest of `filesDir`.

**Every user-facing surface — the on-screen list, Copy, Share, and Export — reads exclusively from the
in-memory `LogRepository.logs` buffer, never the file.** `LogRepository.append(line)` sanitizes every
line before it enters the buffer:

```kotlin
private fun sanitize(raw: String): String {
    return raw
        .replace(Regex("""([0-9a-fA-F]{8}-[0-9a-fA-F-]{27})"""), "<redacted-uuid>")
        .replace(Regex("""("publicKey"\s*:\s*")[^"]+(")"""), "$1<redacted>$2")
        .replace(Regex("""("shortId"\s*:\s*")[^"]+(")"""), "$1<redacted>$2")
}
```

`LogsActivity`'s Copy/Share/Export actions all read from `LogRepository.logs` (the Compose
`collectAsState()` of the redacted `StateFlow`) — never the file. Export streams the full buffer
(`logs.joinToString("\n")`) to the chosen `content://` output; Copy/Share hand a byte-bounded newest
tail of that same redacted buffer to `LogShareBudget.bound(logs)` (see the next subsection). Either
way the source is the sanitized in-memory buffer: there is no code path from the Logs screen (or
anywhere else in the app) that reads `filesDir/logs/xray-core.log` directly. The tailer is the only
reader of that file, and it always routes through `LogRepository.append` (hence through `sanitize`)
before a line becomes visible anywhere.

**Do not add a code path that reads or shares `filesDir/logs/xray-core.log` directly** — doing so would
bypass the redaction that Copy/Share/Export currently guarantee.

### Copy/Share are byte-bounded (Binder transaction limit)

Copy (`ClipboardManager.setPrimaryClip`) and Share (`startActivity(ACTION_SEND, EXTRA_TEXT=...)`) both
**inline their entire payload through a single Binder transaction**, whose per-process buffer is ~1 MB
shared across all in-flight IPC. Handing the full buffer (e.g. the 10 000-line preset after a long Debug
session) to either throws `TransactionTooLargeException`. Because `XrayVpnService` has **no
`android:process`** in the manifest — it runs in the *same* process as the UI — that uncaught throw
kills the whole process: the foreground VPN drops **and** the in-memory `LogRepository` buffer is wiped.
The observed symptom ("share the log → VPN disconnects and the log clears") is the signature of that
single-process death, not two separate bugs.

[`log/LogShareBudget.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/log/LogShareBudget.kt)
(`bound(lines, maxBytes = 256 KiB)`) fixes it at the source: it keeps the **newest** lines whose joined
UTF-8 size fits under a conservative quarter-of-the-ceiling budget (always at least the last line), and
reports `includedLines`/`totalLines` so the UI can tell the user what was dropped. When the tail is
truncated, `LogsActivity` shows a small explainer dialog ("this log has N lines… only the most recent M
will be included; use Export for the full log") offering **Copy/Share recent** or **Cancel**; when it
fits, the action runs directly. **Export is deliberately exempt** — it writes to a `content://` output
*stream*, so the payload never rides a Binder parcel and has no size limit; it remains the full-log path.

As defense-in-depth, the actual `setPrimaryClip`/`startActivity` calls are wrapped in `runCatching`
(`performCopy`/`performShare`) so an *unexpected* `TransactionTooLargeException` (or a missing share
target) degrades to a toast rather than an uncaught throw that would again take down the process. The
byte budget is the primary fix; the `runCatching` is the backstop.

> **KNOWN FOLLOW-UP — broaden `sanitize()` before wide release (deferred; tracked here).**
> `sanitize()` was designed for **app-authored** lines and only recognizes the app's own secret shapes:
> a bare UUID and the JSON keys `"publicKey"` / `"shortId"`. Since the Logs screen feature, **raw
> Xray-core error output now flows through the same `append` → `sanitize` path** (via
> `XrayCoreLogTailer`). Core error lines can carry secrets in shapes `sanitize()` does not match — most
> notably a **Hysteria2 password** (an arbitrary string, not a UUID), and other non-UUID credentials —
> and at **Debug** level the core is far more verbose, widening what could surface. Such a value could
> reach the redacted in-memory buffer and therefore the shareable Copy/Share/Export output.
> **Action before wide release:** audit what Xray-core actually logs at each level (especially Debug)
> for the protocols this app builds (VLESS/REALITY, Hysteria2), then broaden `sanitize()` to cover the
> credential shapes found (e.g. Hysteria2 password, any `"password"`/auth fields, server-auth tokens).
> This wave intentionally makes **no** change to `sanitize()`.

## The live buffer-size setting

[`log/LogRepository.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/log/LogRepository.kt)
exposes a mutable cap:

```kotlin
@Volatile
var maxLines: Int = 5000
    private set

fun setMaxLines(n: Int) {
    val capped = n.coerceIn(100, 50_000)
    maxLines = capped
    _logs.update { it.takeLast(capped) }
}
```

`setMaxLines` is a **live**, immediate UI concern: it clamps the requested value into `[100, 50_000]`
and, in the same call, trims the current buffer down to the new cap (`takeLast(capped)`) — there is no
"applies on reconnect" delay for the buffer size, unlike the log level. `LogRepository.append` also
caps every future append at `maxLines`. The default (`5000`) matches
`LogPreferences.DEFAULT_BUFFER`.

`LogsActivity`'s buffer-size dialog picks from `LogPreferences.BUFFER_PRESETS` and, on selection, calls
**both** `LogPreferences.setBufferLines(context, preset)` (persist) **and**
`LogRepository.setMaxLines(preset)` (apply immediately) — so the on-screen list visibly trims the
instant the user picks a smaller preset.

## `LogPreferences` — persisted keys and apply semantics

[`log/LogPreferences.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/log/LogPreferences.kt)
persists both settings in the shared `xray_prefs` `SharedPreferences` store (the same store `PromoGate`
uses):

| Key | Type | Default | Preset/range |
|---|---|---|---|
| `xray_log_level` | `String` (enum name) | none stored → `XrayLogLevel.fromName(null)` → `WARNING` | `DEBUG` / `INFO` / `WARNING` / `ERROR` (picker excludes `NONE`) |
| `xray_log_buffer_lines` | `Int` | `DEFAULT_BUFFER = 5000` | `BUFFER_PRESETS = [1000, 2000, 5000, 10000]` |

The two settings have **different apply semantics**, and this is the single most important operational
fact about this screen:

- **Log level = persist-only; applies on the next connection.** `LogsActivity`'s level dialog calls
  `LogPreferences.setLogLevel(context, choice)` and nothing else — it does **not** touch the running
  session. The new value only takes effect the next time `startVpn` runs (fresh connect, or a
  kill-switch–independent stop/start), per the session-stable capture described above. The screen
  communicates this directly: the level row's subtitle is the string resource
  `logs_level_caption` = "Applies from the next connection".
- **Buffer size = live.** `LogsActivity`'s buffer dialog calls `LogPreferences.setBufferLines` **and**
  `LogRepository.setMaxLines` in the same click handler, so the change is visible immediately in the
  currently-displayed list, regardless of connection state.

## Components

| File | Role |
|---|---|
| [`config/LogSettings.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/config/LogSettings.kt) | `XrayLogLevel` enum (`wire` strings, `fromName` fallback to `WARNING`); `LogSettings(level, errorFilePath)`. |
| [`config/ConfigBuilder.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/config/ConfigBuilder.kt) | `buildRuntimeConfig(input, log = LogSettings(WARNING, null))` ends with private `forceLog`, which overwrites (not merges) the config's `log` object; `toPingTestConfig` forces `LogSettings(NONE, null)`. |
| [`log/LogPreferences.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/log/LogPreferences.kt) | `xray_prefs`-backed `getLogLevel`/`setLogLevel`, `getBufferLines`/`setBufferLines`, `BUFFER_PRESETS`, `DEFAULT_BUFFER`. |
| [`log/XrayCoreLogTailer.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/log/XrayCoreLogTailer.kt) | Polls the log file every 400 ms (≤64 KiB/poll fixed buffer), tracks byte offset, resets on shrink, strips Xray's own timestamp, feeds `LogRepository.append`. The **file read** catches only `IOException` (rethrows `CancellationException`) and retries. The **per-line handoff** to `append`/`sanitize` is separately guarded so a non-IOException bug there leaves a breadcrumb and the loop survives instead of silently killing the tailer coroutine. |
| [`log/BoundedLogLineAccumulator.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/log/BoundedLogLineAccumulator.kt) | Byte-oriented complete-line splitter; UTF-8 decode only after `\n`; discard-until-newline on pending overflow (>64 KiB). |
| [`log/LogRepository.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/log/LogRepository.kt) | `maxLines` (default 5000) + `setMaxLines` (coerce `[100, 50_000]`, immediate trim); `append` timestamps + redacts (UUID / `publicKey` / `shortId`) every line; `logs: StateFlow<List<String>>`. |
| [`vpn/XrayVpnService.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt) | `startVpn` creates+truncates `filesDir/logs/xray-core.log` once, captures `sessionLog` from `LogPreferences.getLogLevel`, and assigns a session epoch under `lock`; initial success/failure, tailer ownership, and kill-switch callbacks require that epoch to remain active. `stopVpn` invalidates it and serializes global teardown before a new session can start (kill-switch pause does not stop the tailer). |
| [`vpn/SessionLifecycleDecision.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleDecision.kt) | Pure identity/transition rules: a lifecycle callback is accepted only when the service is running and its epoch equals the active epoch (`acceptsSessionLifecycleCallback` / `ownsTunnelTransition`); `canReserveRevive` (PAUSED→REVIVING); `shouldDeferKillDuringRevive` (current session AND `REVIVING` — the defer-vs-drop rule for a kill landing mid-revive). |
| [`log/LogShareBudget.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/log/LogShareBudget.kt) | Pure `bound(lines, maxBytes)` → newest-tail `BoundedLog` (`text`/`includedLines`/`totalLines`/`truncated`) under a 256 KiB budget; keeps the inline Copy/Share payload clear of the Binder transaction limit. Export bypasses it (streams). |
| [`log/LogsActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/log/LogsActivity.kt) | Screen: auto-following `LazyColumn` of `LogRepository.logs`, "jump to latest" FAB, single overflow menu (Copy/Share/Export + a divider then the destructive **Clear** — no bare toolbar icon), fully-tappable radio rows (`selectable`), a large-log explainer dialog gating Copy/Share, level selector dialog (persist-only), buffer selector dialog (live). Copy/Share go through `LogShareBudget` + `runCatching`. |

## Error handling

- **Log file creation/truncation is best-effort.** `runCatching { logFile.writeText("") }` does not
  abort the connect on IO failure (e.g. storage pressure) — a missing/unwritable log file degrades to
  "no Xray-core log lines this session," not a failed connection. On failure the service appends a
  sanitized `LogRepository` breadcrumb (`"Core log file truncate failed; Xray-core logs may be
  unavailable this session"`) with no path or exception detail.
- **Tailer IO errors are transient and retried.** `XrayCoreLogTailer.start()`'s poll loop catches
  `IOException` around each read attempt (file being written/rotated concurrently) and simply retries
  on the next 400 ms tick; `CancellationException` is rethrown so `stop()` cancels cleanly. The file
  read still does **not** swallow `Error` / arbitrary `Throwable` — only the **per-line handoff** to
  `LogRepository.append`/`sanitize` is separately wrapped (rethrows `CancellationException`, logs a
  best-effort breadcrumb for any other `Throwable`) so a processing bug can't silently kill the tailer.
- **Unknown/corrupted persisted level falls back to `WARNING`**, not a crash or `NONE` — `fromName`'s
  `firstOrNull { it.name == name } ?: WARNING`.

## Known limitations

- **Level changes are visibly delayed by design**, not a bug — the whole point of session-stable
  capture is that a running connection's behavior can't be altered mid-flight from the Logs screen. The
  UI caption is the only affordance communicating this; there is no toast or "pending" indicator when a
  changed level differs from the currently-running session's level.
- **`NONE` is unreachable from the UI.** It exists only for `toPingTestConfig`; there is no way for a
  user to fully disable Xray-core's own log file for a real connection short of accepting `ERROR` as the
  quietest level.
- **Export uses a hard-coded filename** (`boykisser-log.txt`) via `ActivityResultContracts.CreateDocument`
  (SAF); the user picks the destination but not the name.

## Testing

- **JUnit4 (JVM)** — `app/src/test/.../log/XrayCoreLogTailerTest.kt`:
  - `stripXrayTimestamp` — strips micros / no-micros stamps; leaves untimestamped lines unchanged.
  - `BoundedLogLineAccumulator` — partial lines do not emit until `\n`; a UTF-8 multibyte character
    split across accepts round-trips correctly; oversized unterminated input follows the
    discard-until-newline policy and never emits a truncated secret prefix.
- **JUnit4 (JVM)** — `app/src/test/.../log/LogRepositoryBufferTest.kt`: `setMaxLines` coerces into
  `[100, 50_000]`, trims the current buffer immediately, and caps subsequent `append`s.
- **JUnit4 (JVM)** — `app/src/test/.../log/LogShareBudgetTest.kt`: `bound` returns an empty untruncated
  result for an empty buffer; keeps every line (in order) when under budget; keeps only the newest tail
  under the byte budget when over (oldest dropped, newest kept, payload ≤ `maxBytes`); and still returns
  a single line that alone exceeds the budget rather than nothing.
- **JUnit4 (JVM)** — `app/src/test/.../LogSettingsTest.kt`: `XrayLogLevel.fromName` parses known
  names and falls back to `WARNING` for unknown/`null`. (`LogPreferences` itself needs Android
  `SharedPreferences`, so there is no fabricated JVM persistence fake — wire-level enum parsing is
  what the JVM suite covers.)
- **JUnit4 (JVM)** — `app/src/test/.../vpn/SessionLifecycleDecisionTest.kt`: matching active epoch
  accepts a lifecycle callback; a callback from an earlier session is rejected even when a later
  session is running; stopped sessions reject matching callbacks. It also verifies that a matching
  `PAUSED` session accepts one revive reservation, while `REVIVING`, stale-epoch, and stopped sessions
  reject it, and that `shouldDeferKillDuringRevive` returns true only for the current session in
  `REVIVING` (false for CONNECTED, PAUSED, stale-epoch, and stopped). Android service/TUN/Xray
  scheduling itself remains integration behavior, so this suite tests the extracted identity/transition
  decision rather than fabricating a JVM `VpnService`.
- **JUnit4 (JVM)** — `ConfigBuilderTest` forces the `log` object (including hard-coded `access =
  "none"`) for VLESS, Hysteria2 URI, and raw-JSON overwrite paths; `toPingTestConfig` forces
  `NONE` with no `error` key.
- **On-device (manual)**: set level = Debug, connect, confirm Xray-core lines appear; change level while
  connected, confirm the running session is unaffected, then disconnect/reconnect and confirm the new
  level takes effect; trigger kill-switch pause/revive and confirm log lines keep flowing to the same
  buffer with no level change; run Copy/Share/Export and confirm UUID/publicKey/shortId values read
  `<redacted-uuid>` / `<redacted>` rather than raw values. **Also, at the 10 000-line preset after a long
  Debug session, run Copy and Share and confirm the explainer dialog appears and neither crashes/drops
  the VPN** (the `TransactionTooLargeException` path), while Export still writes the full log. See
  [`docs/qa/`](../qa/) for the broader QA scenario list.
