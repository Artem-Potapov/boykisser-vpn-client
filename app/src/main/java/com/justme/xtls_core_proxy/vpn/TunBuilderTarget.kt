package com.justme.xtls_core_proxy.vpn

internal interface TunBuilderTarget {
    fun setSession(name: String)
    fun setMtu(mtu: Int)
    fun addAddress(address: String, prefixLength: Int)
    fun addRoute(destination: String, prefixLength: Int)
    fun addDnsServer(address: String)
    fun addAllowedApplication(packageName: String)
    fun addDisallowedApplication(packageName: String)
}
