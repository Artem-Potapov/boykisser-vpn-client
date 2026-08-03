package com.justme.xtls_core_proxy.failover

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plain-HTTP 204 probe. Because SplitTunnelPlanner keeps this app INSIDE the tunnel in both split
 * modes (only protect()'d Xray sockets bypass), this request travels tun -> xray -> proxy ->
 * internet and therefore tests the exact path user traffic takes.
 *
 * **That claim only holds because the routing table is made to honour it.** Reaching the tun is not
 * enough — Xray still decides which outbound carries the request, and several rules would hand the
 * GET to `freedom` on a `protect()`'d socket, returning 204 with the proxy completely dead:
 * `BLOCKED_ONLY`'s `network: tcp,udp -> direct` catch-all, `EXCEPT_COUNTRY`'s country direct rules,
 * and any direct rule the imported config carries. `ConfigBuilder` therefore carves
 * `ConfigBuilder.HEALTH_PROBE_HOST` through the proxy in **every** mode (see
 * `ConfigBuilder.healthProbeCarveOutRule`, which also records the sniffing residual), and the target
 * is that fixed constant rather than the user-editable Ping Test URL, because a static rule and an
 * editable target cannot both be right. Any replacement probe must keep both halves.
 *
 * Deliberately NOT XrayBridge.measureLatency: that builds a throwaway instance whose sockets are
 * protect()'d OUT of the tun, so it answers "can this config reach that server", not "is the live
 * tunnel passing traffic" — and those diverge in precisely the failure mode this feature targets.
 *
 * [opener] is injected so tests can supply a mock connection.
 */
class Http204HealthProbe(
    private val targetUrl: String,
    private val timeoutMs: Long,
    private val opener: (String) -> HttpURLConnection = { url ->
        URL(url).openConnection() as HttpURLConnection
    },
) : HealthProbe {

    override suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        // withTimeoutOrNull does NOT bound a hung DNS resolution: runProbe() is a plain blocking
        // call with no suspension point, so cancellation has nothing to act on until it returns.
        // The socket connect/read timeouts are the real bound: the timeout only lands after the
        // blocking call returns, discarding an overrun result rather than shortening the wait, so
        // isHealthy() can itself outlast timeoutMs. Accepted for v1 — the poll loop is
        // sequential so ticks cannot overlap, which makes this slower detection, never a leak.
        // PingCoordinator.probeWithBackstop is the in-repo pattern if real bounding is ever needed.
        withTimeoutOrNull(timeoutMs) { runProbe() } ?: false
    }

    private fun runProbe(): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = opener(targetUrl).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs.toInt()
                readTimeout = timeoutMs.toInt()
                instanceFollowRedirects = false
                useCaches = false
            }
            connection.responseCode == 204
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
