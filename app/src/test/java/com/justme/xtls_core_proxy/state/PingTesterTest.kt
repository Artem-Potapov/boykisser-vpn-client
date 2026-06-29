package com.justme.xtls_core_proxy.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class PingTesterTest {

    @Test
    fun fromResult_mapsSuccessAndFailure() {
        assertEquals(PingState.Success(42L), PingState.fromResult(Result.success(42L)))
        assertEquals(PingState.Unavailable, PingState.fromResult(Result.failure(RuntimeException("x"))))
    }

    @Test
    fun testAll_capsConcurrencyAtMax() = runTest {
        val tester = PingTester(maxConcurrency = 3)
        val active = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()
        val results = mutableMapOf<Long, PingState>()

        val job = launch {
            tester.testAll(
                ids = (1L..6L).toList(),
                onUpdate = { id, state ->
                    if (state is PingState.Success || state is PingState.Unavailable) results[id] = state
                },
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
        assertEquals(3, maxSeen.get())
        release.complete(Unit)
        job.join()
        assertEquals(6, results.size)
        assertEquals(3, maxSeen.get())
    }

    @Test
    fun testAll_skipsIdsAlreadyInFlight() = runTest {
        val tester = PingTester(maxConcurrency = 3)
        val probeCounts = HashMap<Long, Int>()
        val release = CompletableDeferred<Unit>()
        val probe: suspend (Long) -> PingState = { id ->
            probeCounts[id] = (probeCounts[id] ?: 0) + 1
            release.await()
            PingState.Success(1L)
        }

        val job1 = launch { tester.testAll(listOf(1L, 2L), { _, _ -> }, probe) }
        runCurrent()
        val job2 = launch { tester.testAll(listOf(2L, 3L), { _, _ -> }, probe) }
        runCurrent()
        release.complete(Unit)
        job1.join(); job2.join()

        assertEquals(1, probeCounts[1L])
        assertEquals(1, probeCounts[2L]) // skipped by job2 because still in flight
        assertEquals(1, probeCounts[3L])
    }
}
