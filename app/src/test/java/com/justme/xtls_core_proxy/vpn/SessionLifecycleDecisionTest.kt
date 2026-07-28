package com.justme.xtls_core_proxy.vpn

import com.justme.xtls_core_proxy.failover.FailoverPreferences
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
