package com.justme.xtls_core_proxy.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.max

/**
 * P3-R5 + P3-R6: a single stable admission owner (created once, never swapped) that keeps
 * cross-run de-dup and a fixed native-admission ceiling across per-run concurrency changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PingCoordinatorTest {

    @Test
    fun fromResult_mapsSuccessAndFailure() {
        assertEquals(PingState.Success(42L), PingState.fromResult(Result.success(42L)))
        assertEquals(PingState.Unavailable, PingState.fromResult(Result.failure(RuntimeException("x"))))
    }

    // ---- R5: cross-run de-dup + per-run concurrency, no tester reconstruction ----

    @Test
    fun runGroup_dedupsIdAlreadyInFlight_sameConcurrency() = runTest {
        val coordinator = PingCoordinator(nativeCeiling = 5)
        val counts = ConcurrentHashMap<Long, Int>()
        val release = CompletableDeferred<Unit>()
        val probe: suspend (Long) -> PingState = { id ->
            counts.merge(id, 1, Int::plus); release.await(); PingState.Success(1L)
        }

        val job1 = launch { coordinator.runGroup(listOf(1L, 2L), concurrency = 3, { _, _ -> }, probe) }
        runCurrent()
        val job2 = launch { coordinator.runGroup(listOf(2L, 3L), concurrency = 3, { _, _ -> }, probe) }
        runCurrent()
        release.complete(Unit)
        job1.join(); job2.join()

        assertEquals(1, counts[1L])
        assertEquals(1, counts[2L]) // still in flight from job1 -> job2 skips it
        assertEquals(1, counts[3L])
    }

    @Test
    fun runGroup_concurrencyChange_keepsCrossRunDedup() = runTest {
        val coordinator = PingCoordinator(nativeCeiling = 5)
        val counts = ConcurrentHashMap<Long, Int>()
        val release = CompletableDeferred<Unit>()
        val probe: suspend (Long) -> PingState = { id ->
            counts.merge(id, 1, Int::plus); release.await(); PingState.Success(1L)
        }

        // Run 1 at concurrency 2 admits ids 1,2,3 into the shared in-flight set (only 2 run at once,
        // id 3 waits on the per-run limit but is already admitted/de-duplicated).
        val job1 = launch { coordinator.runGroup(listOf(1L, 2L, 3L), concurrency = 2, { _, _ -> }, probe) }
        runCurrent()
        // Run 2 at a DIFFERENT concurrency overlaps id 3; the stable coordinator must not re-admit it.
        val job2 = launch { coordinator.runGroup(listOf(3L, 4L), concurrency = 5, { _, _ -> }, probe) }
        runCurrent()
        release.complete(Unit)
        job1.join(); job2.join()

        assertEquals(1, counts[3L]) // NOT double-admitted despite the concurrency change (the R5 bug)
        assertEquals(1, counts[4L])
    }

    @Test
    fun runGroup_boundsParallelismByPerRunConcurrency() = runTest {
        val coordinator = PingCoordinator(nativeCeiling = 5)
        val active = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()

        val job = launch {
            coordinator.runGroup(
                ids = (1L..5L).toList(),
                concurrency = 2,
                onUpdate = { _, _ -> },
                probe = {
                    val now = active.incrementAndGet()
                    maxSeen.updateAndGet { max(it, now) }
                    release.await()
                    active.decrementAndGet()
                    PingState.Success(1L)
                }
            )
        }
        runCurrent()
        assertEquals(2, maxSeen.get())
        release.complete(Unit)
        job.join()
    }

    @Test
    fun runGroup_completedId_becomesEligibleAgain() = runTest {
        val coordinator = PingCoordinator(nativeCeiling = 5)
        var attempts = 0
        val states = mutableMapOf<Long, PingState>()
        coordinator.runGroup(listOf(9L), 3, { id, s -> states[id] = s }, { attempts++; PingState.Success(3L) })
        assertEquals(PingState.Success(3L), states[9L])
        coordinator.runGroup(listOf(9L), 3, { id, s -> states[id] = s }, { attempts++; PingState.Success(4L) })
        assertEquals(PingState.Success(4L), states[9L])
        assertEquals(2, attempts)
    }

    @Test
    fun runGroup_emptyIds_noProbe() = runTest {
        val coordinator = PingCoordinator()
        var calls = 0
        var updates = 0
        coordinator.runGroup(emptyList(), 3, { _, _ -> updates++ }, { calls++; PingState.Success(1L) })
        assertEquals(0, calls)
        assertEquals(0, updates)
    }

    @Test
    fun runGroup_probeThrows_emitsUnavailableAndClearsInFlight() = runTest {
        val coordinator = PingCoordinator()
        val states = mutableMapOf<Long, PingState>()
        var attempts = 0
        coordinator.runGroup(listOf(7L), 3, { id, s -> states[id] = s }, { attempts++; throw RuntimeException("boom") })
        assertEquals(PingState.Unavailable, states[7L])
        coordinator.runGroup(listOf(7L), 3, { id, s -> states[id] = s }, { attempts++; PingState.Success(5L) })
        assertEquals(PingState.Success(5L), states[7L])
        assertEquals(2, attempts)
    }

    // ---- R6: bounded native-admission cap (orphans hold a native slot until the JNI call returns) ----

    @Test
    fun probeWithBackstop_nativeReturnsBeforeBackstop_success() = runTest {
        val coordinator = PingCoordinator(nativeCeiling = 2)
        val deferred = async {
            coordinator.probeWithBackstop(
                scope = backgroundScope, backstopMs = 1_000L, context = EmptyCoroutineContext,
                nativeCall = { Result.success(12L) }
            )
        }
        advanceUntilIdle()
        assertEquals(PingState.Success(12L), deferred.await())
        assertEquals(2, coordinator.availableNativeSlots()) // slot released after completion
    }

    @Test
    fun probeWithBackstop_backstopReturnsUnavailable_slotHeldUntilNativeReturns() = runTest {
        val coordinator = PingCoordinator(nativeCeiling = 2)
        // Manual orphan scope on the test scheduler so gate-completion resumption is deterministic.
        val orphanScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val gate = CompletableDeferred<Unit>()
        var backstopped = false
        val state = async {
            coordinator.probeWithBackstop(
                scope = orphanScope, backstopMs = 100L, context = EmptyCoroutineContext,
                onBackstop = { backstopped = true },
                nativeCall = { gate.await(); Result.success(7L) }
            )
        }
        runCurrent()
        assertEquals(1, coordinator.availableNativeSlots()) // one of two reserved

        advanceTimeBy(101L); runCurrent()
        assertEquals(PingState.Unavailable, state.await()) // R6#1: backstop -> Unavailable
        assertTrue(backstopped)
        assertEquals(1, coordinator.availableNativeSlots()) // R6#2: native slot STILL held

        gate.complete(Unit); advanceUntilIdle()
        assertEquals(2, coordinator.availableNativeSlots()) // R6#4: released when JNI returns
        orphanScope.cancel()
    }

    @Test
    fun probeWithBackstop_ceilingFull_rejectsPromptlyWithoutLaunching() = runTest {
        val coordinator = PingCoordinator(nativeCeiling = 1)
        val orphanScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val gate = CompletableDeferred<Unit>()
        val first = async {
            coordinator.probeWithBackstop(
                scope = orphanScope, backstopMs = 50L, context = EmptyCoroutineContext,
                nativeCall = { gate.await(); Result.success(1L) }
            )
        }
        runCurrent()
        assertEquals(0, coordinator.availableNativeSlots())

        var launched = false
        var rejected = false
        val second = async {
            coordinator.probeWithBackstop(
                scope = orphanScope, backstopMs = 50L, context = EmptyCoroutineContext,
                onAdmissionRejected = { rejected = true },
                nativeCall = { launched = true; Result.success(2L) }
            )
        }
        runCurrent()
        assertEquals(PingState.Unavailable, second.await()) // R6#3: retry rejected while full
        assertTrue(rejected)
        assertFalse(launched)

        advanceTimeBy(51L); runCurrent()
        assertEquals(PingState.Unavailable, first.await())
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, coordinator.availableNativeSlots())
        orphanScope.cancel()
    }

    @Test
    fun probeWithBackstop_scopeCancellation_releasesSlot() = runTest {
        val coordinator = PingCoordinator(nativeCeiling = 1)
        val orphanScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val gate = CompletableDeferred<Unit>()
        val state = async {
            coordinator.probeWithBackstop(
                scope = orphanScope, backstopMs = 50L, context = EmptyCoroutineContext,
                nativeCall = { gate.await(); Result.success(1L) }
            )
        }
        runCurrent()
        assertEquals(0, coordinator.availableNativeSlots())

        advanceTimeBy(51L); runCurrent()
        assertEquals(PingState.Unavailable, state.await())
        assertEquals(0, coordinator.availableNativeSlots()) // still held by the orphan

        orphanScope.cancel() // R6#5: cancelling the surrounding scope must not leak the slot
        advanceUntilIdle()
        assertEquals(1, coordinator.availableNativeSlots())
    }
}
