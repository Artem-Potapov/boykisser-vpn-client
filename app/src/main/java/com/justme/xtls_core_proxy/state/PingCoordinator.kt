package com.justme.xtls_core_proxy.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

/**
 * Single, stable admission owner for latency probes across the ViewModel lifetime (P3-R5 / P3-R6).
 *
 * It is created ONCE and never swapped (this replaces the old reconstruct-on-concurrency-change
 * `PingTester` instance). It owns two independent bounds that must not be conflated:
 *
 *  - [inFlight] cross-run de-duplication: an id admitted by ANY active run is not re-admitted by a
 *    later run, even when the per-run concurrency differs between runs. Reconstructing a tester per
 *    run (the retired design) dropped this and let a settings change double-admit an id.
 *  - [nativeSlots]: a FIXED-ceiling ([nativeCeiling], = [PingPreferences.CONCURRENCY_MAX]) semaphore
 *    bounding concurrent *native* probe work — including orphaned JNI calls that outlive the
 *    wall-clock backstop. This is DISTINCT from the per-run concurrency permit (see [runGroup]):
 *    the per-run permit is released when the user-facing probe returns at the backstop, whereas a
 *    native slot is released only when the JNI call actually returns ([probeWithBackstop]). That
 *    difference is what keeps repeated backstop expirations from accumulating unbounded native work.
 */
class PingCoordinator(
    private val nativeCeiling: Int = PingPreferences.CONCURRENCY_MAX,
) {
    private val gate = Mutex()
    private val inFlight = mutableSetOf<Long>()
    private val nativeSlots = Semaphore(nativeCeiling)

    /** Native slots currently free. Diagnostic/test seam. */
    fun availableNativeSlots(): Int = nativeSlots.availablePermits

    /**
     * Run one group probe. [concurrency] is THIS run's parallelism, applied as a per-run limit
     * passed INTO the run rather than by reconstructing the coordinator — so the shared [inFlight]
     * set and the [nativeSlots] ceiling persist across concurrency changes. Ids already in flight
     * from any active run are de-duplicated. If [probe] throws, that id resolves to
     * [PingState.Unavailable]; a thrown [CancellationException] propagates.
     */
    suspend fun runGroup(
        ids: List<Long>,
        concurrency: Int,
        onUpdate: (Long, PingState) -> Unit,
        probe: suspend (Long) -> PingState,
    ) {
        val fresh = gate.withLock { ids.filter { inFlight.add(it) } }
        if (fresh.isEmpty()) return
        fresh.forEach { onUpdate(it, PingState.Testing) }

        val runLimit = Semaphore(concurrency.coerceAtLeast(1))
        coroutineScope {
            fresh.forEach { id ->
                launch {
                    val state = try {
                        runLimit.withPermit { probe(id) }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        PingState.Unavailable
                    } finally {
                        gate.withLock { inFlight.remove(id) }
                    }
                    onUpdate(id, state)
                }
            }
        }
    }

    /**
     * Admit and run one native probe under the bounded-orphan pattern (P3-R6, controller Option B).
     *
     * 1. Reserve a native slot up front; if the ceiling is full, invoke [onAdmissionRejected] and
     *    return [PingState.Unavailable] PROMPTLY (never block the UI).
     * 2. Launch [nativeCall] on [scope] (a supervisor scope — `viewModelScope` in production) so it
     *    is an orphan that outlives this caller. The native slot is released ONLY in the child's
     *    `finally`, after [nativeCall] actually returns — never at the backstop. Cancelling [scope]
     *    still runs that `finally`, so the slot is not leaked.
     * 3. The caller stops awaiting at [backstopMs] and returns [PingState.Unavailable] WITHOUT
     *    freeing the slot early (invoking [onBackstop]); the slot frees when the JNI call completes.
     *
     * [context] overrides the child's dispatcher (`Dispatchers.IO` in production for the blocking
     * JNI call; the test scheduler in unit tests so virtual time governs the backstop).
     */
    suspend fun probeWithBackstop(
        scope: CoroutineScope,
        backstopMs: Long,
        context: CoroutineContext = Dispatchers.IO,
        onAdmissionRejected: () -> Unit = {},
        onBackstop: () -> Unit = {},
        nativeCall: suspend () -> Result<Long>,
    ): PingState {
        if (!nativeSlots.tryAcquire()) {
            onAdmissionRejected()
            return PingState.Unavailable
        }
        val child = scope.async(context) {
            try {
                nativeCall()
            } finally {
                nativeSlots.release()
            }
        }
        val result = withTimeoutOrNull(backstopMs) { child.await() }
        return if (result == null) {
            onBackstop()
            PingState.Unavailable
        } else {
            PingState.fromResult(result)
        }
    }
}
