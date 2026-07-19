package com.justme.xtls_core_proxy.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P3-R4: the once-per-app-launch latch is process-scoped (survives Activity/ViewModel replacement),
 * not a ViewModel-instance field. These tests exercise the pure process holder directly; the
 * MainActivity wiring stays a thin shell over [shouldAutoPing] + this latch.
 */
class AutoPingLatchTest {

    @Before
    fun setUp() {
        // Reset in setup WITHOUT persisting — the latch is process-scoped, never written to disk.
        AutoPingLatch.resetForTest()
    }

    @Test
    fun starts_unconsumed() {
        assertFalse(AutoPingLatch.isConsumed)
        // enabled + unconsumed -> auto-ping is allowed
        assertTrue(shouldAutoPing(autoOnOpen = true, alreadyConsumed = AutoPingLatch.isConsumed))
    }

    @Test
    fun first_consume_wins_and_marks_consumed() {
        assertTrue(AutoPingLatch.consume())
        assertTrue(AutoPingLatch.isConsumed)
        // enabled + consumed -> no auto-ping
        assertFalse(shouldAutoPing(autoOnOpen = true, alreadyConsumed = AutoPingLatch.isConsumed))
    }

    @Test
    fun second_consume_loses_and_stays_consumed() {
        assertTrue(AutoPingLatch.consume())
        assertFalse(AutoPingLatch.consume())
        assertTrue(AutoPingLatch.isConsumed)
    }

    @Test
    fun new_activity_or_viewmodel_facade_still_sees_consumed_state() {
        // One Activity/VM facade consumes the latch...
        AutoPingLatch.consume()
        // ...a *different* Activity/VM instance reading the SAME process holder still observes
        // consumed, so it must not re-ping (the exact defect P3-R4 fixes).
        assertTrue(AutoPingLatch.isConsumed)
        assertFalse(shouldAutoPing(autoOnOpen = true, alreadyConsumed = AutoPingLatch.isConsumed))
    }

    @Test
    fun reset_clears_without_persisting_mirroring_process_death() {
        AutoPingLatch.consume()
        AutoPingLatch.resetForTest()
        assertFalse(AutoPingLatch.isConsumed)
        assertTrue(shouldAutoPing(autoOnOpen = true, alreadyConsumed = AutoPingLatch.isConsumed))
    }
}
