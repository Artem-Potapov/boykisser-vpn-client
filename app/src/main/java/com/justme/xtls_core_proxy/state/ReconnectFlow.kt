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
 *    contender is refused and reported — see [reconnectingProfileId].
 *
 * A third override is **not** closed here and is documented on [cancel]: a stop dispatched from the
 * QS tile or the ongoing notification is indistinguishable, from this class's vantage point, from
 * its own.
 */
internal class ReconnectFlow(
    private val connectionState: StateFlow<VpnConnectionState>,
    private val stop: () -> Unit,
    private val start: (Long) -> Unit,
    private val onTimeout: () -> Unit,
    private val onSuperseded: () -> Unit,
    private val dispatcher: CoroutineDispatcher,
) {
    private val _reconnectingProfileId = MutableStateFlow<Long?>(null)

    /**
     * The profile a reconnect is currently sequencing, or `null` when none is. Set from the moment
     * a reconnect is admitted until its sequence ends.
     *
     * It publishes the **target id** rather than a bare flag because the UI needs both halves and
     * they are not the same set of rows: only the target's control should read "Connecting…", while
     * EVERY control must be disabled, since a contending request would be refused anyway. A Boolean
     * made thirty unrelated servers claim to be connecting.
     */
    val reconnectingProfileId: StateFlow<Long?> = _reconnectingProfileId.asStateFlow()

    private var job: Job? = null

    // Identifies which sequence a coroutine's cleanup belongs to, so a cancelled job that has not
    // been resumed yet cannot clear a slot its SUCCESSOR already owns — the UI would stop showing a
    // reconnect that is still in flight, and a third request would be admitted alongside it.
    // A captured Int rather than a job-identity comparison, for the reason FastestConnectRunner's
    // `generation` documents: under a Dispatchers.Main.immediate scope a coroutine's body (and its
    // finally) can run synchronously inside `launch`, before the field assignments around it have
    // happened, whereas a captured Int is fully assigned before the coroutine literal is created.
    private var generation = 0

    /**
     * Abandons a reconnect in flight without starting anything, and releases the guard so a later
     * one is admitted.
     *
     * **Scope — read this before assuming it is the whole fix.** This covers the **in-app Disconnect
     * only**, because that is the one stop this class can be TOLD about. The flow's settle signal is
     * `LogRepository.connectionState`, which is source-blind: it cannot tell whose stop it just
     * observed. So the **QS tile** (`XrayVpnTileService`) and the **ongoing notification's Stop
     * action** (`XrayVpnService`) both still override a reconnect in flight — they dispatch
     * `ACTION_STOP` straight to the service, the state reaches `DISCONNECTED`, and this flow reads
     * that as its own teardown completing and starts the VPN back up. That is reachable whenever
     * `MainActivity` is merely backgrounded, since the ViewModel and this flow are still alive.
     *
     * Closing it completely needs the service to publish a *stop was requested* signal the flow can
     * distinguish from its own, which means changing human-review-gated `vpn/`. Recorded for the
     * maintainer rather than half-done here.
     */
    fun cancel() {
        job?.cancel()
        job = null
        // Cleared here rather than left to the cancelled job's own cleanup, which a posting
        // dispatcher may not have run yet — the guard must be released by the time this returns,
        // since the caller's very next action can be another reconnect. That cleanup is separately
        // stopped from clobbering a successor's slot by [generation].
        _reconnectingProfileId.value = null
    }

    /**
     * Dispatches the stop, waits up to [STOP_TIMEOUT_MS] for the session to reach
     * [VpnConnectionState.DISCONNECTED], dispatches the start for [profileId], then verifies it
     * took.
     *
     * Returns `null` when a reconnect is already in flight ([reconnectingProfileId] is non-null) —
     * the request is refused, reported via `onSuperseded`, and never queued. **First request
     * wins**, matching the rule this branch already settled for the parked-connect slot: the
     * earlier choice is honoured and the later one is reported rather than silently dropped.
     */
    fun run(profileId: Long, scope: CoroutineScope): Job? {
        if (_reconnectingProfileId.value != null) {
            onSuperseded()
            return null
        }
        val myGeneration = ++generation
        // Armed synchronously, before the coroutine is even launched, so a second call on the same
        // (main) thread is refused no matter how the dispatcher schedules the body.
        _reconnectingProfileId.value = profileId
        val newJob = scope.launch(dispatcher) {
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
                // Also runs on cancellation (the ViewModel being cleared, or [cancel]), so the
                // guard can never wedge shut and make Reconnect work exactly once per ViewModel.
                // Skipped when a later run already owns the slot — see [generation].
                if (generation == myGeneration) _reconnectingProfileId.value = null
            }
        }
        job = newJob
        return newJob
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
