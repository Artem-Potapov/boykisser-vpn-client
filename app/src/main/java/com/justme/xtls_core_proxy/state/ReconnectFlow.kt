package com.justme.xtls_core_proxy.state

import com.justme.xtls_core_proxy.log.VpnConnectionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stop-then-start sequencing for Reconnect — **the canonical home for why Reconnect is shaped this
 * way.** `VpnViewModel.reconnect` and `MainActivity`'s connect choke point point here rather than
 * restating it, so the rationale cannot drift into three independently-edited copies.
 *
 * Kept framework-free so it is JVM-testable, the same shape `FastestConnectRunner` uses for the
 * other half of connect orchestration. `ReconnectFlowTest` drives every rule below.
 *
 * ### Why not `startVpn`'s in-service restart
 * A give-up leaves the service RUNNING, so a plain `connect()` takes `startVpn`'s "VPN already
 * running" early return and does nothing. The obvious fix — widening `shouldRestartForRecovery` to
 * cover the contained outcomes — is the one thing that must NOT happen: `CONTAINED_BY_LIVE_TUNNEL`
 * has a **running Xray core**, and that path calls `stopVpn` on the **main thread**, where
 * `stopXray()` would become a real `instance.Close()`. That is the RISK-1 hazard documented in
 * `docs/features/auto-failover.md`, cleared only because `UNPROTECTED` implies an already-stopped
 * core. `ACTION_STOP` already marshals onto the service's `tunnelOpScope`
 * (`Dispatchers.IO.limitedParallelism(1)`), so stop → settle → start keeps every blocking call off
 * the main thread and needs no change to `stopVpn` at all.
 *
 * ### Which give-up outcomes arrive here
 * Both CONTAINED ones, sharing this single path: `CONTAINED_BY_LIVE_TUNNEL` and
 * `CONTAINED_BY_BLACKHOLE` both surface as [VpnConnectionState.BLACKHOLED], and the blackhole case
 * deliberately gets NO separate "faster" path on the grounds that its core is already stopped —
 * two restart paths would be one rule in two homes, the shape behind most of this feature's
 * defects. `UNPROTECTED` does **not** arrive here: it surfaces as `ERROR`, which [connectAction]
 * maps to a plain CONNECT (see `ConnectActionTest` — "reconnect" would overstate what is left when
 * the core could not establish at all), and its recovery stays `startVpn`'s existing
 * `shouldRestartForRecovery` restart, safe there precisely because that outcome implies a stopped
 * core.
 *
 * ### The two races this survives
 * Both fail silently to "VPN off", and both are millisecond-scale, so neither would show up
 * reliably in manual testing:
 * 1. **The service's own destruction window.** `stopVpn` publishes `DISCONNECTED` about a dozen
 *    lines before it calls `stopSelf()`. A start dispatched the instant that state lands can reach
 *    AMS inside that window: `onStartCommand` runs, and the pending `stopSelf()` then tears the new
 *    session down along with the old one. So the start is **verified**, not assumed — see
 *    [START_VERIFY_MS].
 * 2. **A contending second tap.** The teardown window can last seconds while the state is still
 *    `BLACKHOLED`, so the affordance still renders and a re-tap is the natural user response. A
 *    second `stop()` landing after the first flow's start has set `running = true` takes the FULL
 *    teardown and kills the session the user just asked for. So the first request **wins** and the
 *    contender is refused and reported — see [inFlight].
 */
internal class ReconnectFlow(
    private val connectionState: StateFlow<VpnConnectionState>,
    private val stop: () -> Unit,
    private val start: (Long) -> Unit,
    private val onTimeout: () -> Unit,
    private val onSuperseded: () -> Unit,
    private val dispatcher: CoroutineDispatcher,
) {
    private val _inFlight = MutableStateFlow(false)

    /**
     * True from the moment a reconnect is admitted until its sequence ends. Two jobs: it refuses a
     * contending request in [run], and it lets the UI render the affordance as busy so there is no
     * reason to re-tap a button that legitimately takes seconds to act.
     */
    val inFlight: StateFlow<Boolean> = _inFlight.asStateFlow()

    /**
     * Dispatches the stop, waits up to [STOP_TIMEOUT_MS] for the session to reach
     * [VpnConnectionState.DISCONNECTED], dispatches the start for [profileId], then verifies it
     * took.
     *
     * Returns `null` when a reconnect is already [inFlight] — the request is refused, reported via
     * `onSuperseded`, and never queued. **First request wins**, matching the rule this branch
     * already settled for the parked-connect slot: the earlier choice is honoured and the later one
     * is reported rather than silently dropped.
     */
    fun run(profileId: Long, scope: CoroutineScope): Job? {
        if (_inFlight.value) {
            onSuperseded()
            return null
        }
        // Armed synchronously, before the coroutine is even launched, so a second call on the same
        // (main) thread is refused no matter how the dispatcher schedules the body.
        _inFlight.value = true
        return scope.launch(dispatcher) {
            try {
                stop()
                val settled = withTimeoutOrNull(STOP_TIMEOUT_MS) {
                    connectionState.first { it == VpnConnectionState.DISCONNECTED }
                }
                if (settled == null) {
                    // Never dispatch a start we could not sequence: it would hit "VPN already
                    // running" and leave ActiveProfileRepository naming a server the tunnel never
                    // carried.
                    onTimeout()
                    return@launch
                }
                start(profileId)
                // See "The two races this survives" (1). If the state never leaves DISCONNECTED the
                // start was swallowed; by then the service really is gone, so one re-dispatch lands
                // on a fresh instance. Bounded at ONE: a start that fails for a real reason (no
                // profile, permission revoked) fails the same way twice and must then stop. A
                // redundant second start is harmless — startVpn refuses it with "VPN already
                // running", and activeProfileIdToRestoreOnRefusedStart returns null for equal ids,
                // so it writes nothing.
                val started = withTimeoutOrNull(START_VERIFY_MS) {
                    connectionState.first { it != VpnConnectionState.DISCONNECTED }
                }
                if (started == null) start(profileId)
            } finally {
                // Also runs on cancellation (the ViewModel being cleared), so the guard can never
                // wedge shut and make Reconnect work exactly once per process.
                _inFlight.value = false
            }
        }
    }

    companion object {
        /** Generous relative to a teardown, short enough that a wedged stop still surfaces. */
        const val STOP_TIMEOUT_MS = 8_000L

        /**
         * How long to wait for the dispatched start to announce itself before assuming the service
         * swallowed it. `startVpn` publishes `CONNECTING` almost immediately, so this is generous
         * rather than tuned.
         */
        const val START_VERIFY_MS = 2_000L
    }
}
