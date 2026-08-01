package com.justme.xtls_core_proxy.vpn

import com.justme.xtls_core_proxy.failover.FailoverPreferences
import org.junit.Assert.assertEquals
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

    // --- Fail-closed give-up: when may a blackhole TUN be established? ---

    @Test
    fun blackhole_isEstablished_whenAConnectedSessionLostItsTunnel() {
        // The all-servers-dead path tears the TUN down first; ending there with no fd would hand
        // the user's traffic back to the clear network at the worst possible moment.
        assertTrue(
            shouldEstablishBlackholeTunnel(
                hasTunnel = false,
                tunnelState = SessionTunnelState.CONNECTED,
            )
        )
    }

    @Test
    fun blackhole_isNotEstablished_whenATunnelAlreadyExists() {
        assertFalse(
            shouldEstablishBlackholeTunnel(
                hasTunnel = true,
                tunnelState = SessionTunnelState.CONNECTED,
            )
        )
    }

    @Test
    fun blackhole_isNotEstablished_whileTheKillSwitchHoldsTheTunnelDown() {
        // PAUSED means "no tunnel must exist" — establishing one here would break the
        // kill-on-foreground compliance contract outright.
        assertFalse(
            shouldEstablishBlackholeTunnel(
                hasTunnel = false,
                tunnelState = SessionTunnelState.PAUSED,
            )
        )
    }

    // --- Give-up outcome: three physically different situations, three different messages ---

    @Test
    fun giveUpOverALiveTunnel_isNotTheSameAsABlackhole() {
        // The no-candidate and thrash-cap give-ups run BEFORE any teardown, so the fd is the
        // current profile's still-proxying tunnel. Telling that user "your traffic is blocked" is
        // simply false.
        assertEquals(
            FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL,
            classifyGiveUpOutcome(hadTunnel = true, blackholeEstablished = false)
        )
        assertEquals(
            "a live tunnel wins regardless of what the blackhole attempt would have said",
            FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL,
            classifyGiveUpOutcome(hadTunnel = true, blackholeEstablished = true)
        )
    }

    @Test
    fun giveUpWithNoTunnel_andASuccessfulBlackhole_isContained() {
        assertEquals(
            FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE,
            classifyGiveUpOutcome(hadTunnel = false, blackholeEstablished = true)
        )
    }

    @Test
    fun giveUpWithNoTunnel_andAFailedBlackhole_isUnprotected() {
        // The one case where the user is genuinely on the clear network. It must never be reported
        // with the reassuring "traffic is blocked on purpose" copy.
        assertEquals(
            FailoverGiveUpOutcome.UNPROTECTED,
            classifyGiveUpOutcome(hadTunnel = false, blackholeEstablished = false)
        )
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

    // --- Connect-from-UNPROTECTED must actually recover, not no-op ---

    @Test
    fun startWhileUnprotected_restartsInsteadOfNoOpping() {
        // canConnect(ERROR) enables the per-profile Connect buttons, but ACTION_START hits
        // startVpn's "VPN already running" early return. The copy tells the user to turn the VPN
        // off and on again or choose another server, so the control has to do exactly that.
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
        // Both contained give-ups still hold a TUN, so traffic is not leaking and there is nothing
        // for a restart to rescue.
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

    @Test
    fun blackhole_isNotEstablished_inAnyOtherState() {
        for (state in listOf(
            SessionTunnelState.STARTING,
            SessionTunnelState.REVIVING,
            SessionTunnelState.ROTATING,
            SessionTunnelState.STOPPED,
        )) {
            assertFalse(
                "another owner drives the tunnel in $state",
                shouldEstablishBlackholeTunnel(hasTunnel = false, tunnelState = state)
            )
        }
    }
}
