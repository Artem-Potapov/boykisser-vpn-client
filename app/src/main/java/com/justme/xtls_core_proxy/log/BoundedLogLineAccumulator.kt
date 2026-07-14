package com.justme.xtls_core_proxy.log

import java.nio.charset.StandardCharsets

/**
 * Byte-oriented line splitter for [XrayCoreLogTailer].
 *
 * Accumulates raw UTF-8 bytes and emits only complete `\n`-terminated lines (CRLF trimmed),
 * so a multibyte character split across poll/chunk boundaries is never decoded as U+FFFD.
 *
 * **Overflow policy:** if an unterminated pending line would exceed [maxPendingBytes], the
 * pending bytes are discarded and further input is skipped until the next `\n` (resync).
 * The oversized fragment is never emitted — including as a truncated prefix — so secrets that
 * might appear in a malformed/oversized line are not retained or leaked through the log path.
 */
internal class BoundedLogLineAccumulator(
    private val maxPendingBytes: Int = XrayCoreLogTailer.MAX_PENDING_LINE_BYTES,
    private val onLine: (String) -> Unit,
) {
    private val pending = ByteArray(maxPendingBytes)
    private var pendingLen = 0
    private var discardUntilNewline = false

    fun accept(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size) {
        var i = offset
        val end = offset + length
        while (i < end) {
            if (discardUntilNewline) {
                while (i < end && chunk[i] != NEWLINE) i++
                if (i < end && chunk[i] == NEWLINE) {
                    i++ // consume the newline that ends the discarded region
                    discardUntilNewline = false
                    pendingLen = 0
                }
                continue
            }

            val nlRel = indexOfNewline(chunk, i, end)
            if (nlRel < 0) {
                val remaining = end - i
                if (pendingLen + remaining > maxPendingBytes) {
                    pendingLen = 0
                    discardUntilNewline = true
                    // Re-process from here under discard mode (may find a newline later in chunk).
                    continue
                }
                System.arraycopy(chunk, i, pending, pendingLen, remaining)
                pendingLen += remaining
                return
            }

            val lineByteCount = nlRel - i
            if (pendingLen + lineByteCount > maxPendingBytes) {
                pendingLen = 0
                discardUntilNewline = true
                // Fall through: discard mode will consume through this newline on the next loop.
                continue
            }

            val lineBytes = if (pendingLen == 0) {
                chunk.copyOfRange(i, nlRel)
            } else {
                val combined = ByteArray(pendingLen + lineByteCount)
                System.arraycopy(pending, 0, combined, 0, pendingLen)
                System.arraycopy(chunk, i, combined, pendingLen, lineByteCount)
                pendingLen = 0
                combined
            }
            i = nlRel + 1
            emitDecodedLine(lineBytes)
        }
    }

    fun accept(chunk: ByteArray) = accept(chunk, 0, chunk.size)

    /** Clears pending bytes and discard-until-newline state (e.g. after file shrink/rotation). */
    fun reset() {
        pendingLen = 0
        discardUntilNewline = false
    }

    private fun emitDecodedLine(lineBytes: ByteArray) {
        var len = lineBytes.size
        if (len > 0 && lineBytes[len - 1] == CR) len--
        if (len == 0) return
        val line = String(lineBytes, 0, len, StandardCharsets.UTF_8)
        if (line.isNotBlank()) onLine(line)
    }

    private companion object {
        const val NEWLINE: Byte = 0x0A
        const val CR: Byte = 0x0D

        fun indexOfNewline(buf: ByteArray, from: Int, to: Int): Int {
            for (i in from until to) {
                if (buf[i] == NEWLINE) return i
            }
            return -1
        }
    }
}
