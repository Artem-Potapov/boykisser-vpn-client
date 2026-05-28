package com.justme.xtls_core_proxy.killswitch

import com.justme.xtls_core_proxy.log.LogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Polls UsageStatsManager.queryEvents on a fixed cadence (default 1s). Maintains
 * the last-seen foreground package and fires Listener callbacks only on
 * transitions to/from a controlled-app state.
 *
 * The screen-on/screen-off behavior described in the spec (suspending polling
 * while the device is off) is handled by the caller (XrayVpnService) wiring
 * a BroadcastReceiver to call stop()/start() at the appropriate moments —
 * this monitor itself is unaware of screen state, which keeps it
 * unit-testable without registering broadcast receivers.
 */
class UsageStatsForegroundAppMonitor(
    private val source: UsageStatsEventSource,
    private val pollIntervalMs: Long = 1_000L,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onAutoStop: (() -> Unit)? = null
) : ForegroundAppMonitor {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private var job: Job? = null

    @Volatile private var packages: Set<String> = emptySet()
    @Volatile private var listener: ForegroundAppMonitor.Listener? = null

    /** null = no known foreground app yet (initial state, or after a clean stop) */
    @Volatile private var currentForeground: String? = null

    /** true = currentForeground is a controlled app and we have already fired onControlledAppForeground */
    @Volatile private var inControlledState: Boolean = false

    /** true = [start] has been called (and [stop] has not been called since). */
    @Volatile private var isStarted: Boolean = false

    override fun start(packages: Set<String>, listener: ForegroundAppMonitor.Listener) {
        isStarted = true
        this.packages = packages
        this.listener = listener
        job?.cancel()
        job = scope.launch {
            try {
                runPollLoop()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                LogRepository.append("KillSwitchMonitor poll loop aborted: ${t.message}")
                onAutoStop?.invoke()
            }
        }
    }

    override fun stop() {
        isStarted = false
        job?.cancel()
        job = null
        scope.coroutineContext.cancelChildren()
        currentForeground = null
        inControlledState = false
        listener = null
    }

    override fun updatePackages(packages: Set<String>) {
        scope.launch {
            val callback = mutex.withLock {
                val previousPackages = this@UsageStatsForegroundAppMonitor.packages
                this@UsageStatsForegroundAppMonitor.packages = packages
                computeReconciliationCallback(previousPackages, packages)
            }
            if (callback != null && currentCoroutineContext().isActive) {
                callback.invoke()
            }
        }
    }

    override fun pausePolling() {
        job?.cancel()
        job = null
        // currentForeground, inControlledState, packages, listener intentionally preserved.
    }

    override fun resumePolling() {
        if (!isStarted) return  // never started, nothing to resume
        if (job != null) return  // already polling
        job = scope.launch {
            try {
                runPollLoop()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                LogRepository.append("KillSwitchMonitor poll loop aborted: ${t.message}")
                onAutoStop?.invoke()
            }
        }
    }

    private suspend fun runPollLoop() {
        var firstTick = true
        while (currentCoroutineContext().isActive) {
            if (!firstTick) {
                delay(pollIntervalMs)
            }
            firstTick = false

            val now = System.currentTimeMillis()
            val events = try {
                source.queryForegroundEvents(now - 2 * pollIntervalMs, now)
            } catch (t: Throwable) {
                LogRepository.append("KillSwitchMonitor query failed: ${t.message}")
                onAutoStop?.invoke()
                return
            }

            if (events.isEmpty()) continue

            val latest = events.maxByOrNull { it.timestampMs } ?: continue
            val callback = computeForegroundCallback(latest.packageName)
            if (callback != null && currentCoroutineContext().isActive) {
                callback.invoke()
            }
        }
    }

    /**
     * Compute the listener callback for a foreground change under the mutex,
     * then return it so the caller can invoke it OUTSIDE the lock. Returns null
     * if the transition does not warrant a callback.
     */
    private suspend fun computeForegroundCallback(newForeground: String): (() -> Unit)? {
        return mutex.withLock {
            val previous = currentForeground
            if (previous == newForeground) return@withLock null
            currentForeground = newForeground

            val isNewControlled = newForeground in packages
            val l = listener ?: return@withLock null

            when {
                isNewControlled && !inControlledState -> {
                    inControlledState = true
                    ({ l.onControlledAppForeground(newForeground) })
                }
                !isNewControlled && inControlledState -> {
                    inControlledState = false
                    ({ l.onControlledAppLeftForeground() })
                }
                else -> null
            }
        }
    }

    private fun computeReconciliationCallback(
        previousPackages: Set<String>,
        newPackages: Set<String>
    ): (() -> Unit)? {
        val current = currentForeground ?: return null
        val l = listener ?: return null

        val wasControlled = current in previousPackages
        val isControlled = current in newPackages

        return when {
            !wasControlled && isControlled -> {
                inControlledState = true
                ({ l.onControlledAppForeground(current) })
            }
            wasControlled && !isControlled -> {
                inControlledState = false
                ({ l.onControlledAppLeftForeground() })
            }
            else -> null
        }
    }

    /** Test-only: dispose of the internal CoroutineScope. */
    internal fun shutdownForTesting() {
        scope.cancel()
    }
}
