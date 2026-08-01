package com.justme.xtls_core_proxy.failover

/**
 * One health check of the live tunnel. Implementations MUST NOT throw — any failure is reported as
 * `false`, because a throw is itself the "unhealthy" signal and must not kill the polling loop.
 *
 * The one carve-out: `CancellationException` MUST propagate, never be caught and reported as
 * `false`. [TunnelHealthMonitor] relies on this to unwind cleanly on cancellation; swallowing it
 * here would leave its `consecutiveFailures` bookkeeping racing a cancelled probe instead. See
 * [Http204HealthProbe] for the pattern: `catch (ce: CancellationException) { throw ce }` ahead of
 * the catch-all.
 */
interface HealthProbe {
    suspend fun isHealthy(): Boolean
}
