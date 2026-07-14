package com.justme.xtls_core_proxy.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLifecycleDecisionTest {
    @Test
    fun matchingRunningSession_acceptsLifecycleCallback() {
        assertTrue(
            acceptsSessionLifecycleCallback(
                running = true,
                activeSessionEpoch = 42L,
                callbackSessionEpoch = 42L,
            )
        )
    }

    @Test
    fun laterRunningSession_rejectsStaleCallback() {
        assertFalse(
            acceptsSessionLifecycleCallback(
                running = true,
                activeSessionEpoch = 43L,
                callbackSessionEpoch = 42L,
            )
        )
    }

    @Test
    fun stoppedSession_rejectsMatchingCallback() {
        assertFalse(
            acceptsSessionLifecycleCallback(
                running = false,
                activeSessionEpoch = 42L,
                callbackSessionEpoch = 42L,
            )
        )
    }

    @Test
    fun pausedSession_firstReviveIsAccepted_secondIsRejected() {
        assertTrue(
            canReserveRevive(
                running = true,
                activeSessionEpoch = 42L,
                callbackSessionEpoch = 42L,
                tunnelState = SessionTunnelState.PAUSED,
            )
        )
        assertFalse(
            canReserveRevive(
                running = true,
                activeSessionEpoch = 42L,
                callbackSessionEpoch = 42L,
                tunnelState = SessionTunnelState.REVIVING,
            )
        )
    }

    @Test
    fun staleEpoch_rejectsPausedRevive() {
        assertFalse(
            canReserveRevive(
                running = true,
                activeSessionEpoch = 43L,
                callbackSessionEpoch = 42L,
                tunnelState = SessionTunnelState.PAUSED,
            )
        )
    }

    @Test
    fun stoppedSession_rejectsPausedRevive() {
        assertFalse(
            canReserveRevive(
                running = false,
                activeSessionEpoch = 42L,
                callbackSessionEpoch = 42L,
                tunnelState = SessionTunnelState.PAUSED,
            )
        )
    }

    @Test
    fun currentSessionInReviving_defersKill() {
        assertTrue(
            shouldDeferKillDuringRevive(
                running = true,
                activeSessionEpoch = 42L,
                callbackSessionEpoch = 42L,
                tunnelState = SessionTunnelState.REVIVING,
            )
        )
    }

    @Test
    fun currentSessionConnected_doesNotDeferKill() {
        assertFalse(
            shouldDeferKillDuringRevive(
                running = true,
                activeSessionEpoch = 42L,
                callbackSessionEpoch = 42L,
                tunnelState = SessionTunnelState.CONNECTED,
            )
        )
    }

    @Test
    fun currentSessionPaused_doesNotDeferKill() {
        assertFalse(
            shouldDeferKillDuringRevive(
                running = true,
                activeSessionEpoch = 42L,
                callbackSessionEpoch = 42L,
                tunnelState = SessionTunnelState.PAUSED,
            )
        )
    }

    @Test
    fun staleEpochInReviving_doesNotDeferKill() {
        assertFalse(
            shouldDeferKillDuringRevive(
                running = true,
                activeSessionEpoch = 43L,
                callbackSessionEpoch = 42L,
                tunnelState = SessionTunnelState.REVIVING,
            )
        )
    }

    @Test
    fun stoppedSessionInReviving_doesNotDeferKill() {
        assertFalse(
            shouldDeferKillDuringRevive(
                running = false,
                activeSessionEpoch = 42L,
                callbackSessionEpoch = 42L,
                tunnelState = SessionTunnelState.REVIVING,
            )
        )
    }
}
