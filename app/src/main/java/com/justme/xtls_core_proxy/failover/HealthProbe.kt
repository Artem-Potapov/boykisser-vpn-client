package com.justme.xtls_core_proxy.failover

/**
 * One health check of the live tunnel. Implementations MUST NOT throw — any failure is reported as
 * `false`, because a throw is itself the "unhealthy" signal and must not kill the polling loop.
 */
interface HealthProbe {
    suspend fun isHealthy(): Boolean
}
