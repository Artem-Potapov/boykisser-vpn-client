package com.justme.xtls_core_proxy.state

import com.justme.xtls_core_proxy.log.VpnConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Task 7: Reconnect out of a give-up is a stop-then-start SEQUENCE, not a plain start. The
 * sequencing lives in [ReconnectFlow] precisely so it can be driven here, framework-free, the same
 * way `FastestConnectRunnerTest` drives the other piece of connect orchestration.
 *
 * Every assertion below is about ORDER or COUNT — co-occurrence is not enough. The defects they pin
 * are millisecond races against `XrayVpnService`'s teardown, so they would otherwise present as
 * "works on my device, not theirs".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectFlowTest {

    /** One flow under test, recording every dispatch into [calls] in the order it happened. */
    private fun flowFor(
        state: MutableStateFlow<VpnConnectionState>,
        calls: MutableList<String>,
        scheduler: TestCoroutineScheduler,
    ) = ReconnectFlow(
        connectionState = state,
        stop = { calls += "stop" },
        start = { calls += "start:$it" },
        onTimeout = { calls += "timeout" },
        onSuperseded = { calls += "superseded" },
        dispatcher = StandardTestDispatcher(scheduler),
    )

    @Test
    fun reconnectStartsOnlyAfterTheStopHasSettled() = runTest {
        // Dispatching ACTION_START while the service is still running would hit startVpn's
        // "VPN already running" early return and, worse, leave ActiveProfileRepository naming a
        // server the tunnel never carried.
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val calls = mutableListOf<String>()
        val flow = flowFor(state, calls, testScheduler)

        flow.run(profileId = 7L, scope = this)
        runCurrent()
        assertEquals(listOf("stop"), calls)

        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()
        assertEquals(listOf("stop", "start:7"), calls)

        // Let the start-verification await settle so this test ends on a completed flow rather than
        // on runTest draining it — the re-dispatch it guards has its own tests below.
        state.value = VpnConnectionState.CONNECTING
        runCurrent()
    }

    @Test
    fun aStopThatNeverSettlesReportsRatherThanHanging() = runTest {
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val calls = mutableListOf<String>()
        val flow = flowFor(state, calls, testScheduler)

        flow.run(profileId = 7L, scope = this)
        advanceTimeBy(ReconnectFlow.STOP_TIMEOUT_MS + 1); runCurrent()

        assertEquals("must never dispatch a start it could not sequence", listOf("stop", "timeout"), calls)
    }

    @Test
    fun aStartSwallowedByTheServiceDestructionWindowIsReDispatchedOnce() = runTest {
        // stopVpn publishes DISCONNECTED about a dozen lines BEFORE it calls stopSelf(), so a start
        // dispatched the instant that state lands can reach AMS inside the service's own
        // destruction window: onStartCommand runs, then the pending stopSelf() tears the new
        // session down along with the old one. The observable symptom is the state never leaving
        // DISCONNECTED — the user taps Reconnect and the VPN ends up off, silently.
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val calls = mutableListOf<String>()
        val flow = flowFor(state, calls, testScheduler)

        flow.run(profileId = 7L, scope = this)
        runCurrent()
        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()
        assertEquals(listOf("stop", "start:7"), calls)

        advanceTimeBy(ReconnectFlow.START_VERIFY_MS + 1); runCurrent()
        assertEquals(
            "a start swallowed by the destruction window must be re-dispatched",
            listOf("stop", "start:7", "start:7"),
            calls
        )

        // Bounded at exactly one: a start that fails for a REAL reason (no profile, permission
        // revoked) fails the same way twice, and must then stop rather than loop forever.
        advanceTimeBy(ReconnectFlow.START_VERIFY_MS * 4); runCurrent()
        assertEquals(
            "the re-dispatch must not become a retry loop",
            listOf("stop", "start:7", "start:7"),
            calls
        )
    }

    @Test
    fun aStartThatTookIsNeverReDispatched() = runTest {
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val calls = mutableListOf<String>()
        val flow = flowFor(state, calls, testScheduler)

        flow.run(profileId = 7L, scope = this)
        runCurrent()
        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()
        // startVpn announces CONNECTING almost immediately: the start took, and a second one would
        // be a pointless intent into a healthy bring-up.
        state.value = VpnConnectionState.CONNECTING
        runCurrent()

        advanceTimeBy(ReconnectFlow.START_VERIFY_MS + 1); runCurrent()
        assertEquals(listOf("stop", "start:7"), calls)
    }

    @Test
    fun aSecondReconnectWhileOneIsInFlightIsRefusedAndReported() = runTest {
        // The teardown window can last seconds (CONTAINED_BY_LIVE_TUNNEL closes a real Xray core in
        // it) while the state stays BLACKHOLED, so a re-tap is the expected user response. A second
        // stop() landing after the first flow's start has set `running = true` takes the FULL
        // teardown and kills the session the user just asked for.
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val calls = mutableListOf<String>()
        val flow = flowFor(state, calls, testScheduler)

        assertNotNull(flow.run(profileId = 7L, scope = this))
        runCurrent()
        assertEquals(listOf("stop"), calls)

        assertNull(
            "a contending reconnect must be refused, not queued and not swapped in",
            flow.run(profileId = 9L, scope = this)
        )
        runCurrent()
        assertEquals(
            "the refusal must be reported, never a silent drop, and must not re-dispatch stop",
            listOf("stop", "superseded"),
            calls
        )

        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()
        assertEquals(
            "the FIRST request wins the slot, matching this branch's parked-connect rule",
            listOf("stop", "superseded", "start:7"),
            calls
        )
    }

    @Test
    fun theInFlightGuardReleasesSoALaterReconnectIsAdmitted() = runTest {
        // Without this, Reconnect would work exactly once per process.
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val calls = mutableListOf<String>()
        val flow = flowFor(state, calls, testScheduler)

        flow.run(profileId = 7L, scope = this)
        runCurrent()
        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()
        state.value = VpnConnectionState.CONNECTING
        runCurrent()
        assertEquals(listOf("stop", "start:7"), calls)

        state.value = VpnConnectionState.BLACKHOLED
        assertNotNull(
            "the guard must release once the flow completes",
            flow.run(profileId = 9L, scope = this)
        )
        runCurrent()
        assertEquals(listOf("stop", "start:7", "stop"), calls)
    }
}
