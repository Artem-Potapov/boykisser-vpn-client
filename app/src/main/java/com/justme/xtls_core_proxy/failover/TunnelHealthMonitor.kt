package com.justme.xtls_core_proxy.failover

import com.justme.xtls_core_proxy.log.LogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls [probe] on a fixed cadence and reports the tunnel unhealthy after [failureThreshold]
 * consecutive failures. **Terminal after firing:** it invokes the listener at most once per
 * [start] call, then stops polling on its own ([isStarted] and the internal job are cleared
 * before the listener runs) — [pausePolling]/[resumePolling] can never revive it after that,
 * regardless of which order they land in relative to the fire. Only a fresh [start] call resets
 * state and begins polling again.
 *
 * Screen on/off is handled by the caller (XrayVpnService) wiring a BroadcastReceiver to
 * pausePolling()/resumePolling(), exactly as the kill-switch monitor does — so this class stays
 * unit-testable without registering receivers.
 *
 * Notable properties, relative to UsageStatsForegroundAppMonitor:
 *  - A throwing source there ABORTS the loop; here a throw IS the signal, so the loop must survive
 *    it instead (both the probe and the availability check are guarded per-tick).
 *  - resumePolling() probes immediately rather than waiting a full interval — via the same
 *    `firstTick` mechanism the sibling monitor also uses, not a divergence from it — so picking
 *    the phone back up recovers fast at zero idle cost.
 */
class TunnelHealthMonitor(
    private val probe: HealthProbe,
    private val availability: NetworkAvailability,
    private val intervalMs: Long,
    private val failureThreshold: Int,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null

    @Volatile private var listener: (() -> Unit)? = null
    @Volatile private var healthyListener: (() -> Unit)? = null
    @Volatile private var consecutiveFailures: Int = 0
    /** Latched after firing so we report once per transition, not once per failed probe. */
    @Volatile private var reportedUnhealthy: Boolean = false
    /** Latched after the first healthy probe of this [start], for the same once-per-transition reason. */
    @Volatile private var reportedHealthy: Boolean = false
    @Volatile private var isStarted: Boolean = false

    /**
     * [onHealthy] is optional and fires AT MOST ONCE per [start] call, on the first probe that
     * succeeds — the "the tunnel is working again" signal. Without it this class could only ever
     * report failure, so a caller that recorded a failure state had no way to learn it was stale.
     *
     * Note the parameter ORDER: [onHealthy] deliberately comes first so that the mandatory
     * [onUnhealthy] stays last and every existing `start { ... }` trailing-lambda call site keeps
     * binding to it. Swapping them would silently re-bind those callers to the optional listener.
     */
    fun start(onHealthy: (() -> Unit)? = null, onUnhealthy: () -> Unit) {
        isStarted = true
        listener = onUnhealthy
        healthyListener = onHealthy
        consecutiveFailures = 0
        reportedUnhealthy = false
        reportedHealthy = false
        job?.cancel()
        job = scope.launch {
            try {
                runPollLoop()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                // The listener is the last unguarded throw source inside runPollLoop(); this
                // scope has no CoroutineExceptionHandler, so letting it escape would crash the
                // process while the VPN is up. Log and let the loop end, same as the kill-switch
                // monitor's launch-site guard.
                LogRepository.append("TunnelHealthMonitor poll loop aborted: ${t.message}")
            }
        }
    }

    fun stop() {
        isStarted = false
        job?.cancel()
        job = null
        listener = null
        healthyListener = null
        consecutiveFailures = 0
        reportedUnhealthy = false
        reportedHealthy = false
    }

    fun pausePolling() {
        job?.cancel()
        job = null
        // consecutiveFailures / reportedUnhealthy intentionally preserved across a pause.
    }

    fun resumePolling() {
        if (!isStarted) return
        if (job != null) return
        job = scope.launch {
            try {
                runPollLoop()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                LogRepository.append("TunnelHealthMonitor poll loop aborted: ${t.message}")
            }
        }
    }

    private suspend fun runPollLoop() {
        var firstTick = true
        while (currentCoroutineContext().isActive) {
            if (!firstTick) delay(intervalMs)
            firstTick = false
            if (!currentCoroutineContext().isActive) return

            // NetworkAvailability makes no non-throw promise the way HealthProbe does (Task 2 only
            // hardened the concrete AndroidNetworkAvailability, not the interface). This scope has
            // no CoroutineExceptionHandler, so an escaping throw here would reach the default
            // handler and kill the process. Treat a throwing check the same as "offline": skip
            // this tick and reset the failure counter rather than blaming the server for it.
            val online = try {
                availability.hasUnderlyingInternet()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                false
            }

            if (!online) {
                // Device is offline: not the server's fault. Reset so returning signal cannot trip
                // an instant rotation off a stale count.
                consecutiveFailures = 0
                continue
            }

            val healthy = try {
                probe.isHealthy()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                false
            }

            if (healthy) {
                consecutiveFailures = 0
                reportedUnhealthy = false
                if (!reportedHealthy) {
                    reportedHealthy = true
                    // Guarded like the unhealthy listener below: this scope has no
                    // CoroutineExceptionHandler, and the enclosing launch-site catch would end the
                    // whole poll loop over a throw from a caller's recovery handler.
                    val h = healthyListener
                    if (h != null && currentCoroutineContext().isActive) {
                        try {
                            h.invoke()
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            LogRepository.append("TunnelHealthMonitor recovery listener failed: ${t.message}")
                        }
                    }
                }
                continue
            }

            consecutiveFailures++
            if (consecutiveFailures >= failureThreshold && !reportedUnhealthy) {
                reportedUnhealthy = true
                // Go fully terminal BEFORE invoking the listener: a pause landing either side of
                // this point must never leave resumePolling() able to relaunch into a loop that
                // still carries a stale consecutiveFailures/reportedUnhealthy — that loop would
                // poll forever without ever being able to fire again. Clearing isStarted here
                // (not just job) makes resumePolling()'s `if (!isStarted) return` guard fire in
                // BOTH orderings, and also protects against the listener itself synchronously
                // calling resumePolling() before this coroutine has returned.
                isStarted = false
                job = null
                LogRepository.append(
                    "Failover: tunnel unhealthy after $consecutiveFailures consecutive probe failures"
                )
                val l = listener
                if (l != null && currentCoroutineContext().isActive) l.invoke()
                return // terminal: only a fresh start() revives this monitor
            }
        }
    }

    /** Test-only: dispose of the internal CoroutineScope. */
    internal fun shutdownForTesting() {
        scope.cancel()
    }
}
