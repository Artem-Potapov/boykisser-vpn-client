package com.justme.xtls_core_proxy.vpn

import android.content.Context
import com.justme.xtls_core_proxy.failover.FailoverPreferences
import com.justme.xtls_core_proxy.testutil.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock

class SessionLifecycleRotationTest {

    @Test
    fun canReserveRotation_onlyFromConnected_onCurrentEpoch() {
        assertTrue(
            canReserveRotation(
                running = true, activeSessionEpoch = 5L, callbackSessionEpoch = 5L,
                tunnelState = SessionTunnelState.CONNECTED,
                failoverEnabled = true,
            )
        )
        assertFalse(
            "a paused tunnel has nothing to rotate",
            canReserveRotation(
                running = true, activeSessionEpoch = 5L, callbackSessionEpoch = 5L,
                tunnelState = SessionTunnelState.PAUSED,
                failoverEnabled = true,
            )
        )
        assertFalse(
            "a stale epoch must never rotate a newer session",
            canReserveRotation(
                running = true, activeSessionEpoch = 6L, callbackSessionEpoch = 5L,
                tunnelState = SessionTunnelState.CONNECTED,
                failoverEnabled = true,
            )
        )
        assertFalse(
            canReserveRotation(
                running = false, activeSessionEpoch = 5L, callbackSessionEpoch = 5L,
                tunnelState = SessionTunnelState.CONNECTED,
                failoverEnabled = true,
            )
        )
    }

    @Test
    fun canReserveRotation_refusesWhenFailoverDisabled() {
        // Queued rotateTunnel (mid-episode recursive dispatch, or a monitor callback already on
        // tunnelOpScope) must not reserve after the user switched the feature off — otherwise the
        // disable branch's thrash-window reset hands it a fresh budget and it keeps switching.
        assertFalse(
            canReserveRotation(
                running = true, activeSessionEpoch = 5L, callbackSessionEpoch = 5L,
                tunnelState = SessionTunnelState.CONNECTED,
                failoverEnabled = false,
            )
        )
    }

    @Test
    fun failedCandidate_bridgeHeld_disableBeforeQueuedRetry_funnelsToGiveUp() {
        // A failed candidate has torn down the live TUN, reopened the unread bridge, and returned
        // the episode to CONNECTED before queueing the next rotation. If disable wins that queue
        // race, the bridge is the sole containment and must be adopted by the give-up funnel, not
        // released on the reservation-refusal path.
        assertFalse(
            canReserveRotation(
                running = true,
                activeSessionEpoch = 5L,
                callbackSessionEpoch = 5L,
                tunnelState = SessionTunnelState.CONNECTED,
                failoverEnabled = false,
            )
        )
        assertTrue(
            shouldFunnelRotationReservationRefusal(
                running = true,
                activeSessionEpoch = 5L,
                callbackSessionEpoch = 5L,
                tunnelState = SessionTunnelState.CONNECTED,
                hasTunnel = false,
                hasRotationBridge = true,
            )
        )
        assertEquals(
            GiveUpContainment.ADOPT_ROTATION_BRIDGE,
            containmentForGiveUp(
                hasTunnel = false,
                hasRotationBridge = true,
                tunnelState = SessionTunnelState.CONNECTED,
            )
        )
    }

    @Test
    fun queuedRetry_admissionUsesTheAuthoritativeDisabledState() {
        // The service cache may still say enabled while FailoverPreferences.state already carries
        // the synchronous save. The value passed to canReserveRotation must be that current state.
        val serviceCacheEnabled = true
        val authoritativeEnabled = false
        assertTrue("fixture: the service cache is stale", serviceCacheEnabled)
        assertFalse(
            canReserveRotation(
                running = true,
                activeSessionEpoch = 5L,
                callbackSessionEpoch = 5L,
                tunnelState = SessionTunnelState.CONNECTED,
                failoverEnabled = authoritativeEnabled,
            )
        )
    }

    @Test
    fun queuedRetry_admissionBoundaryReadsTheLiveFailoverPreferencesState() {
        val prefs = InMemorySharedPreferences()
        val context = mock<Context> {
            on { getSharedPreferences(eq("xray_prefs"), eq(Context.MODE_PRIVATE)) } doReturn prefs
        }
        val connected = {
            canReserveRotationFromAuthoritativeState(
                running = true,
                activeSessionEpoch = 5L,
                callbackSessionEpoch = 5L,
                tunnelState = SessionTunnelState.CONNECTED,
            )
        }

        try {
            FailoverPreferences.save(context, FailoverPreferences.DEFAULT.copy(enabled = true))
            assertTrue("enabled state should admit a current connected rotation", connected())

            // This is the queued-retry race: the service's collected cache may still be true, but
            // the synchronous persistence boundary has already published the disable.
            FailoverPreferences.save(context, FailoverPreferences.DEFAULT.copy(enabled = false))
            assertFalse(
                "reservation must read the live StateFlow after disable, not a stale service cache",
                connected(),
            )
        } finally {
            FailoverPreferences.save(context, FailoverPreferences.DEFAULT)
        }
    }

    @Test
    fun theAuthoritativeReadCarriesTheWholeTuple_notJustTheEnabledFlag() {
        // N-7: the enable veto was the only read routed through the authoritative source, while the
        // thrash cap, the re-arm delay and the UNPROTECTED stop deadline all read the service's
        // collected cache — one question with two answers in one file. save() publishes to the
        // process StateFlow SYNCHRONOUSLY; the service's cache is updated by an asynchronous
        // collector, so it can be a whole tuple behind. Every field the service acts on must come
        // from here.
        val prefs = InMemorySharedPreferences()
        val context = mock<Context> {
            on { getSharedPreferences(eq("xray_prefs"), eq(Context.MODE_PRIVATE)) } doReturn prefs
        }
        val edited = FailoverPreferences.DEFAULT.copy(
            enabled = true,
            maxRotations = 9,
            rotationWindowMs = 1_800_000L,
        )
        try {
            FailoverPreferences.save(context, edited)
            val authoritative = authoritativeFailoverSettings()
            assertEquals("the thrash cap must not read a stale budget", 9, authoritative.maxRotations)
            assertEquals(
                "the re-arm delay and the UNPROTECTED stop deadline are both this window",
                1_800_000L,
                authoritative.rotationWindowMs,
            )
            assertTrue(authoritative.enabled)
        } finally {
            FailoverPreferences.save(context, FailoverPreferences.DEFAULT)
        }
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

    @Test
    fun missingRequiredBridge_abortsTheUncoveredRebuild() {
        // After tearDown the live TUN is gone. If the bridge could not be established, continuing
        // the off-lock rebuild reopens the clear-network window the bridge exists to close.
        // Aborting into give-up (which tries blackhole containment) is the fail-closed answer.
        assertTrue(
            shouldAbortRotationForMissingBridge(bridgeRequired = true, bridgeHeld = false)
        )
        assertFalse(
            "bridge held — continue the rebuild under cover",
            shouldAbortRotationForMissingBridge(bridgeRequired = true, bridgeHeld = true)
        )
        assertFalse(
            "bridge was not required (already held / not ROTATING) — no abort from this rule",
            shouldAbortRotationForMissingBridge(bridgeRequired = false, bridgeHeld = false)
        )
    }
}
