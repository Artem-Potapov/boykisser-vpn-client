package com.justme.xtls_core_proxy.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun staleEpoch_doesNotDeferKill_duringTransition() {
        // Mirrors SessionLifecycleDecisionTest.staleEpochInReviving_doesNotDeferKill, generalized
        // to both transitional states: a kill callback belonging to a superseded session must
        // never be replayed into the newer one.
        for (state in listOf(SessionTunnelState.REVIVING, SessionTunnelState.ROTATING)) {
            assertFalse(
                "a stale epoch must not defer kill during $state",
                shouldDeferKillDuringTransition(
                    running = true, activeSessionEpoch = 6L, callbackSessionEpoch = 5L,
                    tunnelState = state,
                )
            )
        }
    }

    @Test
    fun deferredKillNotice_namesTheApp_whenATunnelIsStillUp() {
        // A give-up discharges the deferred kill instead of replaying it, so the user must be told
        // their kill-switch did not act — the listed app is still riding the tunnel.
        assertEquals(
            "Bank",
            deferredKillNoticeLabel(pendingKillLabel = "Bank", tunnelStillUp = true)
        )
    }

    @Test
    fun deferredKillNotice_isSilent_whenNoKillWasDeferred() {
        // The overwhelmingly common give-up: nothing was deferred, so nothing may be posted.
        assertNull(deferredKillNoticeLabel(pendingKillLabel = null, tunnelStillUp = true))
    }

    @Test
    fun deferredKillNotice_isSilent_whenNoTunnelRemains() {
        // UNPROTECTED (and the give-up that stops the service) leave NO tunnel, so the listed app
        // is not behind a VPN after all. Claiming it still is would be the opposite of the truth.
        assertNull(deferredKillNoticeLabel(pendingKillLabel = "Bank", tunnelStillUp = false))
    }

    @Test
    fun stoppedSession_doesNotDeferKill_duringTransition() {
        // Mirrors SessionLifecycleDecisionTest.stoppedSessionInReviving_doesNotDeferKill,
        // generalized to both transitional states.
        for (state in listOf(SessionTunnelState.REVIVING, SessionTunnelState.ROTATING)) {
            assertFalse(
                "a stopped session must not defer kill during $state",
                shouldDeferKillDuringTransition(
                    running = false, activeSessionEpoch = 5L, callbackSessionEpoch = 5L,
                    tunnelState = state,
                )
            )
        }
    }

    // --- The rotation bridge: no clear network between teardown and the next establish() ---

    @Test
    fun rotationBridge_isEstablished_onceTheRotationHasTornTheTunnelDown() {
        // The gap this closes: rotateTunnel tears the TUN down under `lock`, then does
        // buildRuntimeConfig + geo-asset prep + the split read OFF-lock before it reaches
        // establish(). Every tunneled app emits cleartext for that whole span.
        assertTrue(
            shouldEstablishRotationBridge(
                hasTunnel = false,
                hasRotationBridge = false,
                tunnelState = SessionTunnelState.ROTATING,
            )
        )
    }

    @Test
    fun rotationBridge_isNotEstablishedASecondTime() {
        // One rotation episode walks N dead candidates through the same reserved transition. The
        // bridge is held across all of them; establishing another would strand the first fd, i.e.
        // leak a VPN interface for the rest of the process's life.
        assertFalse(
            shouldEstablishRotationBridge(
                hasTunnel = false,
                hasRotationBridge = true,
                tunnelState = SessionTunnelState.ROTATING,
            )
        )
    }

    @Test
    fun rotationBridge_isNeverEstablishedWhileALiveTunnelIsHeld() {
        // establish() while a real interface is up would replace the interface Xray is dialing
        // through with an unread one — turning a healthy tunnel into a blackhole.
        assertFalse(
            shouldEstablishRotationBridge(
                hasTunnel = true,
                hasRotationBridge = false,
                tunnelState = SessionTunnelState.ROTATING,
            )
        )
    }

    @Test
    fun rotationBridge_isEstablishedOnlyByAReservedRotation() {
        // Scope is rotation ONLY. An initial connect has no prior tunnel to bridge from, and
        // PAUSED is the kill-switch's deliberate no-tunnel state, whose compliance contract is
        // literally "no tunnel must exist".
        for (state in listOf(
            SessionTunnelState.STARTING,
            SessionTunnelState.CONNECTED,
            SessionTunnelState.PAUSED,
            SessionTunnelState.REVIVING,
            SessionTunnelState.STOPPED,
        )) {
            assertFalse(
                "$state has an owner other than a rotation; it must not open a bridge",
                shouldEstablishRotationBridge(
                    hasTunnel = false,
                    hasRotationBridge = false,
                    tunnelState = state,
                )
            )
        }
    }
}
