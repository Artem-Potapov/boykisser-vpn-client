package com.justme.xtls_core_proxy.log

/**
 * Result of bounding a log buffer for an *inline* (Binder-marshalled) Copy/Share payload.
 *
 * @property text the joined payload (newest-line-inclusive tail), never larger than the budget
 *   unless the single newest line alone exceeds it.
 * @property includedLines how many lines the payload carries.
 * @property totalLines how many lines the source buffer held.
 */
internal data class BoundedLog(
    val text: String,
    val includedLines: Int,
    val totalLines: Int,
) {
    /** True when lines were dropped to fit the budget (so the UI can warn + point at Export). */
    val truncated: Boolean get() = includedLines < totalLines
}

/**
 * Bounds a log buffer to a byte budget for the *inline* Copy/Share paths.
 *
 * `ClipboardManager.setPrimaryClip` and `startActivity(ACTION_SEND, EXTRA_TEXT=...)` both
 * marshal their entire payload through a single Binder transaction, whose per-process buffer
 * is ~1 MB shared across all in-flight transactions. Handing the full 10 000-line buffer to
 * either throws `TransactionTooLargeException`; because `XrayVpnService` runs in this same
 * process, that uncaught throw kills the tunnel and wipes the in-memory `LogRepository`.
 *
 * This keeps the **newest** lines (the tail) that fit under [MAX_SHARE_BYTES] — the recent
 * output is what a user sharing logs for debugging actually needs, and the *complete* log
 * stays reachable via Export, which streams to a `content://` output and has no Binder limit.
 */
internal object LogShareBudget {
    /**
     * UTF-8 byte budget for an inline payload. Conservatively a quarter of the ~1 MB Binder
     * ceiling so the transaction stays safe even alongside other in-flight IPC and parcel
     * overhead.
     */
    const val MAX_SHARE_BYTES: Int = 100 * 1024

    /**
     * @return the newest suffix of [lines] whose joined UTF-8 size is within [maxBytes].
     *   Always includes at least the last line for a non-empty buffer (best-effort even if
     *   that lone line exceeds the budget — the caller's `runCatching` is the backstop).
     */
    fun bound(lines: List<String>, maxBytes: Int = MAX_SHARE_BYTES): BoundedLog {
        val total = lines.size
        if (total == 0) return BoundedLog(text = "", includedLines = 0, totalLines = 0)

        var bytes = 0
        var startIndex = total // suffix kept is [startIndex, total); walks down toward 0
        for (i in total - 1 downTo 0) {
            val lineBytes = lines[i].toByteArray(Charsets.UTF_8).size
            // The newest line is included unconditionally; each older line adds a '\n' joiner.
            val added = if (startIndex == total) lineBytes else lineBytes + 1
            if (startIndex != total && bytes + added > maxBytes) break
            bytes += added
            startIndex = i
        }

        val included = lines.subList(startIndex, total)
        return BoundedLog(
            text = included.joinToString("\n"),
            includedLines = included.size,
            totalLines = total,
        )
    }
}
