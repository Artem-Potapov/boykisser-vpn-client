package com.justme.xtls_core_proxy.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLifecycleRotationTest {

    @Test
    fun canReserveRotation_onlyFromConnected_onCurrentEpoch() {
        assertTrue(
            canReserveRotation(
                running = true, activeSessionEpoch = 5L, callbackSessionEpoch = 5L,
                tunnelState = SessionTunnelState.CONNECTED,
            )
        )
        assertFalse(
            "a paused tunnel has nothing to rotate",
            canReserveRotation(
                running = true, activeSessionEpoch = 5L, callbackSessionEpoch = 5L,
                tunnelState = SessionTunnelState.PAUSED,
            )
        )
        assertFalse(
            "a stale epoch must never rotate a newer session",
            canReserveRotation(
                running = true, activeSessionEpoch = 6L, callbackSessionEpoch = 5L,
                tunnelState = SessionTunnelState.CONNECTED,
            )
        )
        assertFalse(
            canReserveRotation(
                running = false, activeSessionEpoch = 5L, callbackSessionEpoch = 5L,
                tunnelState = SessionTunnelState.CONNECTED,
            )
        )
    }

    @Test
    fun killIsDeferred_duringBothRevivingAndRotating() {
        // A kill landing mid-transition must be recorded and replayed, never dropped: the
        // foreground monitor is edge-triggered and will not re-fire it.
        for (state in listOf(SessionTunnelState.REVIVING, SessionTunnelState.ROTATING)) {
            assertTrue(
                "kill must defer during $state",
                shouldDeferKillDuringTransition(
                    running = true, activeSessionEpoch = 5L, callbackSessionEpoch = 5L,
                    tunnelState = state,
                )
            )
        }
    }

    @Test
    fun killIsNotDeferred_inSettledStates() {
        for (state in listOf(
            SessionTunnelState.CONNECTED, SessionTunnelState.PAUSED,
            SessionTunnelState.STARTING, SessionTunnelState.STOPPED,
        )) {
            assertFalse(
                "no deferral in $state",
                shouldDeferKillDuringTransition(
                    running = true, activeSessionEpoch = 5L, callbackSessionEpoch = 5L,
                    tunnelState = state,
                )
            )
        }
    }
}
