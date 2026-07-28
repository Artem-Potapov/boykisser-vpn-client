package com.justme.xtls_core_proxy.failover

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the device has a usable non-VPN network underneath the tunnel.
 *
 * Load-bearing guard: without it, airplane mode or lost signal makes every probe fail and the
 * engine would thrash through the entire server list blaming servers for the phone being offline.
 */
interface NetworkAvailability {
    fun hasUnderlyingInternet(): Boolean
}

class AndroidNetworkAvailability(private val context: Context) : NetworkAvailability {

    override fun hasUnderlyingInternet(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true // Cannot tell — assume online rather than suppressing real failover.
            // allNetworks is deprecated (API 31+), but the replacement, activeNetwork, returns the
            // VPN's OWN network while the tunnel is up — precisely the transport this check must
            // exclude. Switching to it would silently invert the check, so it is kept deliberately.
            cm.allNetworks.any { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@any false
                // Ignore our own VPN transport: it is "connected" even while the tunnel is dead.
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } catch (t: Throwable) {
            // Called from a SupervisorJob polling loop with no exception handler (Task 3+); an
            // escaping throw here would reach the default handler and kill the process. Same
            // fail-open rationale as the null-service branch above.
            true
        }
    }
}
