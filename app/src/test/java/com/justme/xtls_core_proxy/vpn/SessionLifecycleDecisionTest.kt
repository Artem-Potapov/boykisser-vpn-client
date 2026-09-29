package com.justme.xtls_core_proxy.vpn

import com.justme.xtls_core_proxy.failover.FailoverPreferences
import com.justme.xtls_core_proxy.log.GiveUpOngoingLine
import com.justme.xtls_core_proxy.log.VpnConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // --- Shared screen receiver (kill-switch + failover) ---

    @Test
    fun screenReceiver_staysRegistered_whenOnlyFailoverIsLive() {
        assertTrue(shouldHoldScreenReceiver(killSwitchLive = false, failoverLive = true))
    }

    @Test
    fun screenReceiver_staysRegistered_whenOnlyKillSwitchIsLive() {
        // Regression guard for the PRE-EXISTING behaviour this refactor must not break.
        assertTrue(shouldHoldScreenReceiver(killSwitchLive = true, failoverLive = false))
    }

    @Test
    fun screenReceiver_staysRegistered_whenBothAreLive() {
        assertTrue(shouldHoldScreenReceiver(killSwitchLive = true, failoverLive = true))
    }

    @Test
    fun screenReceiver_released_whenNeitherIsLive() {
        assertFalse(shouldHoldScreenReceiver(killSwitchLive = false, failoverLive = false))
    }

    // --- Health-monitor run gate ---

    @Test
    fun failoverMonitor_runs_whenConnectedAndEnabled() {
        assertTrue(
            shouldRunFailoverMonitor(
                enabled = true,
                running = true,
                tunnelState = SessionTunnelState.CONNECTED,
            )
        )
    }

    @Test
    fun failoverMonitor_mustNotRun_whenPaused() {
        // No tunnel exists while PAUSED, so every probe would fail and we would "rotate" a
        // tunnel the kill-switch deliberately tore down.
        assertFalse(
            shouldRunFailoverMonitor(
                enabled = true,
                running = true,
                tunnelState = SessionTunnelState.PAUSED,
            )
        )
    }

    @Test
    fun failoverMonitor_mustNotRun_inAnyNonConnectedState() {
        for (state in listOf(
            SessionTunnelState.STARTING,
            SessionTunnelState.REVIVING,
            SessionTunnelState.ROTATING,
            SessionTunnelState.STOPPED,
        )) {
            assertFalse(
                "monitor must not run in $state",
                shouldRunFailoverMonitor(enabled = true, running = true, tunnelState = state)
            )
        }
    }

    @Test
    fun failoverMonitor_mustNotRun_whenDisabledOrNotRunning() {
        assertFalse(
            shouldRunFailoverMonitor(
                enabled = false,
                running = true,
                tunnelState = SessionTunnelState.CONNECTED,
            )
        )
        assertFalse(
            shouldRunFailoverMonitor(
                enabled = true,
                running = false,
                tunnelState = SessionTunnelState.CONNECTED,
            )
        )
    }

    // --- Live timing edits: rebuild the monitor only when its own inputs changed ---

    @Test
    fun monitorRebuild_requiredWhenNothingWasRecorded() {
        assertTrue(failoverMonitorNeedsRebuild(builtFrom = null, next = FailoverPreferences.DEFAULT))
    }

    @Test
    fun monitorRebuild_notRequiredForAnUnchangedEmission() {
        // The settings StateFlow re-emits on every save; an unchanged emission must be a no-op or
        // the poll cycle restarts continuously and the tunnel is never actually observed.
        assertFalse(
            failoverMonitorNeedsRebuild(
                builtFrom = FailoverPreferences.DEFAULT,
                next = FailoverPreferences.DEFAULT.copy(),
            )
        )
    }

    @Test
    fun monitorRebuild_requiredForEveryTimingRelevantField() {
        val base = FailoverPreferences.DEFAULT
        assertTrue(
            "probe interval feeds TunnelHealthMonitor.intervalMs",
            failoverMonitorNeedsRebuild(base, base.copy(probeIntervalMs = base.probeIntervalMs + 1))
        )
        assertTrue(
            "probe timeout feeds Http204HealthProbe.timeoutMs",
            failoverMonitorNeedsRebuild(base, base.copy(probeTimeoutMs = base.probeTimeoutMs + 1))
        )
        assertTrue(
            "failure threshold feeds TunnelHealthMonitor.failureThreshold",
            failoverMonitorNeedsRebuild(base, base.copy(failureThreshold = base.failureThreshold + 1))
        )
    }

    @Test
    fun monitorRebuild_notRequiredForFieldsTheMonitorNeverReads() {
        // enabled is handled by shouldRunFailoverMonitor; maxRotations/rotationWindowMs are read
        // fresh at rotation time. Rebuilding for these would restart the poll cycle for nothing.
        val base = FailoverPreferences.DEFAULT
        assertFalse(failoverMonitorNeedsRebuild(base, base.copy(enabled = !base.enabled)))
        assertFalse(failoverMonitorNeedsRebuild(base, base.copy(maxRotations = base.maxRotations + 1)))
        assertFalse(
            failoverMonitorNeedsRebuild(base, base.copy(rotationWindowMs = base.rotationWindowMs + 1))
        )
    }

    // --- Fail-closed give-up: what does it do to contain traffic, and where does the fd come from?
    //     Inputs are the derived TunnelRole: one owner, one role, so "is a tunnel held" and "is it
    //     still proxying" can no longer be answered by two fields that disagree. ---

    @Test
    fun giveUpWithNothingHeld_buildsAFreshBlackhole() {
        // The all-servers-dead path tears the TUN down first; ending there with no fd would hand
        // the user's traffic back to the clear network at the worst possible moment.
        assertEquals(
            GiveUpContainment.ESTABLISH_BLACKHOLE,
            containmentForGiveUp(
                tunnelRole = TunnelRole.NONE,
                tunnelState = SessionTunnelState.CONNECTED,
            )
        )
    }

    @Test
    fun giveUpHoldingALiveTunnel_establishesNothing() {
        // A second establish() over a live interface would replace the one Xray dials through.
        assertEquals(
            GiveUpContainment.NONE,
            containmentForGiveUp(
                tunnelRole = TunnelRole.LIVE_PROXY,
                tunnelState = SessionTunnelState.CONNECTED,
            )
        )
    }

    @Test
    fun giveUpDuringTheRotationGap_adoptsTheBridgeRatherThanBuildingASecondTun() {
        // The bridge IS already the interface this give-up wants: same builder, no protector, no
        // Xray. Building a second one would strand the bridge fd — a leaked VPN interface.
        assertEquals(
            GiveUpContainment.ADOPT_ROTATION_BRIDGE,
            containmentForGiveUp(
                tunnelRole = TunnelRole.UNREAD_ROTATION_BRIDGE,
                tunnelState = SessionTunnelState.CONNECTED,
            )
        )
    }

    @Test
    fun aHeldUnreadBlackholeIsNeverReplacedByTheBridge() {
        // The adoption arm writes the bridge over the owner. Doing that while a give-up from an
        // EARLIER episode still holds its containment fd would drop a working, drop-only fd on the
        // floor and call the result contained. Both unread roles refuse for the same reason, and
        // the role is what now makes that one fact: there is no "fd present AND a bridge held"
        // input left to reason about.
        assertEquals(
            GiveUpContainment.NONE,
            containmentForGiveUp(
                tunnelRole = TunnelRole.UNREAD_BLACKHOLE,
                tunnelState = SessionTunnelState.CONNECTED,
            )
        )
    }

    @Test
    fun aHeldTunnelWinsOverTheStateCheck_inEveryState() {
        // Precedence, whole-enum. The role guard is evaluated BEFORE the CONNECTED guard, exactly as
        // the hasTunnel-then-state pair it replaces was. Be precise about what that buys: the two
        // held roles answer NONE in every state either way, so this is a legibility/exhaustiveness
        // requirement rather than an observable one — the `when` over the enum must stay total, and
        // a state guard written first would make the held-tunnel arms read as consequences of the
        // state rather than as the independent fact they are. This is the sweep the old suite lacked.
        for (state in SessionTunnelState.entries) {
            for (role in listOf(TunnelRole.LIVE_PROXY, TunnelRole.UNREAD_BLACKHOLE)) {
                assertEquals(
                    "a held $role must win outright in $state",
                    GiveUpContainment.NONE,
                    containmentForGiveUp(tunnelRole = role, tunnelState = state)
                )
            }
        }
    }

    @Test
    fun giveUpWhileAnotherOwnerHoldsTheTunnel_establishesNothing_evenWithABridgeHeld() {
        // PAUSED means "no tunnel must exist" — adopting a bridge into it would break the
        // kill-on-foreground compliance contract just as surely as building a blackhole would.
        // Every role is swept, not just the tunnelled ones: the state guard is what refuses these,
        // and a role added later must not be able to slip past it.
        for (state in SessionTunnelState.entries) {
            if (state == SessionTunnelState.CONNECTED) continue
            for (role in TunnelRole.entries) {
                assertEquals(
                    "another owner drives the tunnel in $state (role: $role)",
                    GiveUpContainment.NONE,
                    containmentForGiveUp(tunnelRole = role, tunnelState = state)
                )
            }
        }
    }

    @Test
    fun bridgeAdoptionHappens_onlyInConnected() {
        // The one role whose answer depends on the state at all. Swept whole-enum so a state
        // added later cannot be mistaken for a second adoption point.
        for (state in SessionTunnelState.entries) {
            assertEquals(
                "a bridge in $state is ${if (state == SessionTunnelState.CONNECTED) "adoptable" else "left to its owner"}",
                if (state == SessionTunnelState.CONNECTED) {
                    GiveUpContainment.ADOPT_ROTATION_BRIDGE
                } else {
                    GiveUpContainment.NONE
                },
                containmentForGiveUp(
                    tunnelRole = TunnelRole.UNREAD_ROTATION_BRIDGE,
                    tunnelState = state,
                )
            )
        }
    }

    @Test
    fun containmentTruthTable_isTotal_overEveryRoleAndState() {
        // Whole-enum, whole-state. This is the anti-drift guard for the precedence rule: the pair
        // of loops above proves each guard fires, this one pins the complete table so a new role
        // or a new state cannot be added without an answer being decided for it here.
        for (role in TunnelRole.entries) {
            for (state in SessionTunnelState.entries) {
                val expected = when {
                    role == TunnelRole.LIVE_PROXY || role == TunnelRole.UNREAD_BLACKHOLE ->
                        GiveUpContainment.NONE
                    state != SessionTunnelState.CONNECTED -> GiveUpContainment.NONE
                    role == TunnelRole.UNREAD_ROTATION_BRIDGE -> GiveUpContainment.ADOPT_ROTATION_BRIDGE
                    else -> GiveUpContainment.ESTABLISH_BLACKHOLE
                }
                assertEquals(
                    "containment for $role in $state",
                    expected,
                    containmentForGiveUp(tunnelRole = role, tunnelState = state)
                )
            }
        }
    }

    @Test
    fun adoptingTheBridgeIsClassifiedAsABlackhole_neverAsALiveTunnel() {
        // Within-one-give-up hazard: the role is read AFTER the containment step, so an adopted
        // bridge reaches classifyGiveUpOutcome as the owner the adoption installed, not as the
        // pre-adaction state. Reading the fd before adoption — or as a bare "an fd exists" — would
        // report CONTAINED_BY_LIVE_TUNNEL over an unread fd.
        assertEquals(
            GiveUpContainment.ADOPT_ROTATION_BRIDGE,
            containmentForGiveUp(
                tunnelRole = TunnelRole.UNREAD_ROTATION_BRIDGE,
                tunnelState = SessionTunnelState.CONNECTED,
            )
        )
        // Adoption cannot fail — it happens under the same lock — so the resulting role is the
        // blackhole owner, and that is what must be classified.
        assertEquals(
            FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE,
            classifyGiveUpOutcome(resultingRole = TunnelRole.UNREAD_BLACKHOLE)
        )
    }

    // --- Give-up outcome: three physically different situations, three different messages ---

    @Test
    fun giveUpOverALiveTunnel_isNotTheSameAsABlackhole() {
        // The no-candidate and thrash-cap give-ups run BEFORE any teardown, so the owner is the
        // current profile's still-proxying tunnel. Telling that user "your traffic is blocked" is
        // simply false.
        assertEquals(
            FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL,
            classifyGiveUpOutcome(resultingRole = TunnelRole.LIVE_PROXY)
        )
    }

    @Test
    fun aLiveTunnelIsNeverOverwrittenByTheContainmentStep() {
        // The "regardless of what the blackhole attempt would have said" half of the old test,
        // restated against the role: when the session still holds a live tunnel, containment
        // establishes nothing, so the role classification reads is necessarily the live one. A
        // containment step that did overwrite it would report a working tunnel as blocked.
        assertEquals(
            GiveUpContainment.NONE,
            containmentForGiveUp(
                tunnelRole = TunnelRole.LIVE_PROXY,
                tunnelState = SessionTunnelState.CONNECTED,
            )
        )
        assertEquals(
            FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL,
            classifyGiveUpOutcome(resultingRole = TunnelRole.LIVE_PROXY)
        )
    }

    @Test
    fun giveUpWithNoTunnel_andASuccessfulBlackhole_isContained() {
        assertEquals(
            GiveUpContainment.ESTABLISH_BLACKHOLE,
            containmentForGiveUp(
                tunnelRole = TunnelRole.NONE,
                tunnelState = SessionTunnelState.CONNECTED,
            )
        )
        assertEquals(
            FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE,
            classifyGiveUpOutcome(resultingRole = TunnelRole.UNREAD_BLACKHOLE)
        )
    }

    @Test
    fun giveUpWithNoTunnel_andAFailedBlackhole_isUnprotected() {
        // The one case where the user is genuinely on the clear network. It must never be reported
        // with the reassuring "traffic is blocked on purpose" copy. A failed establish leaves the
        // session owning nothing, which is the whole reason the outcome is not an input flag: the
        // classification reads the owner the give-up ended up with.
        assertEquals(
            FailoverGiveUpOutcome.UNPROTECTED,
            classifyGiveUpOutcome(resultingRole = TunnelRole.NONE)
        )
    }

    @Test
    fun giveUpOverAnExistingUnreadContainment_staysBlackhole_neverLiveTunnel() {
        // Across-give-ups hazard: after a blackhole (or an adopted bridge) lands in the owner, a
        // second give-up in the same session must not read "fd present" as "still proxying".
        // That lie posts vpn_status_no_response over a drop-only fd.
        assertEquals(
            FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE,
            classifyGiveUpOutcome(resultingRole = TunnelRole.UNREAD_BLACKHOLE)
        )
    }

    @Test
    fun anUnadoptedBridgeIsClassifiedAsABlackhole_asAPacketTruthBackstop() {
        // Defensive mapping, not the live path: the service adopts the bridge into the blackhole
        // owner before classifying, so this arm should be unreachable in production. It is pinned
        // anyway because an unread owned TUN does contain traffic — reporting it as anything other
        // than contained would tell a user on a working blackhole that their traffic is exposed.
        assertEquals(
            FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE,
            classifyGiveUpOutcome(resultingRole = TunnelRole.UNREAD_ROTATION_BRIDGE)
        )
    }

    @Test
    fun everyTunnelRoleIsClassifiedAndTheUnreadOnesShareOneOutcome() {
        // Whole-enum guard, and the reason the map is a when and not a lookup table of "held" vs
        // "not held": the two unread roles are physically the same packet truth and MUST land on
        // one outcome, while the live tunnel must not share it. If a future role were added and
        // folded into either group by accident, this is where it shows.
        val byRole = TunnelRole.entries.associateWith { classifyGiveUpOutcome(resultingRole = it) }
        assertEquals(
            "the two unread roles are both drop-only containment",
            FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE,
            byRole[TunnelRole.UNREAD_ROTATION_BRIDGE]
        )
        assertEquals(
            FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE,
            byRole[TunnelRole.UNREAD_BLACKHOLE]
        )
        assertNotEquals(
            "an unread fd must never be reported as still proxying",
            FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL,
            byRole[TunnelRole.UNREAD_BLACKHOLE]
        )
        assertEquals(
            FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL,
            byRole[TunnelRole.LIVE_PROXY]
        )
        assertEquals(
            FailoverGiveUpOutcome.UNPROTECTED,
            byRole[TunnelRole.NONE]
        )
    }

    @Test
    fun onlyTheUncontainedGiveUpMapsToError() {
        // BLACKHOLED is a live, stoppable state; ERROR is not. Getting this backwards would tell a
        // user with contained traffic they are in an error state, and a user on the clear network
        // that their connection is merely paused.
        assertEquals(VpnConnectionState.ERROR, connectionStateForGiveUp(FailoverGiveUpOutcome.UNPROTECTED))
        assertEquals(
            VpnConnectionState.BLACKHOLED,
            connectionStateForGiveUp(FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE)
        )
        assertEquals(
            VpnConnectionState.BLACKHOLED,
            connectionStateForGiveUp(FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL)
        )
    }

    // --- The recorded give-up line must survive a released give-up ---

    @Test
    fun giveUpLine_neverSharedByTheTwoContainedOutcomes() {
        // They have OPPOSITE packet truths: one is still proxying, the other drops everything.
        // Sharing a line tells a user with a working connection that their traffic is being held,
        // or a user behind a blackhole that their connection is merely unhealthy.
        assertEquals(
            GiveUpOngoingLine.STILL_PROXYING,
            giveUpOngoingLine(FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL)
        )
        assertEquals(
            GiveUpOngoingLine.TRAFFIC_HELD,
            giveUpOngoingLine(FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE)
        )
        assertNotEquals(
            giveUpOngoingLine(FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL),
            giveUpOngoingLine(FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE)
        )
    }

    @Test
    fun giveUpLine_recordsTheUncontainedOutcomeToo_notOnlyTheBlackholedOnes() {
        // It used to be null for UNPROTECTED, on the reasoning that the line was the BLACKHOLED
        // copy and UNPROTECTED renders as ERROR. That left home and the tile with the generic
        // "Error" — the exact string an ordinary failed connection shows — for the one outcome
        // that admits the user is on the clear network. ERROR alone cannot tell the two apart, so
        // the outcome has to reach the UI, and this recorded marker is the mechanism that already
        // does it for the other two.
        assertEquals(
            GiveUpOngoingLine.UNPROTECTED,
            giveUpOngoingLine(FailoverGiveUpOutcome.UNPROTECTED)
        )
    }

    @Test
    fun giveUpLine_existsForEveryOutcome_andNoTwoOutcomesShareOne() {
        // Whole-enum guard: every give-up outcome must reach the UI as its own line, or two
        // outcomes with different packet truths end up behind one string. Collecting into a set
        // keeps it non-vacuous — a mapping that collapsed two outcomes would shrink the set.
        val lines = FailoverGiveUpOutcome.entries.map { outcome ->
            val line = giveUpOngoingLine(outcome)
            assertNotNull("$outcome must record a line for home/tile/1101", line)
            line
        }
        assertEquals(
            "each outcome must own a distinct line: $lines",
            FailoverGiveUpOutcome.entries.size,
            lines.toSet().size,
        )
    }

    @Test
    fun giveUpLine_isAbsentWhenNoGiveUpProducedTheState() {
        // The service holds this as a nullable field, so null must mean "no give-up describes this
        // session" rather than silently selecting one.
        assertNull(giveUpOngoingLine(null))
    }

    // --- "Disconnect now, stop if the re-arm fails": exactly one automatic recovery attempt ---

    @Test
    fun firstUnprotectedGiveUp_reArmsRatherThanStopping() {
        // Forfeiting the re-arm would switch the VPN off without the user asking for it.
        assertFalse(
            shouldStopServiceOnGiveUp(
                outcome = FailoverGiveUpOutcome.UNPROTECTED,
                unprotectedRetryConsumed = false,
            )
        )
    }

    @Test
    fun secondUnprotectedGiveUp_stopsTheService() {
        // The one recovery attempt has been spent and traffic is STILL not contained; the honest
        // off state beats an indefinite "running but unprotected" one.
        assertTrue(
            shouldStopServiceOnGiveUp(
                outcome = FailoverGiveUpOutcome.UNPROTECTED,
                unprotectedRetryConsumed = true,
            )
        )
    }

    @Test
    fun containedGiveUps_neverStopTheService_evenAfterARetry() {
        // Traffic is contained in both, so there is nothing to be honest about turning off.
        for (outcome in listOf(
            FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE,
            FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL,
        )) {
            assertFalse(
                "$outcome is contained and must keep its existing re-arm behaviour",
                shouldStopServiceOnGiveUp(outcome, unprotectedRetryConsumed = false)
            )
            assertFalse(
                "$outcome must not inherit the stop from a spent unprotected retry",
                shouldStopServiceOnGiveUp(outcome, unprotectedRetryConsumed = true)
            )
        }
    }

    // --- The single automatic recovery is spent by an ATTEMPT, never by scheduling one ---

    @Test
    fun unprotectedRetry_attemptsFromTheOneStateARotationCanReserve() {
        // CONNECTED is exactly canReserveRotation's requirement, so this is the only state in
        // which dispatching a rotation can actually do anything.
        assertEquals(
            UnprotectedRetryAction.ATTEMPT,
            unprotectedRetryAction(
                tunnelState = SessionTunnelState.CONNECTED,
                unprotectedSinceMs = 0L,
                now = 1_000L,
                rotationWindowMs = 600_000L,
            )
        )
    }

    @Test
    fun unprotectedRetry_thatCannotBeAttempted_isDeferredNotForfeited() {
        // THE defect this rule exists for. The retry used to be marked consumed at SCHEDULE time,
        // so a kill-switch pause landing before the timer fired spent it with no attempt at all:
        // rotateTunnel bailed at canReserveRotation, no second give-up ever happened, and
        // shouldStopServiceOnGiveUp could therefore never fire. The service then ran with NO TUN
        // until the user intervened.
        for (state in SessionTunnelState.entries) {
            if (state == SessionTunnelState.CONNECTED) continue
            assertEquals(
                "a retry that cannot be attempted in $state must be re-armed, not forfeited",
                UnprotectedRetryAction.DEFER,
                unprotectedRetryAction(
                    tunnelState = state,
                    unprotectedSinceMs = 0L,
                    now = 600_000L,
                    rotationWindowMs = 600_000L,
                )
            )
        }
    }

    @Test
    fun unprotectedRetry_stopsTheServiceOnceDeferringHasRunOutOfTime() {
        // The other half of the bound, and the reason DEFER is safe: re-arming cannot go on
        // forever. A service that is running, owns no TUN and cannot even attempt a recovery must
        // land in an honest OFF state rather than persist indefinitely while protecting nothing.
        for (state in SessionTunnelState.entries) {
            if (state == SessionTunnelState.CONNECTED) continue
            assertEquals(
                "deferring in $state must terminate, not repeat forever",
                UnprotectedRetryAction.STOP_SERVICE,
                unprotectedRetryAction(
                    tunnelState = state,
                    unprotectedSinceMs = 0L,
                    now = 10_000_000L,
                    rotationWindowMs = 600_000L,
                )
            )
        }
    }

    @Test
    fun unprotectedRetry_deferralDeadlineIsExactAndScalesWithTheWindow() {
        // The deadline is expressed in rotation windows, so a user who asked for hour-long windows
        // is not stopped before their first window has even elapsed. `now` is a parameter, so this
        // is checked without waiting on a clock.
        val window = 600_000L
        val deadline = window * UNPROTECTED_UNATTEMPTED_RETRY_WINDOWS
        assertEquals(
            "one millisecond inside the deadline still re-arms",
            UnprotectedRetryAction.DEFER,
            unprotectedRetryAction(
                tunnelState = SessionTunnelState.PAUSED,
                unprotectedSinceMs = 0L,
                now = deadline - 1,
                rotationWindowMs = window,
            )
        )
        assertEquals(
            "reaching the deadline stops the service",
            UnprotectedRetryAction.STOP_SERVICE,
            unprotectedRetryAction(
                tunnelState = SessionTunnelState.PAUSED,
                unprotectedSinceMs = 0L,
                now = deadline,
                rotationWindowMs = window,
            )
        )
    }

    @Test
    fun unprotectedRetry_stillAttemptsWhenTheSessionRecoversAfterTheDeadline() {
        // Being able to act beats the deadline: the attempt is itself a terminating path (it either
        // restores a tunnel or produces the second give-up that stops the service), so stopping
        // instead would throw away the recovery the user is owed.
        assertEquals(
            UnprotectedRetryAction.ATTEMPT,
            unprotectedRetryAction(
                tunnelState = SessionTunnelState.CONNECTED,
                unprotectedSinceMs = 0L,
                now = 10_000_000L,
                rotationWindowMs = 600_000L,
            )
        )
    }

    @Test
    fun retryTimer_handoffIsPublishedOnlyForCurrentAttempt() {
        assertTrue(
            shouldPublishUnprotectedRecoveryHandoff(
                action = UnprotectedRetryAction.ATTEMPT,
                isCurrentSession = true,
            )
        )
        assertFalse(
            "a stale timer must not publish a recovery handoff marker",
            shouldPublishUnprotectedRecoveryHandoff(
                action = UnprotectedRetryAction.ATTEMPT,
                isCurrentSession = false,
            )
        )
        assertFalse(
            "deferral and stop actions do not queue a rotation",
            shouldPublishUnprotectedRecoveryHandoff(
                action = UnprotectedRetryAction.DEFER,
                isCurrentSession = true,
            )
        )
    }

    @Test
    fun retryTimer_stopEffectsRequireCurrentSessionAtExecution() {
        assertTrue(
            shouldExecuteUnprotectedStop(
                action = UnprotectedRetryAction.STOP_SERVICE,
                isCurrentSession = true,
            )
        )
        assertFalse(
            "a stale STOP_SERVICE timer must not emit effects into a replacement session",
            shouldExecuteUnprotectedStop(
                action = UnprotectedRetryAction.STOP_SERVICE,
                isCurrentSession = false,
            )
        )
        assertFalse(
            shouldExecuteUnprotectedStop(
                action = UnprotectedRetryAction.ATTEMPT,
                isCurrentSession = true,
            )
        )
    }

    // --- A scheduled retry must not outlive the setting that authorised it ---

    @Test
    fun retryTimer_firesOnlyWhileFailoverIsStillEnabled() {
        assertTrue(shouldFireFailoverRetry(failoverEnabled = true, isCurrentSession = true))
        assertFalse(
            "disabling failover must veto an already-scheduled retry, or a user who turned the " +
                "feature off still gets an automatic rotation — and a possible VPN shutdown",
            shouldFireFailoverRetry(failoverEnabled = false, isCurrentSession = true)
        )
    }

    @Test
    fun retryTimer_doesNotFireForASupersededSession() {
        assertFalse(shouldFireFailoverRetry(failoverEnabled = true, isCurrentSession = false))
        assertFalse(shouldFireFailoverRetry(failoverEnabled = false, isCurrentSession = false))
    }

    @Test
    fun reEnableDuringUnprotected_restoresTheRecoveryRearm() {
        // Disable cancels failoverRearmJob — the only automatic recovery/stop for a no-TUN session.
        // Re-enable must reschedule it; otherwise the monitor probes the clear network, never
        // rotates, and the service runs forever while protecting nothing.
        assertTrue(
            shouldRestoreUnprotectedRearm(
                failoverEnabled = true,
                giveUpOutcome = FailoverGiveUpOutcome.UNPROTECTED,
                tunnelRole = TunnelRole.NONE,
                rearmJobActive = false,
            )
        )
        assertFalse(
            "still disabled — disable half correctly leaves no re-arm",
            shouldRestoreUnprotectedRearm(
                failoverEnabled = false,
                giveUpOutcome = FailoverGiveUpOutcome.UNPROTECTED,
                tunnelRole = TunnelRole.NONE,
                rearmJobActive = false,
            )
        )
        assertFalse(
            "re-arm already alive — do not stack a second timer",
            shouldRestoreUnprotectedRearm(
                failoverEnabled = true,
                giveUpOutcome = FailoverGiveUpOutcome.UNPROTECTED,
                tunnelRole = TunnelRole.NONE,
                rearmJobActive = true,
            )
        )
        assertFalse(
            "contained give-ups are not the UNPROTECTED recovery path",
            shouldRestoreUnprotectedRearm(
                failoverEnabled = true,
                giveUpOutcome = FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE,
                tunnelRole = TunnelRole.UNREAD_BLACKHOLE,
                rearmJobActive = false,
            )
        )
        assertFalse(
            "a live tunnel means clearGiveUpStateOnRecovery can clear — not this restore",
            shouldRestoreUnprotectedRearm(
                failoverEnabled = true,
                giveUpOutcome = FailoverGiveUpOutcome.UNPROTECTED,
                tunnelRole = TunnelRole.LIVE_PROXY,
                rearmJobActive = false,
            )
        )
    }

    @Test
    fun theRearmRestoreHappens_onlyForTheUnprotectedGiveUpHoldingNothing() {
        // Whole-enum over the give-up marker and the tunnel role, so the two dimensions this rule
        // gates on are both swept rather than spot-checked. Only UNPROTECTED + nothing owned may
        // restore: every other pairing either is not this recovery path, or has containment already.
        for (outcome in listOf<FailoverGiveUpOutcome?>(null) + FailoverGiveUpOutcome.entries) {
            for (role in TunnelRole.entries) {
                val expected = outcome == FailoverGiveUpOutcome.UNPROTECTED && role == TunnelRole.NONE
                assertEquals(
                    "rearm restore for outcome=$outcome role=$role",
                    expected,
                    shouldRestoreUnprotectedRearm(
                        failoverEnabled = true,
                        giveUpOutcome = outcome,
                        tunnelRole = role,
                        rearmJobActive = false,
                    )
                )
            }
        }
    }

    @Test
    fun aHeldRotationBridgeIsNotAnUnprotectedRestorePoint() {
        // The one input the role and the old boolean could read differently. An owned unread bridge
        // IS containment, exactly as a blackhole is, so the session is not "owning nothing" and the
        // UNPROTECTED recovery is not owed. Pre-owner this read as a restore because the bridge
        // lived in a sibling field the boolean never asked about.
        assertFalse(
            shouldRestoreUnprotectedRearm(
                failoverEnabled = true,
                giveUpOutcome = FailoverGiveUpOutcome.UNPROTECTED,
                tunnelRole = TunnelRole.UNREAD_ROTATION_BRIDGE,
                rearmJobActive = false,
            )
        )
    }

    @Test
    fun reEnableDuringUnprotected_doesNotRestoreWhileRecoveryRotationIsQueued() {
        assertFalse(
            shouldRestoreUnprotectedRearm(
                failoverEnabled = true,
                giveUpOutcome = FailoverGiveUpOutcome.UNPROTECTED,
                tunnelRole = TunnelRole.NONE,
                rearmJobActive = false,
                recoveryRotationQueued = true,
            )
        )
    }

    @Test
    fun reEnableDuringUnprotected_restoresWhenNoRecoveryRotationIsQueued() {
        assertTrue(
            shouldRestoreUnprotectedRearm(
                failoverEnabled = true,
                giveUpOutcome = FailoverGiveUpOutcome.UNPROTECTED,
                tunnelRole = TunnelRole.NONE,
                rearmJobActive = false,
                recoveryRotationQueued = false,
            )
        )
    }

    // --- Connect-from-UNPROTECTED must actually recover, not no-op ---

    @Test
    fun startWhileUnprotected_restartsInsteadOfNoOpping() {
        // connectAction(ERROR) == CONNECT enables the per-profile Connect buttons, but
        // ACTION_START hits startVpn's "VPN already running" early return. The copy tells the user
        // to turn the VPN off and on again or choose another server, so the control has to do
        // exactly that.
        assertTrue(
            shouldRestartForRecovery(
                running = true,
                giveUpOutcome = FailoverGiveUpOutcome.UNPROTECTED,
            )
        )
    }

    @Test
    fun startWhileHealthy_keepsTheIdempotentEarlyReturn() {
        // Deliberately NOT a general "start while running = restart". The tile,
        // START_REDELIVER_INTENT recovery and stray/duplicated intents all rely on that early
        // return being idempotent; a general restart would let a stray intent bounce a live tunnel.
        assertFalse(shouldRestartForRecovery(running = true, giveUpOutcome = null))
    }

    @Test
    fun startWhileContained_doesNotRestart() {
        // Both are excluded, for DIFFERENT reasons — do not collapse them into the shared, weaker
        // one, which is the rationale a maintainer would feel safe widening on:
        //   * CONTAINED_BY_LIVE_TUNNEL holds a RUNNING Xray core, and this predicate unlocks a
        //     stopVpn on the MAIN THREAD, where that becomes a real instance.Close(). That is
        //     RISK-1, and it is the reason this must never widen.
        //   * CONTAINED_BY_BLACKHOLE holds no running core (hadTunnel == false, so stopXray()
        //     already ran); it is excluded because it still holds a TUN, so a restart has nothing
        //     to rescue.
        for (outcome in listOf(
            FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE,
            FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL,
        )) {
            assertFalse(
                "$outcome is contained; a restart would bounce a tunnel that is doing its job",
                shouldRestartForRecovery(running = true, giveUpOutcome = outcome)
            )
        }
    }

    @Test
    fun startWhileStopped_isANormalStart_notARestart() {
        // A dying-session ERROR has already stopped the service, so running is false and the plain
        // start path applies — this predicate must not claim that one.
        assertFalse(
            shouldRestartForRecovery(
                running = false,
                giveUpOutcome = FailoverGiveUpOutcome.UNPROTECTED,
            )
        )
    }

    // --- A refused start must not leave the app claiming the server it refused ---

    @Test
    fun refusedStart_restoresTheProfileTheTunnelIsActuallyOn() {
        // Every connect path writes ActiveProfileRepository BEFORE dispatching ACTION_START. When
        // startVpn refuses that start, traffic keeps flowing through the running profile while the
        // UI/QS tile would keep labelling the requested one as connected.
        assertEquals(
            7L,
            activeProfileIdToRestoreOnRefusedStart(requestedProfileId = 12L, currentProfileId = 7L)
        )
    }

    @Test
    fun refusedStartOfTheRunningProfile_restoresNothing() {
        // The eager write already stored exactly what is running; rewriting it would churn
        // SharedPreferences and re-emit to every observer for no change.
        assertNull(
            activeProfileIdToRestoreOnRefusedStart(requestedProfileId = 7L, currentProfileId = 7L)
        )
    }

    @Test
    fun refusedStartWithNoSessionProfile_restoresNothing() {
        // currentProfileId is a plain Long carrying -1L when unset (stopVpn resets it), and Room
        // never issues a non-positive profile id. Writing one back would point the UI at nothing.
        for (unset in listOf(-1L, 0L)) {
            assertNull(
                "id $unset is not a real profile; restoring it would write garbage",
                activeProfileIdToRestoreOnRefusedStart(
                    requestedProfileId = 12L,
                    currentProfileId = unset,
                )
            )
        }
    }

    // --- Disabling auto-failover must release a give-up it left behind ---

    @Test
    fun disablingFailoverDuringAContainedGiveUpMustReleaseIt() {
        // Turning the feature off cancels the re-arm timer, which is the only automatic recovery
        // from a contained give-up. Without releasing the state the user is stranded behind a
        // blackhole TUN by the very act of switching the feature off.
        assertTrue(shouldReleaseGiveUpOnDisable(false, FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE))
        assertTrue(shouldReleaseGiveUpOnDisable(false, FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL))
    }

    @Test
    fun disablingFailoverDuringAnUnprotectedGiveUpMustNotReleaseIt() {
        // DO NOT "fix" this back to assertTrue. UNPROTECTED is the one outcome whose recovery is
        // NOT the re-arm timer — it is the startVpn restart that shouldRestartForRecovery unlocks,
        // and that predicate keys off exactly this marker. Releasing it here leaves the service
        // RUNNING with no TUN on the clear network while every Connect surface takes startVpn's
        // "VPN already running" early return, and with the monitor gone no second give-up can ever
        // fire shouldStopServiceOnGiveUp either. The user would be stranded, unprotected, with only
        // Disconnect working — as a direct result of switching the feature off. There is no re-arm
        // to strand them behind: the two CONTAINED outcomes hold a TUN and depend on the timer,
        // this one holds nothing and depends on the restart.
        assertFalse(shouldReleaseGiveUpOnDisable(false, FailoverGiveUpOutcome.UNPROTECTED))
    }

    @Test
    fun leavingFailoverEnabledNeverReleasesAGiveUp() {
        // While enabled, clearGiveUpStateOnRecovery and the re-arm own this transition.
        assertFalse(shouldReleaseGiveUpOnDisable(true, FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE))
    }

    @Test
    fun disablingWithNoGiveUpShowingIsANoOp() {
        assertFalse(shouldReleaseGiveUpOnDisable(false, null))
    }

    // --- A parked manual connect must outrank a later automatic winner ---

    @Test
    fun aParkedManualConnectIsNotOverwrittenByALaterAutomaticWinner() {
        // pendingProfileId is one slot serving the manual Connect lambda and connect-fastest's
        // winner delivery. A manual tap on 7 sitting behind a permission dialog must survive a
        // winner arriving minutes later, or the app connects to a server the user never chose.
        assertFalse(shouldOverwritePendingConnect(pending = 7L, incoming = 42L))
    }

    @Test
    fun anEmptySlotAlwaysAcceptsTheIncomingConnect() {
        assertTrue(shouldOverwritePendingConnect(pending = -1L, incoming = 42L))
    }

    @Test
    fun reTappingTheSameProfileIsNotAConflict() {
        assertTrue(shouldOverwritePendingConnect(pending = 7L, incoming = 7L))
    }
}
