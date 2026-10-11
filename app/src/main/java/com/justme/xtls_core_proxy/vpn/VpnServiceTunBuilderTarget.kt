package com.justme.xtls_core_proxy.vpn

import android.net.VpnService

internal class VpnServiceTunBuilderTarget(
    private val builder: VpnService.Builder,
) : TunBuilderTarget {
    override fun setSession(name: String) {
        builder.setSession(name)
    }

    override fun setMtu(mtu: Int) {
        builder.setMtu(mtu)
    }

    override fun addAddress(address: String, prefixLength: Int) {
        builder.addAddress(address, prefixLength)
    }

    override fun addRoute(destination: String, prefixLength: Int) {
        builder.addRoute(destination, prefixLength)
    }

    override fun addDnsServer(address: String) {
        builder.addDnsServer(address)
    }

    override fun addAllowedApplication(packageName: String) {
        builder.addAllowedApplication(packageName)
    }

    override fun addDisallowedApplication(packageName: String) {
        builder.addDisallowedApplication(packageName)
    }
}
