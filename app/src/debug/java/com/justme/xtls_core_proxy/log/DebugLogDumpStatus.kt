package com.justme.xtls_core_proxy.log

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Last-action-wins state for the debug-only dump actions, plus the reducer and the JVM-testable
 * holder used by the composable and the dump orchestration. One value carries the request identity
 * plus exactly one outcome: a newer request clears every earlier path/chunk/failure field, and a
 * stale asynchronous completion cannot overwrite it.
 *
 * Production confines `begin`, [complete], and toast presentation to `Dispatchers.Main` — the same
 * dispatcher as the menu click — so begin/complete/present ordering is a single non-suspending Main
 * segment. [complete] is additionally atomic, accepting only the current request's in-flight marker,
 * so callers from any thread cannot publish a stale or duplicate outcome. The redacted buffer is
 * never exposed here — only the path, count, and error summary of the latest action.
 *
 * The holder is an instance (not a bare `object`) so tests exercise the exact class the UI uses;
 * production owns one process-global instance and tests build isolated instances.
 */
internal class DebugLogDumpStatusHolder {
    private val nextRequestId = AtomicLong(1L)
    private val mutableStatus = MutableStateFlow<DebugLogDumpStatus?>(null)

    /** Read-only view collected by the composable and asserted by JVM tests. */
    val status: StateFlow<DebugLogDumpStatus?> = mutableStatus.asStateFlow()

    /** Claims a monotonically increasing request id and marks it in flight. Main-confined. */
    fun begin(): Long {
        val id = nextRequestId.getAndIncrement()
        mutableStatus.value = DebugLogDumpStatusReducer.started(id, System.currentTimeMillis())
        return id
    }

    /**
     * Atomically publishes [outcome] only when it completes the still-current IN_FLIGHT request of
     * the same id. Returns true exactly once for that request, giving the caller presentation
     * eligibility without a second piece of state. A stale or duplicate completion is rejected
     * without touching status; an orphan completion (no in-flight request) is rejected too.
     */
    fun complete(outcome: DebugLogDumpStatus): Boolean {
        while (true) {
            val current = mutableStatus.value
            if (current?.kind != DebugDumpOutcomeKind.IN_FLIGHT ||
                current.requestId != outcome.requestId
            ) {
                return false
            }
            if (mutableStatus.compareAndSet(current, outcome)) return true
        }
    }
}

internal enum class DebugDumpOutcomeKind { IN_FLIGHT, FILE, LOGCAT, FAILURE }

internal data class DebugLogDumpStatus(
    val requestId: Long,
    val kind: DebugDumpOutcomeKind,
    val filePath: String? = null,
    val logcatChunks: Int? = null,
    val failure: String? = null,
    val instant: Long = 0L,
)

internal object DebugLogDumpStatusReducer {
    fun started(requestId: Long, instant: Long): DebugLogDumpStatus = DebugLogDumpStatus(
        requestId = requestId, kind = DebugDumpOutcomeKind.IN_FLIGHT, instant = instant)

    fun fileWritten(requestId: Long, path: String, instant: Long): DebugLogDumpStatus =
        DebugLogDumpStatus(
            requestId = requestId,
            kind = DebugDumpOutcomeKind.FILE,
            filePath = path,
            instant = instant,
        )

    fun logcatWritten(requestId: Long, chunks: Int, instant: Long): DebugLogDumpStatus =
        DebugLogDumpStatus(
            requestId = requestId,
            kind = DebugDumpOutcomeKind.LOGCAT,
            logcatChunks = chunks,
            instant = instant,
        )

    fun failed(requestId: Long, message: String, instant: Long): DebugLogDumpStatus =
        DebugLogDumpStatus(
            requestId = requestId,
            kind = DebugDumpOutcomeKind.FAILURE,
            failure = message,
            instant = instant,
        )

    /** Accept [candidate] only if it belongs to the request the current status still represents. */
    fun applyIfCurrent(
        candidate: DebugLogDumpStatus,
        current: DebugLogDumpStatus,
    ): DebugLogDumpStatus = if (candidate.requestId == current.requestId) candidate else current
}
