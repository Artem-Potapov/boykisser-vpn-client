package com.justme.xtls_core_proxy.failover

import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.state.PingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FastestPickTest {

    private fun profile(id: Long) = Profile(id = id, name = "s$id", config = "{}")
    private val candidates = listOf(profile(1), profile(2), profile(3))

    @Test
    fun picksLowestLatencySuccess() {
        val states = mapOf(
            1L to PingState.Success(120L),
            2L to PingState.Success(45L),
            3L to PingState.Success(300L),
        )
        assertEquals(2L, pickFastest(states, candidates)?.id)
    }

    @Test
    fun ignoresUnavailableAndTesting() {
        val states = mapOf(
            1L to PingState.Unavailable,
            2L to PingState.Testing,
            3L to PingState.Success(300L),
        )
        assertEquals(3L, pickFastest(states, candidates)?.id)
    }

    @Test
    fun returnsNullWhenNothingSucceeded() {
        val states = mapOf(1L to PingState.Unavailable, 2L to PingState.Unavailable)
        assertNull(pickFastest(states, candidates))
    }

    @Test
    fun ignoresResultsForProfilesOutsideTheCandidateList() {
        val states = mapOf(99L to PingState.Success(1L), 1L to PingState.Success(120L))
        assertEquals(1L, pickFastest(states, candidates)?.id)
    }
}
