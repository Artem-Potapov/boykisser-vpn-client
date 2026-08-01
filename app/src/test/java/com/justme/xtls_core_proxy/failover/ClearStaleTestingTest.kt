package com.justme.xtls_core_proxy.failover

import com.justme.xtls_core_proxy.state.PingState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard for connect-to-fastest cancellation coherence.
 *
 * `PingCoordinator.runGroup` rethrows a caller-triggered `CancellationException` from inside its
 * per-id `try/finally` BEFORE invoking `onUpdate` for that id (the catch-and-rethrow happens ahead of
 * the `onUpdate(id, state)` call at the bottom of the per-id `launch`), so an id whose probe was still
 * in flight when `connectFastest`'s Job is cancelled never receives a terminal `PingState` — its row
 * would spin on `Testing` forever without this cleanup. [clearStaleTesting] is the pure decision run
 * from `connectFastest`'s `finally` block to reset exactly those ids, and only those ids, back to
 * `Idle`.
 */
class ClearStaleTestingTest {

    @Test
    fun resetsPoolIdsStuckOnTestingToIdle() {
        val states = mapOf(1L to PingState.Testing, 2L to PingState.Testing)
        val result = clearStaleTesting(states, ids = setOf(1L, 2L))
        assertEquals(mapOf(1L to PingState.Idle, 2L to PingState.Idle), result)
    }

    @Test
    fun leavesResolvedPoolIdsUntouched() {
        // The happy path: runGroup already resolved every id before returning, so cleanup is a no-op.
        val states = mapOf(1L to PingState.Success(42L), 2L to PingState.Unavailable)
        val result = clearStaleTesting(states, ids = setOf(1L, 2L))
        assertEquals(states, result)
    }

    @Test
    fun neverTouchesTestingIdsOutsideThePool() {
        // A concurrent, unrelated group ping test's Testing rows must survive a connectFastest cancel.
        val states = mapOf(1L to PingState.Testing, 99L to PingState.Testing)
        val result = clearStaleTesting(states, ids = setOf(1L))
        assertEquals(mapOf(1L to PingState.Idle, 99L to PingState.Testing), result)
    }
}
