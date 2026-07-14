package com.justme.xtls_core_proxy.log

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Tails Xray-core's error log file into [LogRepository] (which timestamps + redacts each
 * line). Owned by XrayVpnService: start() once after the core starts, stop() on full teardown.
 * Deliberately survives kill-switch pause/revive — the file persists and the core reopens the
 * same path (append), so the byte offset keeps advancing. If the file shrinks (rotation/reopen
 * truncation), the offset resets to 0.
 *
 * Each poll reads at most [MAX_READ_PER_POLL] bytes into a fixed buffer. Incomplete lines are
 * held as raw bytes by [BoundedLogLineAccumulator] (capped at [MAX_PENDING_LINE_BYTES]); see
 * that class for the overflow/resync policy. Decoding happens only for complete lines, so a
 * UTF-8 multibyte character split across polls is never forced through a partial decode.
 */
class XrayCoreLogTailer(private val file: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            var offset = 0L
            val readBuf = ByteArray(MAX_READ_PER_POLL)
            val accumulator = BoundedLogLineAccumulator { rawLine ->
                LogRepository.append(stripXrayTimestamp(rawLine))
            }
            while (isActive) {
                try {
                    val len = if (file.exists()) file.length() else 0L
                    if (len < offset) {
                        // File shrank (rotation / reopen truncation): restart from the beginning.
                        offset = 0L
                        // Drop any partial line that belonged to the previous file generation.
                        accumulator.reset()
                    }
                    if (len > offset) {
                        RandomAccessFile(file, "r").use { raf ->
                            raf.seek(offset)
                            val toRead = minOf((len - offset), MAX_READ_PER_POLL.toLong()).toInt()
                            val read = raf.read(readBuf, 0, toRead)
                            if (read > 0) {
                                offset += read
                                accumulator.accept(readBuf, 0, read)
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: IOException) {
                    // Transient IO (file being written/rotated); retry next tick.
                }
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val POLL_MS = 400L

        /** Fixed per-poll read cap — prevents unbounded ByteArray allocation from a large append. */
        const val MAX_READ_PER_POLL = 64 * 1024

        /**
         * Max bytes retained for an unterminated line. Oversized pending input is discarded
         * until the next `\n` (see [BoundedLogLineAccumulator]); nothing is emitted from the
         * discarded region.
         */
        const val MAX_PENDING_LINE_BYTES = 64 * 1024

        private val TS = Regex("""^\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}(\.\d+)?\s+""")

        /** Removes Xray's own leading `2006/01/02 15:04:05(.000000)` stamp so the app's
         *  own [LogRepository] timestamp isn't doubled. Non-matching lines pass through. */
        fun stripXrayTimestamp(line: String): String = TS.replaceFirst(line, "")
    }
}
