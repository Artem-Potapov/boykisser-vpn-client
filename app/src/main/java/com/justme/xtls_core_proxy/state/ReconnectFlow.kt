package com.justme.xtls_core_proxy.state

import com.justme.xtls_core_proxy.log.VpnConnectionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stop-then-start sequencing for Reconnect, kept framework-free so it is JVM-testable — the same
 * shape `FastestConnectRunner` uses for the other half of connect orchestration.
 *
 * Deliberately NOT routed through `XrayVpnService.startVpn`'s in-service restart
 * (`shouldRestartForRecovery`): `CONTAINED_BY_LIVE_TUNNEL` has a RUNNING Xray core, and that path
 * calls `stopVpn` on the main thread, where `stopXray()` would become a real `instance.Close()` —
 * the RISK-1 hazard that was cleared only because `UNPROTECTED` implies an already-stopped core.
 * `ACTION_STOP` already marshals onto the service's `tunnelOpScope`, so dispatching stop, waiting
 * for the session to settle, then dispatching start keeps every blocking call off the main thread
 * and needs no change to `stopVpn` at all.
 *
 * Both CONTAINED give-up outcomes share this one mechanism on purpose — the blackhole case gets no
 * separate "faster" path on the grounds that its core is already stopped, because two restart paths
 * would be one rule in two homes, the shape behind most of this feature's defects. (`UNPROTECTED`
 * surfaces as `ERROR`, not `BLACKHOLED`, and keeps the in-service restart; see
 * `VpnViewModel.reconnect`'s KDoc.)
 */
internal class ReconnectFlow(
    private val connectionState: StateFlow<VpnConnectionState>,
    private val stop: () -> Unit,
    private val start: (Long) -> Unit,
    private val onTimeout: () -> Unit,
    private val dispatcher: CoroutineDispatcher,
) {
    /**
     * Dispatches the stop, waits up to [STOP_TIMEOUT_MS] for the session to reach
     * [VpnConnectionState.DISCONNECTED], then dispatches the start for [profileId].
     */
    fun run(profileId: Long, scope: CoroutineScope): Job = scope.launch(dispatcher) {
        stop()
        val settled = withTimeoutOrNull(STOP_TIMEOUT_MS) {
            connectionState.first { it == VpnConnectionState.DISCONNECTED }
        }
        if (settled == null) {
            // Never dispatch a start we could not sequence: it would hit "VPN already running"
            // and leave ActiveProfileRepository naming a server the tunnel never carried.
            onTimeout()
            return@launch
        }
        start(profileId)
    }

    companion object {
        /** Generous relative to a teardown, short enough that a wedged stop still surfaces. */
        const val STOP_TIMEOUT_MS = 8_000L
    }
}
