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
 * consecutive failures.
 *
 * Screen on/off is handled by the caller (XrayVpnService) wiring a BroadcastReceiver to
 * pausePolling()/resumePolling(), exactly as the kill-switch monitor does — so this class stays
 * unit-testable without registering receivers.
 *
 * Differences from UsageStatsForegroundAppMonitor worth knowing:
 *  - A throwing source there ABORTS the loop; here a throw IS the signal, so the loop must survive.
 *  - resumePolling() probes immediately rather than waiting a full interval, so picking the phone
 *    up recovers fast at zero idle cost.
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
    @Volatile private var consecutiveFailures: Int = 0
    /** Latched after firing so we report once per transition, not once per failed probe. */
    @Volatile private var reportedUnhealthy: Boolean = false
    @Volatile private var isStarted: Boolean = false

    fun start(onUnhealthy: () -> Unit) {
        isStarted = true
        listener = onUnhealthy
        consecutiveFailures = 0
        reportedUnhealthy = false
        job?.cancel()
        job = scope.launch { runPollLoop() }
    }

    fun stop() {
        isStarted = false
        job?.cancel()
        job = null
        listener = null
        consecutiveFailures = 0
        reportedUnhealthy = false
    }

    fun pausePolling() {
        job?.cancel()
        job = null
        // consecutiveFailures / reportedUnhealthy intentionally preserved across a pause.
    }

    fun resumePolling() {
        if (!isStarted) return
        if (job != null) return
        job = scope.launch { runPollLoop() }
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
                continue
            }

            consecutiveFailures++
            if (consecutiveFailures >= failureThreshold && !reportedUnhealthy) {
                reportedUnhealthy = true
                LogRepository.append(
                    "Failover: tunnel unhealthy after $consecutiveFailures consecutive probe failures"
                )
                val l = listener
                if (l != null && currentCoroutineContext().isActive) l.invoke()
                return // stop probing; the service restarts us after rotation settles
            }
        }
    }

    /** Test-only: dispose of the internal CoroutineScope. */
    internal fun shutdownForTesting() {
        scope.cancel()
    }
}
