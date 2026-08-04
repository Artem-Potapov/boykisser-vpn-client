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

    // --- The app left while the transition was still in flight: withdraw the deferred kill ---

    @Test
    fun leftForeground_withdrawsTheKillDeferredForThatApp() {
        // The enter edge CREATED the deferral; the leave edge is the signal that the condition it
        // was deferred for has ended. Dropping the leave instead parks the tunnel PAUSED for an app
        // that is no longer in the foreground, and the edge-triggered monitor never re-fires it.
        assertEquals(
            "Bank",
            deferredKillToWithdraw(
                pendingKillLabel = "Bank",
                running = true, activeSessionEpoch = 5L, callbackSessionEpoch = 5L,
            )
        )
    }

    @Test
    fun leftForeground_withdrawsNothing_whenNoKillWasDeferred() {
        // The overwhelmingly common leave: an ordinary kill-switch revive with no deferral armed.
        assertNull(
            deferredKillToWithdraw(
                pendingKillLabel = null,
                running = true, activeSessionEpoch = 5L, callbackSessionEpoch = 5L,
            )
        )
    }

    @Test
    fun aSupersededSessionCanNeverWithdrawALiveSessionsDeferredKill() {
        // The one reason a leave callback must be refused. Withdrawal cancels a SAFETY event, so a
        // late callback from a session that has already been replaced must not reach the marker the
        // live session armed — it would silently un-arm a kill the user asked for.
        assertNull(
            "a stale epoch must not withdraw",
            deferredKillToWithdraw(
                pendingKillLabel = "Bank",
                running = true, activeSessionEpoch = 6L, callbackSessionEpoch = 5L,
            )
        )
        assertNull(
            "a stopped session must not withdraw",
            deferredKillToWithdraw(
                pendingKillLabel = "Bank",
                running = false, activeSessionEpoch = 5L, callbackSessionEpoch = 5L,
            )
        )
    }

    @Test
    fun everyStateThatCanDeferAKillCanAlsoWithdrawIt() {
        // Anti-drift guard, whole-enum. shouldDeferKillDuringTransition owns the {REVIVING,
        // ROTATING} set and this rule must never re-enumerate it — so instead of listing states,
        // assert the IMPLICATION over every state there is: wherever a kill can be parked, the
        // leave edge can take it back.
        //
        // The `deferrable` counter is what stops this being VACUOUS, and it is the whole reason the
        // assertion below is worth anything. deferredKillToWithdraw takes no tunnel state (by
        // design — see its KDoc), so the call inside the loop is identical on every iteration and
        // the `continue` is the only thing `state` controls. Without the final check, mutating
        // shouldDeferKillDuringTransition to return false everywhere would skip every iteration and
        // leave this test GREEN — i.e. it would be silenced by breaking the very rule it guards.
        var deferrable = 0
        for (state in SessionTunnelState.entries) {
            val canDefer = shouldDeferKillDuringTransition(
                running = true, activeSessionEpoch = 5L, callbackSessionEpoch = 5L,
                tunnelState = state,
            )
            if (!canDefer) continue
            deferrable++
            assertEquals(
                "a kill deferrable in $state must be withdrawable in $state",
                "Bank",
                deferredKillToWithdraw(
                    pendingKillLabel = "Bank",
                    running = true, activeSessionEpoch = 5L, callbackSessionEpoch = 5L,
                )
            )
        }
        assertEquals(
            "the deferral rule must still name REVIVING and ROTATING — if it names neither, this " +
                "test asserted nothing and the implication above is unproven",
            2,
            deferrable,
        )
    }

    @Test
    fun withdrawalIsDeliberatelyWIDERThanDeferral_notAMirrorOfIt() {
        // DO NOT "fix" this into a mirror of shouldDeferKillDuringTransition. The asymmetry is the
        // whole fix. `tunnelOpScope` is Dispatchers.IO.limitedParallelism(1) and bringUpTunnel does
        // NOT suspend, so a leave arriving during a bring-up sits in the queue until the transition
        // coroutine finishes — by which time the session is back at CONNECTED. A withdrawal gated
        // on {REVIVING, ROTATING} would therefore miss the entire bring-up, which is exactly where
        // a leave lands. The same applies to the CONNECTED gap a rotation episode passes through
        // between two failed candidates, where the marker stays armed.
        //
        // Hence: in CONNECTED a kill arriving now would NOT be deferred, yet one already parked
        // MUST still be withdrawable. That is what lets the withdrawal win its race against the
        // replay, which reads the marker at execution time rather than at the commit.
        assertFalse(
            "precondition: CONNECTED is not a state a kill can be deferred in",
            shouldDeferKillDuringTransition(
                running = true, activeSessionEpoch = 5L, callbackSessionEpoch = 5L,
                tunnelState = SessionTunnelState.CONNECTED,
            )
        )
        assertEquals(
            "a kill parked during the transition must survive as withdrawable after it commits",
            "Bank",
            deferredKillToWithdraw(
                pendingKillLabel = "Bank",
                running = true, activeSessionEpoch = 5L, callbackSessionEpoch = 5L,
            )
        )
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
