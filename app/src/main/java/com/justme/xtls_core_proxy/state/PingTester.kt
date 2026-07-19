package com.justme.xtls_core_proxy.state

/**
 * Ping-probe defaults and the wall-clock backstop derivation.
 *
 * Bounded-parallel probe orchestration, cross-run de-duplication, and the native-admission cap now
 * live in [PingCoordinator]; this holder keeps only the shared constants (referenced by
 * [PingPreferences]) and the pure [backstopFor] derivation (used by the ViewModel and covered by
 * `PingTesterBackstopTest`).
 */
object PingTester {
    const val DEFAULT_PING_CONCURRENCY: Int = 3
    const val PING_TIMEOUT_MS: Long = 10_000L
    const val PING_TEST_TARGET: String = "http://cp.cloudflare.com/generate_204"
    const val BACKSTOP_MARGIN_MS: Long = 5_000L

    /** Wall-clock backstop for one probe: the Go-side timeout plus a margin for the unbounded
     *  instance-setup path. Must be > [timeoutMs]. */
    fun backstopFor(timeoutMs: Long): Long = timeoutMs + BACKSTOP_MARGIN_MS
}
