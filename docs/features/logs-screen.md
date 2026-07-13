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
runCatching { logFile.writeText("") }   // truncate once per session
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
it only reads newly-appended bytes (`RandomAccessFile.seek(offset)`), buffers a partial trailing line in
a `StringBuilder` `carry`, and emits only complete (`\n`-terminated) lines to `LogRepository.append(...)`.
If the file shrinks between polls (rotation or the core reopening it truncated) the offset resets to 0
and the carry buffer is cleared — this is the only case that re-reads from the start.

Each emitted line is first passed through `stripXrayTimestamp`, which strips Xray's own leading
`2006/01/02 15:04:05(.000000)`-style stamp via a regex
(`^\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}(\.\d+)?\s+`) before handing the line to `LogRepository.append`,
which stamps its **own** `HH:mm:ss.SSS` timestamp — this avoids every Xray-core line showing two
timestamps. Non-matching lines pass through unchanged.

`XrayVpnService` starts the tailer in `startVpn`'s success path, guarded so a revive doesn't spawn a
second one:

```kotlin
sessionLogFile?.let { f ->
    if (logTailer == null) { logTailer = XrayCoreLogTailer(f).also { it.start() } }
}
```

and stops it only in `stopVpn()` (`logTailer?.stop(); logTailer = null`) — **not** in the kill-switch
pause path (`killTunnel`). This is deliberate: the tailer (and the underlying log file) survive
kill-switch pause/revive, so log lines continue flowing to the same `LogRepository` buffer across a
pause — only a full VPN stop tears the tailer down. `XrayCoreLogTailer`'s own class doc states this
explicitly: "Deliberately survives kill-switch pause/revive."

## Session-stable log level

The log level a connection runs with is captured **once**, in `startVpn`, from
`LogPreferences.getLogLevel(this)` at the moment the session starts — not re-read on every
`bringUpTunnel` call:

```kotlin
@Volatile private var sessionLog: LogSettings = LogSettings(XrayLogLevel.WARNING, null)
...
sessionLog = LogSettings(LogPreferences.getLogLevel(this@XrayVpnService), logFile.absolutePath)
```

`bringUpTunnel(profile)` always builds the runtime config with this captured `sessionLog`:

```kotlin
val configJson = ConfigBuilder.buildRuntimeConfig(profile.config, sessionLog)
```

Because `reviveTunnel()` calls the same `bringUpTunnel(profile)` (not `startVpn`), a kill-switch
pause/revive cycle reuses `sessionLog` unchanged — **the level cannot change mid-session**, even if the
user opens the Logs screen and picks a different level while connected. A level change made in the UI
only takes effect on the **next** `startVpn` call (i.e. the next full connect), because that's the only
place `sessionLog` is re-derived from the persisted preference. This is the load-bearing invariant
behind the Logs screen's "Applies from the next connection" caption (see below).

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

`LogsActivity`'s Copy/Share/Export actions (`copyLogs`, `shareLogs`, the `CreateDocument` export
launcher) all operate on `logs.joinToString("\n")` where `logs` is the Compose `collectAsState()` of
`LogRepository.logs` — the redacted `StateFlow`. There is no code path from the Logs screen (or
anywhere else in the app) that reads `filesDir/logs/xray-core.log` directly. The tailer is the only
reader of that file, and it always routes through `LogRepository.append` (hence through `sanitize`)
before a line becomes visible anywhere.

**Do not add a code path that reads or shares `filesDir/logs/xray-core.log` directly** — doing so would
bypass the redaction that Copy/Share/Export currently guarantee.

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
| [`log/XrayCoreLogTailer.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/log/XrayCoreLogTailer.kt) | Polls the log file every 400 ms, tracks byte offset, resets on shrink, strips Xray's own timestamp, feeds `LogRepository.append`. |
| [`log/LogRepository.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/log/LogRepository.kt) | `maxLines` (default 5000) + `setMaxLines` (coerce `[100, 50_000]`, immediate trim); `append` timestamps + redacts (UUID / `publicKey` / `shortId`) every line; `logs: StateFlow<List<String>>`. |
| [`vpn/XrayVpnService.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt) | `startVpn` creates+truncates `filesDir/logs/xray-core.log` once, captures `sessionLog` from `LogPreferences.getLogLevel`, starts `logTailer` on first successful `bringUpTunnel`; `stopVpn` stops+clears `logTailer` (kill-switch pause does not). |
| [`log/LogsActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/log/LogsActivity.kt) | Screen: auto-following `LazyColumn` of `LogRepository.logs`, "jump to latest" FAB, Clear toolbar action, Copy/Share/Export overflow menu, level selector dialog (persist-only), buffer selector dialog (live). |

## Error handling

- **Log file creation/truncation is best-effort.** `runCatching { logFile.writeText("") }` swallows any
  IO failure (e.g. storage pressure) rather than aborting the connect — a missing/unwritable log file
  degrades to "no Xray-core log lines this session," not a failed connection.
- **Tailer IO errors are transient and retried.** `XrayCoreLogTailer.start()`'s poll loop catches
  `Throwable` around each read attempt (file being written/rotated concurrently) and simply retries on
  the next 400 ms tick; it never crashes the coroutine or the service.
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

- No dedicated JUnit4 suite exists yet for `XrayCoreLogTailer.stripXrayTimestamp` or
  `LogRepository.setMaxLines`'s coercion — verify these paths on-device (Debug level while connected
  shows chatty Xray-core output with single, correctly-formatted timestamps; buffer preset changes trim
  the visible list immediately).
- **On-device (manual)**: set level = Debug, connect, confirm Xray-core lines appear; change level while
  connected, confirm the running session is unaffected, then disconnect/reconnect and confirm the new
  level takes effect; trigger kill-switch pause/revive and confirm log lines keep flowing to the same
  buffer with no level change; run Copy/Share/Export and confirm UUID/publicKey/shortId values read
  `<redacted-uuid>` / `<redacted>` rather than raw values. See
  [`docs/qa/`](../qa/) for the broader QA scenario list.
