package com.justme.xtls_core_proxy.killswitch

import com.justme.xtls_core_proxy.killswitch.UsageStatsEventSource.ForegroundEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class UsageStatsForegroundAppMonitorTest {

    private class FakeEventSource : UsageStatsEventSource {
        val script = ArrayDeque<List<ForegroundEvent>>()
        override fun queryForegroundEvents(beginMs: Long, endMs: Long): List<ForegroundEvent> =
            if (script.isEmpty()) emptyList() else script.removeFirst()
    }

    private class RecordingListener : ForegroundAppMonitor.Listener {
        sealed class Event {
            data class Foreground(val pkg: String) : Event()
            data object Left : Event()
        }
        val events = mutableListOf<Event>()
        override fun onControlledAppForeground(packageName: String) {
            events.add(Event.Foreground(packageName))
        }
        override fun onControlledAppLeftForeground() {
            events.add(Event.Left)
        }
    }

    private fun TestScope.teardownMonitor(monitor: UsageStatsForegroundAppMonitor) {
        monitor.stop()
        monitor.shutdownForTesting()
        runCurrent()
        advanceUntilIdle()
    }

    @Test
    fun firesForeground_whenControlledAppAppears() = runTest {
        val source = FakeEventSource().apply {
            script.add(listOf(ForegroundEvent("com.example.controlled", 1_000L)))
        }
        val listener = RecordingListener()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val monitor = UsageStatsForegroundAppMonitor(source, pollIntervalMs = 1_000L, dispatcher = dispatcher)

        monitor.start(packages = setOf("com.example.controlled"), listener = listener)
        advanceTimeBy(1_100L)

        assertEquals(listOf(RecordingListener.Event.Foreground("com.example.controlled")), listener.events)
        teardownMonitor(monitor)
    }

    @Test
    fun firesLeftForeground_whenNonControlledAppAppearsAfterControlled() = runTest {
        val source = FakeEventSource().apply {
            script.add(listOf(ForegroundEvent("com.example.controlled", 1_000L)))
            script.add(listOf(ForegroundEvent("com.example.benign", 2_000L)))
        }
        val listener = RecordingListener()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val monitor = UsageStatsForegroundAppMonitor(source, pollIntervalMs = 1_000L, dispatcher = dispatcher)

        monitor.start(packages = setOf("com.example.controlled"), listener = listener)
        advanceTimeBy(1_100L)
        advanceTimeBy(1_000L)

        assertEquals(
            listOf(
                RecordingListener.Event.Foreground("com.example.controlled"),
                RecordingListener.Event.Left
            ),
            listener.events
        )
        teardownMonitor(monitor)
    }

    @Test
    fun doesNotFire_whenControlledStaysForeground() = runTest {
        val source = FakeEventSource().apply {
            script.add(listOf(ForegroundEvent("com.example.controlled", 1_000L)))
            script.add(emptyList())
            script.add(emptyList())
        }
        val listener = RecordingListener()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val monitor = UsageStatsForegroundAppMonitor(source, pollIntervalMs = 1_000L, dispatcher = dispatcher)

        monitor.start(packages = setOf("com.example.controlled"), listener = listener)
        advanceTimeBy(3_500L)

        assertEquals(listOf(RecordingListener.Event.Foreground("com.example.controlled")), listener.events)
        teardownMonitor(monitor)
    }

    @Test
    fun doesNotFire_switchingBetweenTwoControlledApps() = runTest {
        val source = FakeEventSource().apply {
            script.add(listOf(ForegroundEvent("com.example.a", 1_000L)))
            script.add(listOf(ForegroundEvent("com.example.b", 2_000L)))
        }
        val listener = RecordingListener()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val monitor = UsageStatsForegroundAppMonitor(source, pollIntervalMs = 1_000L, dispatcher = dispatcher)

        monitor.start(packages = setOf("com.example.a", "com.example.b"), listener = listener)
        advanceTimeBy(1_100L)
        advanceTimeBy(1_000L)

        assertEquals(listOf(RecordingListener.Event.Foreground("com.example.a")), listener.events)
        teardownMonitor(monitor)
    }

    @Test
    fun updatePackages_addingCurrentForeground_firesForeground() = runTest {
        val source = FakeEventSource().apply {
            script.add(listOf(ForegroundEvent("com.example.target", 1_000L)))
        }
        val listener = RecordingListener()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val monitor = UsageStatsForegroundAppMonitor(source, pollIntervalMs = 1_000L, dispatcher = dispatcher)

        monitor.start(packages = emptySet(), listener = listener)
        advanceTimeBy(1_100L)
        assertEquals(emptyList<RecordingListener.Event>(), listener.events)

        monitor.updatePackages(setOf("com.example.target"))
        runCurrent()

        assertEquals(listOf(RecordingListener.Event.Foreground("com.example.target")), listener.events)
        teardownMonitor(monitor)
    }

    @Test
    fun updatePackages_removingCurrentForeground_firesLeftForeground() = runTest {
        val source = FakeEventSource().apply {
            script.add(listOf(ForegroundEvent("com.example.target", 1_000L)))
        }
        val listener = RecordingListener()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val monitor = UsageStatsForegroundAppMonitor(source, pollIntervalMs = 1_000L, dispatcher = dispatcher)

        monitor.start(packages = setOf("com.example.target"), listener = listener)
        advanceTimeBy(1_100L)

        monitor.updatePackages(emptySet())
        runCurrent()

        assertEquals(
            listOf(
                RecordingListener.Event.Foreground("com.example.target"),
                RecordingListener.Event.Left
            ),
            listener.events
        )
        teardownMonitor(monitor)
    }

    @Test
    fun emptyEvents_treatedAsNoTransition() = runTest {
        val source = FakeEventSource()
        val listener = RecordingListener()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val monitor = UsageStatsForegroundAppMonitor(source, pollIntervalMs = 1_000L, dispatcher = dispatcher)

        monitor.start(packages = setOf("com.example.controlled"), listener = listener)
        advanceTimeBy(5_000L)

        assertEquals(emptyList<RecordingListener.Event>(), listener.events)
        teardownMonitor(monitor)
    }

    @Test
    fun sourceThrows_monitorStopsItself() = runTest {
        val throwingSource = object : UsageStatsEventSource {
            override fun queryForegroundEvents(beginMs: Long, endMs: Long): List<ForegroundEvent> =
                throw SecurityException("permission revoked")
        }
        val listener = RecordingListener()
        val stopped = AtomicReference(false)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val monitor = UsageStatsForegroundAppMonitor(
            throwingSource,
            pollIntervalMs = 1_000L,
            dispatcher = dispatcher,
            onAutoStop = { stopped.set(true) }
        )

        monitor.start(packages = setOf("com.example.controlled"), listener = listener)
        advanceTimeBy(1_100L)

        assertEquals(true, stopped.get())
        teardownMonitor(monitor)
    }

    @Test
    fun pausePolling_thenResumePolling_preservesControlledState_andFiresLeftWhenForegroundChanges() = runTest {
        val source = FakeEventSource().apply {
            // First poll after start: controlled app foreground.
            script.add(listOf(ForegroundEvent("com.example.controlled", 1_000L)))
            // First poll after resume: foreground is now a benign app.
            script.add(listOf(ForegroundEvent("com.example.benign", 5_000L)))
        }
        val listener = RecordingListener()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val monitor = UsageStatsForegroundAppMonitor(source, pollIntervalMs = 1_000L, dispatcher = dispatcher)

        // runCurrent drives ONLY the first iteration (firstTick=true, no delay).
        // The second iteration's delay(1000) is scheduled but not dispatched, so
        // script[1] is NOT consumed prematurely.
        monitor.start(packages = setOf("com.example.controlled"), listener = listener)
        runCurrent()

        // Sanity: foreground fired from script[0].
        assertEquals(listOf(RecordingListener.Event.Foreground("com.example.controlled")), listener.events)

        // Pause cancels the job; the suspended iter-2 delay will throw when dispatched.
        // Advancing time during pause should NOT fire any listener events.
        monitor.pausePolling()
        advanceTimeBy(3_000L)
        assertEquals(1, listener.events.size)

        // Resume launches a new coroutine; runCurrent drives its first iteration,
        // which consumes script[1] = [benign]. The preserved inControlledState=true
        // means we fire onControlledAppLeftForeground.
        monitor.resumePolling()
        runCurrent()

        assertEquals(
            listOf(
                RecordingListener.Event.Foreground("com.example.controlled"),
                RecordingListener.Event.Left
            ),
            listener.events
        )
        teardownMonitor(monitor)
    }

    @Test
    fun pausePolling_isIdempotent() = runTest {
        val source = FakeEventSource()
        val listener = RecordingListener()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val monitor = UsageStatsForegroundAppMonitor(source, pollIntervalMs = 1_000L, dispatcher = dispatcher)

        monitor.start(packages = setOf("com.example.controlled"), listener = listener)
        monitor.pausePolling()
        monitor.pausePolling()  // second call should be a no-op, not crash
        monitor.resumePolling()
        monitor.resumePolling()  // second call should be a no-op, not crash
        advanceTimeBy(2_000L)
        teardownMonitor(monitor)
    }

    @Test
    fun resumePolling_withoutStart_isHarmlessNoOp() = runTest {
        val source = FakeEventSource()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val monitor = UsageStatsForegroundAppMonitor(source, pollIntervalMs = 1_000L, dispatcher = dispatcher)

        // No start() — resume on a never-started monitor should not throw or launch.
        monitor.resumePolling()
        advanceTimeBy(2_000L)
        teardownMonitor(monitor)
    }

    @Test
    fun stop_calledDuringPollTick_doesNotInvokeListener() = runTest {
        // A listener that records when it was called.
        val scheduler = testScheduler
        val callTimes = mutableListOf<Long>()
        val listener = object : ForegroundAppMonitor.Listener {
            override fun onControlledAppForeground(packageName: String) {
                callTimes.add(scheduler.currentTime)
            }
            override fun onControlledAppLeftForeground() {
                callTimes.add(scheduler.currentTime)
            }
        }

        val source = FakeEventSource().apply {
            script.add(listOf(ForegroundEvent("com.example.controlled", 1_000L)))
        }
        val dispatcher = StandardTestDispatcher(scheduler)
        val monitor = UsageStatsForegroundAppMonitor(source, pollIntervalMs = 1_000L, dispatcher = dispatcher)

        monitor.start(packages = setOf("com.example.controlled"), listener = listener)
        // Stop before any poll has a chance to dispatch.
        monitor.stop()
        advanceTimeBy(5_000L)

        // Even though the script has a controlled event, listener should never have been called.
        assertEquals(emptyList<Long>(), callTimes)
        teardownMonitor(monitor)
    }

    @Test
    fun screenCycleScenario_pausedDuringControlled_thenResumeToBenign_firesLeft() = runTest {
        // Models XrayVpnService's screen receiver: ACTION_SCREEN_OFF calls
        // pausePolling(), ACTION_SCREEN_ON calls resumePolling(). The user
        // locks the phone while a controlled app is foregrounded (paused
        // state), then unlocks to a launcher (benign foreground). The
        // monitor must remember it was in controlled-state and fire
        // onControlledAppLeftForeground so the service can revive the tunnel.
        //
        // Script is staged: we only add the benign event AFTER pausing,
        // otherwise the poll loop would consume it before pause runs.
        val source = FakeEventSource().apply {
            script.add(listOf(ForegroundEvent("com.example.controlled", 1_000L)))
        }
        val listener = RecordingListener()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val monitor = UsageStatsForegroundAppMonitor(source, pollIntervalMs = 1_000L, dispatcher = dispatcher)

        monitor.start(packages = setOf("com.example.controlled"), listener = listener)
        advanceTimeBy(1_100L)
        assertEquals(
            listOf(RecordingListener.Event.Foreground("com.example.controlled")),
            listener.events
        )

        // Simulate ACTION_SCREEN_OFF: pause polling. State preserved.
        monitor.pausePolling()
        advanceTimeBy(30_000L)
        assertEquals(1, listener.events.size)

        // Now stage the benign event. While paused, no poll consumes it.
        source.script.add(listOf(ForegroundEvent("com.example.benign", 60_000L)))

        // Simulate ACTION_SCREEN_ON: resume polling. Next poll sees benign foreground.
        monitor.resumePolling()
        advanceTimeBy(1_100L)

        // Bug #1 manifestation would be: only one event (no "Left"). Fixed:
        // two events, with "Left" emitted because the preserved inControlledState
        // was true and the new foreground is not controlled.
        assertEquals(
            listOf(
                RecordingListener.Event.Foreground("com.example.controlled"),
                RecordingListener.Event.Left
            ),
            listener.events
        )
        teardownMonitor(monitor)
    }
}
