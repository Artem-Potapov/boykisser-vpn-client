package com.justme.xtls_core_proxy.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Runs latency probes with bounded concurrency. Pure orchestration: the actual dial is the
 * injected [probe], so this is unit-testable with a fake. Emits [PingState.Testing] for each
 * freshly-accepted id up front, then the probe's terminal state as it completes (streaming).
 * Ids whose probe is already in flight are de-duplicated, so a single-server retest that
 * overlaps a running group test can't double-dial the same server.
 *
 * If [probe] throws, that id resolves to [PingState.Unavailable] (a thrown [CancellationException]
 * propagates as normal).
 */
class PingTester(private val maxConcurrency: Int = DEFAULT_PING_CONCURRENCY) {

    private val gate = Mutex()
    private val inFlight = mutableSetOf<Long>()
    private val semaphore = Semaphore(maxConcurrency)

    suspend fun testAll(
        ids: List<Long>,
        onUpdate: (Long, PingState) -> Unit,
        probe: suspend (Long) -> PingState,
    ) {
        val fresh = gate.withLock { ids.filter { inFlight.add(it) } }
        if (fresh.isEmpty()) return
        fresh.forEach { onUpdate(it, PingState.Testing) }

        coroutineScope {
            fresh.forEach { id ->
                launch {
                    val state = try {
                        semaphore.withPermit { probe(id) }
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

    companion object {
        const val DEFAULT_PING_CONCURRENCY: Int = 3
        const val PING_TIMEOUT_MS: Long = 10_000L
        const val PING_TEST_TARGET: String = "http://cp.cloudflare.com/generate_204"
        /**
         * Wall-clock backstop for one probe, applied on the Kotlin side ABOVE the Go-side
         * PING_TIMEOUT_MS deadline. Go bounds the dial+request; this guards the unbounded
         * instance-setup path (core.New/Start) so a wedged probe can't hang a row on Testing
         * forever. Must be > PING_TIMEOUT_MS so a normal slow-but-completing probe isn't cut off.
         */
        const val PING_BACKSTOP_MS: Long = PING_TIMEOUT_MS + 5_000L
    }
}
