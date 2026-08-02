package com.justme.xtls_core_proxy.state

import com.justme.xtls_core_proxy.log.VpnConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Task 7: Reconnect out of a give-up is a stop-then-start SEQUENCE, not a plain start. The
 * sequencing lives in [ReconnectFlow] precisely so it can be driven here, framework-free, the same
 * way `FastestConnectRunnerTest` drives the other piece of connect orchestration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectFlowTest {

    @Test
    fun reconnectStartsOnlyAfterTheStopHasSettled() = runTest {
        // Dispatching ACTION_START while the service is still running would hit startVpn's
        // "VPN already running" early return and, worse, leave ActiveProfileRepository naming a
        // server the tunnel never carried.
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val calls = mutableListOf<String>()
        val flow = ReconnectFlow(
            connectionState = state,
            stop = { calls += "stop" },
            start = { calls += "start:$it" },
            onTimeout = { calls += "timeout" },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        flow.run(profileId = 7L, scope = this)
        runCurrent()
        assertEquals(listOf("stop"), calls)

        state.value = VpnConnectionState.DISCONNECTED
        runCurrent()
        assertEquals(listOf("stop", "start:7"), calls)
    }

    @Test
    fun aStopThatNeverSettlesReportsRatherThanHanging() = runTest {
        val state = MutableStateFlow(VpnConnectionState.BLACKHOLED)
        val calls = mutableListOf<String>()
        val flow = ReconnectFlow(
            connectionState = state,
            stop = { calls += "stop" },
            start = { calls += "start:$it" },
            onTimeout = { calls += "timeout" },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        flow.run(profileId = 7L, scope = this)
        advanceTimeBy(ReconnectFlow.STOP_TIMEOUT_MS + 1); runCurrent()

        assertEquals("must never dispatch a start it could not sequence", listOf("stop", "timeout"), calls)
    }
}
