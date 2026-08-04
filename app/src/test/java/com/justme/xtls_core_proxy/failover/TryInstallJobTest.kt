package com.justme.xtls_core_proxy.failover

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the atomic install half of [TunnelHealthMonitor.resumePolling]. Retirement already goes
 * through `getAndSet(null)`; creation used to be check-then-act (`get != null` then `set`), so a
 * resume racing a stop could park an orphaned poll loop in the slot after stop had cleared it.
 *
 * The full thread interleaving is not staged here (nondeterministic under a real dispatcher); these
 * cases encode the same decisions [tryInstallJob] must make for that race.
 */
class TryInstallJobTest {

    @Test
    fun installsWhenSlotEmptyAndStillLive() {
        val slot = AtomicReference<Job?>(null)
        val candidate = Job()
        assertTrue(tryInstallJob(slot, stillLive = { true }, candidate))
        assertSame(candidate, slot.get())
    }

    @Test
    fun rejectsWhenSlotAlreadyOccupied() {
        val occupant = Job()
        val slot = AtomicReference<Job?>(occupant)
        val candidate = Job()
        assertFalse(tryInstallJob(slot, stillLive = { true }, candidate))
        assertSame("must not displace the live job", occupant, slot.get())
    }

    @Test
    fun rejectsWhenNoLongerLiveBeforeInstall() {
        val slot = AtomicReference<Job?>(null)
        val candidate = Job()
        assertFalse(tryInstallJob(slot, stillLive = { false }, candidate))
        assertNull(slot.get())
    }

    @Test
    fun retiresSelfWhenLiveFlipsFalseAfterCas() {
        // stop() raced: isStarted flipped false after we passed the gate but as we installed.
        // Leaving the candidate in the slot is the orphaned poll loop.
        // MUTATION-VERIFIED: a plain slot.set(candidate) (ignoring the post-CAS stillLive check)
        // leaves the candidate installed and fails assertNull below.
        val slot = AtomicReference<Job?>(null)
        val candidate = Job()
        val live = AtomicBoolean(true)
        val stillLive = {
            // first call (pre-CAS): true; second call (post-CAS): false
            live.getAndSet(false)
        }
        assertFalse(tryInstallJob(slot, stillLive, candidate))
        assertNull("orphaned install must be retired", slot.get())
    }
}
