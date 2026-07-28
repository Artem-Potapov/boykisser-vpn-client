package com.justme.xtls_core_proxy.failover

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelHealthMonitorTest {

    private class FakeProbe(var healthy: Boolean = true, var throws: Boolean = false) : HealthProbe {
        var calls = 0
        override suspend fun isHealthy(): Boolean {
            calls++
            if (throws) throw IllegalStateException("probe blew up")
            return healthy
        }
    }

    private class FakeAvailability(var online: Boolean = true, var throws: Boolean = false) : NetworkAvailability {
        var calls = 0
        override fun hasUnderlyingInternet(): Boolean {
            calls++
            if (throws) throw IllegalStateException("availability check blew up")
            return online
        }
    }

    /**
     * Holds the most recently created monitor so [disposeMonitor] can dispose its CoroutineScope.
     * Every test in this class creates exactly one monitor via [monitor]; a test that ever needs
     * more than one must dispose the extra instance(s) itself, since this field only tracks the
     * last one created.
     */
    private var monitorUnderTest: TunnelHealthMonitor? = null

    private fun monitor(
        probe: HealthProbe,
        availability: NetworkAvailability,
        dispatcher: TestDispatcher,
        threshold: Int = 2,
    ): TunnelHealthMonitor {
        val created = TunnelHealthMonitor(
            probe = probe,
            availability = availability,
            intervalMs = 15_000L,
            failureThreshold = threshold,
            dispatcher = dispatcher,
        )
        monitorUnderTest = created
        return created
    }

    @After
    fun disposeMonitor() {
        // Each test's own m.stop() call is left as-is (it exercises the public API under test);
        // this disposes the internal CoroutineScope so no polling job keeps runTest's scheduler
        // non-idle across the seven tests in this class.
        monitorUnderTest?.shutdownForTesting()
        monitorUnderTest = null
    }

    @Test
    fun belowThreshold_doesNotFire_atThreshold_fires() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = FakeProbe(healthy = false)
        val fired = AtomicInteger(0)
        val m = monitor(probe, FakeAvailability(), dispatcher)

        m.start { fired.incrementAndGet() }
        runCurrent()                       // first probe runs immediately
        assertEquals("one failure must not fire", 0, fired.get())

        advanceTimeBy(15_001L)             // second probe -> threshold reached
        runCurrent()
        assertEquals(1, fired.get())
        m.stop()
    }

    @Test
    fun firesOncePerTransition_notPerFailedProbe() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fired = AtomicInteger(0)
        val m = monitor(FakeProbe(healthy = false), FakeAvailability(), dispatcher)

        m.start { fired.incrementAndGet() }
        advanceTimeBy(100_000L)            // many intervals' worth of failures
        runCurrent()
        assertEquals("must latch after firing, like the kill-switch monitor", 1, fired.get())
        m.stop()
    }

    @Test
    fun successResetsTheFailureCounter() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = FakeProbe(healthy = false)
        val fired = AtomicInteger(0)
        val m = monitor(probe, FakeAvailability(), dispatcher)

        m.start { fired.incrementAndGet() }
        runCurrent()                       // fail 1
        probe.healthy = true
        advanceTimeBy(15_001L); runCurrent()   // success -> reset
        probe.healthy = false
        advanceTimeBy(15_001L); runCurrent()   // fail 1 again, not 2
        assertEquals(0, fired.get())
        // A regression that kills the loop after tick 1 would also leave fired == 0, so pin that
        // the loop is genuinely alive: three ticks ran (fail, success, fail), not one.
        assertEquals("loop must keep ticking, not die silently", 3, probe.calls)
        m.stop()
    }

    @Test
    fun offline_neverFires_evenAfterManyFailures() = runTest {
        // Regression guard: airplane mode must not be blamed on the server.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fired = AtomicInteger(0)
        val availability = FakeAvailability(online = false)
        val m = monitor(FakeProbe(healthy = false), availability, dispatcher)

        m.start { fired.incrementAndGet() }
        advanceTimeBy(100_000L); runCurrent()
        assertEquals(0, fired.get())
        // fired == 0 alone can't tell "suppressed by the offline guard" from "loop died on tick
        // 1" — pin that the loop kept ticking across the whole 100s window (ticks at t = 0,
        // 15000, ..., 90000 -> 7 ticks), not just once.
        assertEquals("loop must keep ticking while offline, not die silently", 7, availability.calls)
        m.stop()
    }

    @Test
    fun offline_resetsPartialFailureCount() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = FakeProbe(healthy = false)
        val availability = FakeAvailability(online = true)
        val fired = AtomicInteger(0)
        val m = monitor(probe, availability, dispatcher)

        m.start { fired.incrementAndGet() }
        runCurrent()                       // fail 1 while online
        availability.online = false
        advanceTimeBy(15_001L); runCurrent()   // offline tick resets the count
        availability.online = true
        advanceTimeBy(15_001L); runCurrent()   // fail 1 again, must not fire
        assertEquals(0, fired.get())
        // Pin that the offline tick actually ran (loop alive, not dead) and that it skipped the
        // probe rather than merely not firing: 3 ticks total (online, offline, online) but the
        // probe is only called on the 2 online ticks.
        assertEquals("offline tick must still occur, not be skipped by a dead loop", 3, availability.calls)
        assertEquals("probe must be skipped only on the offline tick", 2, probe.calls)
        m.stop()
    }

    @Test
    fun throwingProbe_countsAsFailure_andLoopSurvives() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = FakeProbe(throws = true)
        val fired = AtomicInteger(0)
        val m = monitor(probe, FakeAvailability(), dispatcher)

        m.start { fired.incrementAndGet() }
        runCurrent()
        advanceTimeBy(15_001L); runCurrent()
        assertEquals("a throw IS the unhealthy signal, it must not kill the loop", 1, fired.get())
        m.stop()
    }

    @Test
    fun throwingAvailability_treatedAsOffline_resetsCounter_loopSurvives() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = FakeProbe(healthy = false)
        val availability = FakeAvailability()
        val fired = AtomicInteger(0)
        val m = monitor(probe, availability, dispatcher)

        m.start { fired.incrementAndGet() }
        runCurrent()                                 // fail 1 while online
        availability.throws = true
        advanceTimeBy(15_001L); runCurrent()         // throwing tick: treated as offline, not fatal
        availability.throws = false
        advanceTimeBy(15_001L); runCurrent()         // fail 1 again, not 2 (counter was reset)

        assertEquals("throwing availability must reset like offline, not fire", 0, fired.get())
        assertEquals("probe must be skipped on the throwing tick", 2, probe.calls)
        assertEquals("loop must survive a throwing availability check", 3, availability.calls)
        m.stop()
    }

    @Test
    fun firedMonitor_isTerminal_pauseAndResumeDoNotRevive() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = FakeProbe(healthy = false)
        val fired = AtomicInteger(0)
        val m = monitor(probe, FakeAvailability(), dispatcher)

        m.start { fired.incrementAndGet() }
        runCurrent()                           // fail 1
        advanceTimeBy(15_001L); runCurrent()   // fail 2 -> fires, monitor goes terminal
        assertEquals(1, fired.get())
        val callsAfterFire = probe.calls

        // A pause/resume landing after the fire — in either order — must never revive polling:
        // the monitor is terminal until a fresh start(). Without the isStarted/job reset on the
        // fire path, this either silently no-ops (looks resumed but isn't) or, worse, relaunches
        // a loop that can never fire again because reportedUnhealthy is still latched true.
        m.pausePolling()
        m.resumePolling()
        advanceTimeBy(100_000L); runCurrent()

        assertEquals("a terminal monitor must never fire twice", 1, fired.get())
        assertEquals("a terminal monitor must never poll again", callsAfterFire, probe.calls)
        m.stop()
    }

    @Test
    fun pauseStopsProbing_resumeProbesImmediately() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = FakeProbe(healthy = true)
        val m = monitor(probe, FakeAvailability(), dispatcher)

        m.start { }
        runCurrent()
        val afterStart = probe.calls
        m.pausePolling()
        advanceTimeBy(100_000L); runCurrent()
        assertEquals("paused monitor must not probe", afterStart, probe.calls)

        m.resumePolling()
        runCurrent()
        assertEquals("resume must probe immediately, not wait an interval", afterStart + 1, probe.calls)
        m.stop()
    }
}
