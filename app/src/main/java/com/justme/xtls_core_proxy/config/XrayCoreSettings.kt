package com.justme.xtls_core_proxy.config

/** Global core knobs applied by [ConfigBuilder] `applyCoreSettings` (last in the overlay chain). */
data class XrayCoreSettings(
    val mtu: Int,
    val ipv6: Boolean,
    val sniffing: Boolean,
    val domainStrategy: XrayDomainStrategy,
) {
    companion object {
        val DEFAULT = XrayCoreSettings(
            mtu = ConfigBuilder.TUN_MTU,
            ipv6 = true,
            sniffing = false,
            domainStrategy = XrayDomainStrategy.FROM_CONFIG,
        )
    }
}

enum class XrayDomainStrategy(val wire: String?) {
    FROM_CONFIG(null), AS_IS("AsIs"), IP_IF_NON_MATCH("IPIfNonMatch"), IP_ON_DEMAND("IPOnDemand")
}
