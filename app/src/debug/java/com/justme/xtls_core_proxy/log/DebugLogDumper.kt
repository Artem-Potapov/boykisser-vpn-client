package com.justme.xtls_core_proxy.log

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure helpers behind the debug-only Logs dump actions ("Dump to tmp file" / "Dump to
 * logcat"). They shape and split the already-redacted [LogRepository.logs] buffer for
 * retrieval; they never read `filesDir/logs/xray-core.log`, which stays raw and private.
 *
 * Dump text contract (shared by both actions): the marker line built by
 * [buildDumpMarker] is the first line, followed by every buffer line in order, with a
 * trailing newline — `renderDump(logs, marker) + "\n"`.
 *
 * Logcat contract: the dump text is escaped with [escapeForLogcat] so every emitted
 * entry is single-line, chunked by [chunkUtf8] (default 3000 bytes, safely below the
 * ~4068-byte per-entry logd payload limit), and each chunk is logged as
 * `log-dump NNNN/NNNN <payload>` on tag `XTLS.LogDump`. Reassembly: collect that tag's
 * entries matching `^log-dump (\d+)/(\d+) (.*)$`, sort by index, concatenate the
 * payloads, then [unescapeFromLogcat] — byte-exact back to the dump text. See
 * docs/features/logs-screen.md for the full retrieval contract.
 *
 * JVM-testable by design (debug unit-test source set): [DebugLogDumperTest] pins the
 * marker shape, byte-budget inclusivity, multibyte/surrogate-pair safety, lossless
 * reassembly, escaping round trips, and unique file naming.
 */
internal object DebugLogDumper {
    const val CHUNK_MAX_BYTES = 3000

    const val MARKER_NONE = "<none>"

    private val dumpFileNameFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.US)

    private const val MAX_NAME_COLLISIONS = 999

    /**
     * The marker is the whole dump's anchor: UTC timestamp, the display name of the
     * profile that is CONNECTED at dump time (`[MARKER_NONE]` when disconnected or
     * unresolvable), and the buffer line count. Profile names arrive from user data,
     * so control characters are flattened to keep the marker exactly one line.
     */
    fun buildDumpMarker(now: Instant, profileName: String?, lineCount: Int): String {
        val safeProfile = profileName
            ?.let(::flattenControlCharacters)
            ?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: MARKER_NONE
        return "[log-dump] utc=$now profile=$safeProfile lines=$lineCount"
    }

    /** Marker + full buffer, in buffer order, without a trailing newline. */
    fun renderDump(logs: List<String>, marker: String): String {
        if (logs.isEmpty()) return marker
        return buildString {
            append(marker)
            for (line in logs) {
                append('\n')
                append(line)
            }
        }
    }

    /**
     * Splits [text] into chunks of at most [maxBytes] UTF-8 bytes, never splitting a
     * Unicode code point (surrogate pairs included), preserving order; concatenating
     * the chunks reproduces the input byte-exactly. Empty input produces no chunks.
     */
    fun chunkUtf8(text: String, maxBytes: Int = CHUNK_MAX_BYTES): List<String> {
        // The largest single UTF-8 code point is 4 bytes, so every code point must fit.
        require(maxBytes >= 4) { "maxBytes must be at least 4 to hold any code point" }
        if (text.isEmpty()) return emptyList()
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentBytes = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val bytes = utf8ByteLength(codePoint)
            if (currentBytes + bytes > maxBytes && current.isNotEmpty()) {
                chunks.add(current.toString())
                current.setLength(0)
                currentBytes = 0
            }
            current.appendCodePoint(codePoint)
            currentBytes += bytes
            i += Character.charCount(codePoint)
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }

    /**
     * Makes text single-line for one-logcat-entry-per-chunk emission. Bijective with
     * [unescapeFromLogcat]: literal `\` sequences in the buffer must survive dumps, so
     * the backslash itself is escaped first.
     */
    fun escapeForLogcat(text: String): String =
        text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    /** Inverse of [escapeForLogcat]; a lone `\` not followed by `\`/`n`/`r` passes through. */
    fun unescapeFromLogcat(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    '\\' -> { out.append('\\'); i += 2 }
                    'n' -> { out.append('\n'); i += 2 }
                    'r' -> { out.append('\r'); i += 2 }
                    else -> { out.append(text[i]); i += 1 }
                }
            } else {
                out.append(text[i])
                i += 1
            }
        }
        return out.toString()
    }

    /** Unique, filesystem-safe, sortable dump file name: `log-dump-<UTC stamp>.txt`. */
    fun buildDumpFileName(now: Instant): String =
        "log-dump-" + now.atZone(ZoneOffset.UTC).format(dumpFileNameFormatter) + ".txt"

    /**
     * Millisecond-precision names can collide when two dumps land in the same
     * millisecond (or a same-named file was pulled but not cleaned); resolve to the
     * first unused `-N` suffix instead of overwriting an earlier dump.
     */
    fun resolveUniqueFile(targetDir: File, baseName: String): File {
        var candidate = File(targetDir, baseName)
        if (!candidate.exists()) return candidate
        val dot = baseName.lastIndexOf('.')
        val stem = if (dot > 0) baseName.substring(0, dot) else baseName
        val ext = if (dot > 0) baseName.substring(dot) else ""
        var n = 1
        while (n <= MAX_NAME_COLLISIONS) {
            candidate = File(targetDir, "$stem-$n$ext")
            if (!candidate.exists()) return candidate
            n++
        }
        throw IllegalStateException("no unique dump file name available in ${targetDir.path}")
    }

    /**
     * Creates [targetDir] if needed and writes [text] as UTF-8; returns the file written.
     * An unusable target (creation failed, or an existing non-directory) is reported explicitly
     * rather than surfacing as an incidental IO error deep in the write. A benign
     * already-exists race between the `mkdirs` check and a concurrent creator is accepted.
     */
    fun writeDumpFile(targetDir: File, text: String, now: Instant): File {
        if (!targetDir.exists()) {
            val created = targetDir.mkdirs()
            if (!created && !targetDir.isDirectory) {
                throw IllegalStateException("Cannot use dump directory ${targetDir.path}: creation failed")
            }
        }
        if (!targetDir.isDirectory) {
            throw IllegalStateException("Cannot use dump directory ${targetDir.path}: not a directory")
        }
        val file = resolveUniqueFile(targetDir, buildDumpFileName(now))
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    /** Control characters (including CR/LF/TAB) become spaces so the marker stays one line. */
    fun flattenControlCharacters(raw: String): String {
        val out = StringBuilder(raw.length)
        for (c in raw) {
            out.append(if (c.isISOControl()) ' ' else c)
        }
        return out.toString()
    }

    private fun utf8ByteLength(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }
}
