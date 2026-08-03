package com.justme.xtls_core_proxy.failover

import com.justme.xtls_core_proxy.db.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverDecisionTest {

    private fun profile(id: Long) = Profile(id = id, name = "s$id", config = "{}", subscriptionId = 7L)
    private val pool = listOf(profile(1), profile(2), profile(3))

    @Test
    fun nextCandidate_skipsCurrent_andReturnsFirstInListOrder() {
        assertEquals(2L, FailoverDecision.nextCandidate(pool, currentId = 1L, recentlyFailed = emptySet())?.id)
    }

    @Test
    fun nextCandidate_skipsRecentlyFailed() {
        assertEquals(3L, FailoverDecision.nextCandidate(pool, currentId = 1L, recentlyFailed = setOf(2L))?.id)
    }

    @Test
    fun nextCandidate_returnsNullWhenExhausted() {
        assertNull(FailoverDecision.nextCandidate(pool, currentId = 1L, recentlyFailed = setOf(2L, 3L)))
    }

    @Test
    fun nextCandidate_returnsNullForSingleServerPool() {
        assertNull(FailoverDecision.nextCandidate(listOf(profile(1)), currentId = 1L, recentlyFailed = emptySet()))
    }

    @Test
    fun nextCandidate_followsListOrder_notIdOrder_andIsStableAcrossCalls() {
        // This used to compare nextCandidate(...) against nextCandidate(...) with identical
        // arguments, which a pure function cannot fail. What needs pinning is WHICH order
        // "deterministic" means: the pool arrives in the same order the UI lists servers, so the
        // pick must follow list POSITION, not the id value — and a pool whose ids run backwards is
        // the only shape that can tell those two apart. `nextCandidate_skipsCurrent_...` uses
        // [1, 2, 3], where list order and id order agree, so it cannot.
        val reversed = listOf(profile(3), profile(1), profile(2))
        assertEquals(
            "must take the first eligible server in LIST order, not the lowest id",
            3L,
            FailoverDecision.nextCandidate(reversed, currentId = 1L, recentlyFailed = emptySet())?.id,
        )
        assertEquals(
            "and must answer the same way every time it is asked",
            3L,
            FailoverDecision.nextCandidate(reversed, currentId = 1L, recentlyFailed = emptySet())?.id,
        )
    }

    @Test
    fun admitRotation_allowsUpToMaxWithinWindow() {
        var attempts = emptyList<Long>()
        repeat(3) { i ->
            val r = FailoverDecision.admitRotation(attempts, now = 1_000L + i, maxRotations = 3, windowMs = 600_000L)
            assertTrue("rotation ${i + 1} of 3 must be admitted", r is RotationAdmission.Admitted)
            attempts = (r as RotationAdmission.Admitted).attempts
        }
        val denied = FailoverDecision.admitRotation(attempts, now = 2_000L, maxRotations = 3, windowMs = 600_000L)
        assertTrue("the 4th within the window must be denied", denied is RotationAdmission.Denied)
    }

    @Test
    fun admitRotation_windowSlides_soOldAttemptsExpire() {
        val old = listOf(1_000L, 2_000L, 3_000L)
        val r = FailoverDecision.admitRotation(
            attempts = old,
            now = 3_000L + 600_001L,          // past the newest attempt: all three expire
            maxRotations = 3,
            windowMs = 600_000L,
        )
        assertTrue(r is RotationAdmission.Admitted)
        assertEquals("expired attempts must be pruned", 1, (r as RotationAdmission.Admitted).attempts.size)
    }
}
