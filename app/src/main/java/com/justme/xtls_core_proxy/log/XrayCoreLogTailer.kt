package com.justme.xtls_core_proxy.log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile

/**
 * Tails Xray-core's error log file into [LogRepository] (which timestamps + redacts each
 * line). Owned by XrayVpnService: start() once after the core starts, stop() on full teardown.
 * Deliberately survives kill-switch pause/revive — the file persists and the core reopens the
 * same path (append), so the byte offset keeps advancing. If the file shrinks (rotation/reopen
 * truncation), the offset resets to 0.
 */
class XrayCoreLogTailer(private val file: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            var offset = 0L
            val carry = StringBuilder()
            while (isActive) {
                try {
                    val len = if (file.exists()) file.length() else 0L
                    if (len < offset) { offset = 0L; carry.setLength(0) }  // file shrank
                    if (len > offset) {
                        RandomAccessFile(file, "r").use { raf ->
                            raf.seek(offset)
                            val buf = ByteArray((len - offset).toInt())
                            val read = raf.read(buf)
                            if (read > 0) {
                                offset += read
                                carry.append(String(buf, 0, read, Charsets.UTF_8))
                                emitCompleteLines(carry)
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // Transient IO (file being written/rotated); retry next tick.
                }
                delay(POLL_MS)
            }
        }
    }

    private fun emitCompleteLines(carry: StringBuilder) {
        var nl = carry.indexOf("\n")
        while (nl >= 0) {
            val line = carry.substring(0, nl).trimEnd('\r')
            if (line.isNotBlank()) LogRepository.append(stripXrayTimestamp(line))
            carry.delete(0, nl + 1)
            nl = carry.indexOf("\n")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val POLL_MS = 400L
        private val TS = Regex("""^\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}(\.\d+)?\s+""")

        /** Removes Xray's own leading `2006/01/02 15:04:05(.000000)` stamp so the app's
         *  own [LogRepository] timestamp isn't doubled. Non-matching lines pass through. */
        fun stripXrayTimestamp(line: String): String = TS.replaceFirst(line, "")
    }
}
