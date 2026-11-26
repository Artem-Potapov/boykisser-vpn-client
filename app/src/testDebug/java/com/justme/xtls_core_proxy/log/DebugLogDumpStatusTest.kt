package com.justme.xtls_core_proxy.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit spec for the last-action-wins debug dump status. Every dump request moves exactly one
 * [DebugLogDumpStatusHolder] state, the same production holder collected by [DebugLogDumpStatus]
 * and driven by the dump orchestration. In production `begin`, `complete` and toast presentation
 * are confined to `Dispatchers.Main`; these tests pin the holder's atomic acceptance contract.
 */
class DebugLogDumpStatusTest {

    private val holder = DebugLogDumpStatusHolder()
    private val instantA = 1L
    private val instantB = 2L

    // ---- pure reducer ----

    @Test fun startStatus_replacesPriorOutcomeAndMarksRequestInFlight() {
        val prior = DebugLogDumpStatusReducer.fileWritten(
            requestId = 1L, path = "/old/dump.txt", instant = 1L)
        val started = DebugLogDumpStatusReducer.started(requestId = 2L, instant = instantB)

        assertEquals(2L, started.requestId)
        assertEquals(DebugDumpOutcomeKind.IN_FLIGHT, started.kind)
        assertNull("old path must not survive a newer request", started.filePath)
        assertNull(started.logcatChunks)
        assertNull(started.failure)
        assertNotEquals(prior.requestId, started.requestId)
    }

    @Test fun fileSuccess_afterFailure_showsOnlyLatestFilePath() {
        val failed = DebugLogDumpStatusReducer.failed(
            requestId = 3L, message = "Dump failed: old", instant = 1L)
        val succeeded = DebugLogDumpStatusReducer.fileWritten(
            requestId = 4L, path = "/latest/dump.txt", instant = instantB)

        assertEquals(4L, succeeded.requestId)
        assertEquals(DebugDumpOutcomeKind.FILE, succeeded.kind)
        assertEquals("/latest/dump.txt", succeeded.filePath)
        assertNull("prior failure must be replaced", succeeded.failure)
        assertNull(succeeded.logcatChunks)
    }

    @Test fun failure_afterFileSuccess_showsOnlyLatestFailure() {
        val succeeded = DebugLogDumpStatusReducer.fileWritten(
            requestId = 5L, path = "/old/dump.txt", instant = 1L)
        val failed = DebugLogDumpStatusReducer.failed(
            requestId = 6L, message = "Dump failed: disk full", instant = instantB)

        assertEquals(6L, failed.requestId)
        assertEquals(DebugDumpOutcomeKind.FAILURE, failed.kind)
        assertEquals("Dump failed: disk full", failed.failure)
        assertNull("old path must not accompany the new failure", failed.filePath)
    }

    @Test fun logcatSuccess_afterFileSuccess_showsOnlyLatestLogcatOutcome() {
        val file = DebugLogDumpStatusReducer.fileWritten(
            requestId = 7L, path = "/old/dump.txt", instant = 1L)
        val logcat = DebugLogDumpStatusReducer.logcatWritten(
            requestId = 8L, chunks = 12, instant = instantB)

        assertEquals(8L, logcat.requestId)
        assertEquals(DebugDumpOutcomeKind.LOGCAT, logcat.kind)
        assertEquals(12, logcat.logcatChunks)
        assertNull("old file path must not accompany a logcat result", logcat.filePath)
        assertNull(logcat.failure)
        assertNotEquals(file.kind, logcat.kind)
    }

    @Test fun fileSuccess_afterLogcatSuccess_showsOnlyFilePath() {
        val logcat = DebugLogDumpStatusReducer.logcatWritten(
            requestId = 9L, chunks = 3, instant = 1L)
        val file = DebugLogDumpStatusReducer.fileWritten(
            requestId = 10L, path = "/new/dump.txt", instant = instantB)

        assertEquals(10L, file.requestId)
        assertEquals(DebugDumpOutcomeKind.FILE, file.kind)
        assertEquals("/new/dump.txt", file.filePath)
        assertNull("old chunk count must not accompany a file result", file.logcatChunks)
    }

    @Test fun staleCompletion_doesNotOverwriteNewerInFlightOrNewerOutcome() {
        val newerStart = DebugLogDumpStatusReducer.started(requestId = 20L, instant = instantB)

        val staleFile = DebugLogDumpStatusReducer.fileWritten(
            requestId = 19L, path = "/stale/dump.txt", instant = 1L)
        val ignoredDuringStart = DebugLogDumpStatusReducer.applyIfCurrent(staleFile, newerStart)
        assertEquals("stale completion must not replace a newer in-flight request",
            newerStart, ignoredDuringStart)

        val newerOutcome = DebugLogDumpStatusReducer.logcatWritten(
            requestId = 21L, chunks = 5, instant = 3L)
        val staleFailure = DebugLogDumpStatusReducer.failed(
            requestId = 18L, message = "stale failure", instant = 1L)
        assertEquals("stale failure must not replace a newer completed outcome",
            newerOutcome, DebugLogDumpStatusReducer.applyIfCurrent(staleFailure, newerOutcome))
    }

    @Test fun sameRequestId_completionReplacesItsOwnInFlightMarker() {
        val started = DebugLogDumpStatusReducer.started(requestId = 30L, instant = instantA)
        val completed = DebugLogDumpStatusReducer.fileWritten(
            requestId = 30L, path = "/current/dump.txt", instant = instantB)

        assertEquals(completed, DebugLogDumpStatusReducer.applyIfCurrent(completed, started))
        assertEquals(DebugDumpOutcomeKind.FILE, completed.kind)
    }

    // ---- holder end-to-end: status transitions and atomic acceptance ----

    @Test fun holder_begin_clearsPriorOutcomeAndMarksNewRequestInFlight() {
        val first = holder.begin()
        assertTrue(holder.complete(DebugLogDumpStatusReducer.fileWritten(
            first, "/old/dump.txt", instantA)))
        assertEquals("/old/dump.txt", holder.status.value?.filePath)

        val second = holder.begin()
        assertEquals(second, holder.status.value?.requestId)
        assertEquals(DebugDumpOutcomeKind.IN_FLIGHT, holder.status.value?.kind)
        assertNull("newer request clears prior file path", holder.status.value?.filePath)
        assertNull(holder.status.value?.logcatChunks)
        assertNull(holder.status.value?.failure)
    }

    @Test fun holder_successThenFailure_showsOnlyLatestFailure() {
        val fileRequest = holder.begin()
        assertTrue(holder.complete(DebugLogDumpStatusReducer.fileWritten(
            fileRequest, "/old/dump.txt", instantA)))
        val failureRequest = holder.begin()
        assertTrue(holder.complete(DebugLogDumpStatusReducer.failed(
            failureRequest, "Dump failed: disk full", instantB)))

        assertEquals(failureRequest, holder.status.value?.requestId)
        assertEquals(DebugDumpOutcomeKind.FAILURE, holder.status.value?.kind)
        assertEquals("Dump failed: disk full", holder.status.value?.failure)
        assertNull("old file path must not accompany the new failure", holder.status.value?.filePath)
    }

    @Test fun holder_failureThenSuccess_showsOnlyLatestSuccess() {
        val failedRequest = holder.begin()
        assertTrue(holder.complete(DebugLogDumpStatusReducer.failed(
            failedRequest, "Dump failed: old", instantA)))
        val fileRequest = holder.begin()
        assertTrue(holder.complete(DebugLogDumpStatusReducer.fileWritten(
            fileRequest, "/new/dump.txt", instantB)))

        assertEquals(DebugDumpOutcomeKind.FILE, holder.status.value?.kind)
        assertEquals("/new/dump.txt", holder.status.value?.filePath)
        assertNull("old failure must be cleared by the new success", holder.status.value?.failure)
    }

    @Test fun holder_fileThenLogcat_thenFile_showsOnlyLatestKindAndField() {
        val fileRequest = holder.begin()
        assertTrue(holder.complete(DebugLogDumpStatusReducer.fileWritten(
            fileRequest, "/first/dump.txt", instantA)))
        val logcatRequest = holder.begin()
        assertTrue(holder.complete(DebugLogDumpStatusReducer.logcatWritten(
            logcatRequest, 7, instantB)))
        assertEquals(DebugDumpOutcomeKind.LOGCAT, holder.status.value?.kind)
        assertEquals(7, holder.status.value?.logcatChunks)
        assertNull(holder.status.value?.filePath)

        val nextFile = holder.begin()
        assertTrue(holder.complete(DebugLogDumpStatusReducer.fileWritten(
            nextFile, "/second/dump.txt", 3L)))
        assertEquals(DebugDumpOutcomeKind.FILE, holder.status.value?.kind)
        assertEquals("/second/dump.txt", holder.status.value?.filePath)
        assertNull("prior chunk count must not accompany a file result", holder.status.value?.logcatChunks)
    }

    @Test fun holder_staleSuccessAndFailure_duringAndAfterNewerRequest_areRejected() {
        val stale = holder.begin()
        val newer = holder.begin()
        val staleFile = DebugLogDumpStatusReducer.fileWritten(stale, "/stale/dump.txt", instantA)
        val staleFailure = DebugLogDumpStatusReducer.failed(stale, "stale failure", instantA)

        assertFalse("stale success must not be accepted", holder.complete(staleFile))
        assertEquals("stale success must not publish", newer, holder.status.value?.requestId)
        assertNull(holder.status.value?.filePath)
        assertFalse("stale failure must not be accepted", holder.complete(staleFailure))
        assertEquals("stale failure must not publish",
            DebugDumpOutcomeKind.IN_FLIGHT, holder.status.value?.kind)
        assertNull(holder.status.value?.failure)

        assertTrue(holder.complete(DebugLogDumpStatusReducer.logcatWritten(newer, 3, instantB)))
        assertFalse("stale success after newer outcome must not be accepted",
            holder.complete(staleFile))
        assertFalse("stale failure after newer outcome must not be accepted",
            holder.complete(staleFailure))
        assertEquals(3, holder.status.value?.logcatChunks)
        assertNull("stale success after newer outcome must be ignored", holder.status.value?.filePath)
        assertNull("stale failure after newer outcome must be ignored", holder.status.value?.failure)
    }

    @Test fun holder_currentCompletion_isAcceptedOnce_andDuplicateCompletionRejected() {
        val request = holder.begin()
        val outcome = DebugLogDumpStatusReducer.logcatWritten(request, 9, instantB)
        assertTrue(holder.complete(outcome))
        assertEquals(request, holder.status.value?.requestId)
        assertEquals(9, holder.status.value?.logcatChunks)
        assertFalse("a second completion must not be accepted", holder.complete(outcome))
        assertEquals("duplicate completion must not change status",
            DebugDumpOutcomeKind.LOGCAT, holder.status.value?.kind)
    }

    @Test fun holder_newerBeginBeforeQueuedCompletion_rejectsEarlierCompletion() {
        val earlier = holder.begin()
        val newer = holder.begin()
        val queued = DebugLogDumpStatusReducer.fileWritten(earlier, "/earlier/dump.txt", instantA)

        assertFalse("earlier completion queued behind a newer begin must be rejected",
            holder.complete(queued))
        assertEquals(newer, holder.status.value?.requestId)
        assertEquals(DebugDumpOutcomeKind.IN_FLIGHT, holder.status.value?.kind)
        assertTrue("newer request still accepts its own completion",
            holder.complete(DebugLogDumpStatusReducer.fileWritten(newer, "/new/dump.txt", instantB)))
        assertEquals("/new/dump.txt", holder.status.value?.filePath)
    }

    @Test fun holder_completionWithoutInFlightRequest_isRejected() {
        val outcome = DebugLogDumpStatusReducer.fileWritten(1L, "/orphan/dump.txt", instantA)
        assertFalse(holder.complete(outcome))
        assertNull(holder.status.value)
    }

    @Test fun holder_forcedInterleaving_olderCompletionCannotPublishAfterNewerBegin() {
        val concurrentHolder = DebugLogDumpStatusHolder()
        val older = concurrentHolder.begin()
        val olderOutcome = DebugLogDumpStatusReducer.fileWritten(older, "/older/dump.txt", instantA)
        val started = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        var olderAccepted: Boolean? = null
        val thread = Thread {
            // Reads the current in-flight status, then stalls exactly where the prior two-step
            // implementation released the CAS before setting its separate toast token.
            check(concurrentHolder.status.value?.requestId == older)
            started.countDown()
            resume.await()
            olderAccepted = concurrentHolder.complete(olderOutcome)
        }
        thread.start()
        started.await()
        val newer = concurrentHolder.begin()
        resume.countDown()
        thread.join(5_000L)

        assertFalse("older completion must be rejected after a newer begin", olderAccepted == true)
        assertEquals(newer, concurrentHolder.status.value?.requestId)
        assertEquals(DebugDumpOutcomeKind.IN_FLIGHT, concurrentHolder.status.value?.kind)
        assertNull(concurrentHolder.status.value?.filePath)
    }
}
