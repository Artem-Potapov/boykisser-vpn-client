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

    private class FakeAvailability(var online: Boolean = true) : NetworkAvailability {
        override fun hasUnderlyingInternet(): Boolean = online
    }

    /** Captures every monitor created via [monitor] so teardown can dispose its CoroutineScope. */
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
        m.stop()
    }

    @Test
    fun offline_neverFires_evenAfterManyFailures() = runTest {
        // Regression guard: airplane mode must not be blamed on the server.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fired = AtomicInteger(0)
        val m = monitor(FakeProbe(healthy = false), FakeAvailability(online = false), dispatcher)

        m.start { fired.incrementAndGet() }
        advanceTimeBy(100_000L); runCurrent()
        assertEquals(0, fired.get())
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
