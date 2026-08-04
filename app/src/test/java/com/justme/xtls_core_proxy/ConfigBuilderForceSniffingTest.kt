package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.config.RoutingCountry
import com.justme.xtls_core_proxy.config.RoutingMode
import com.justme.xtls_core_proxy.config.RoutingSettings
import com.justme.xtls_core_proxy.config.XrayCoreSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M-I — [ConfigBuilder.forceSniffingFor] is the single owner of the forced-sniffing expression
 * shared by [ConfigBuilder.buildRuntimeConfig] and [ConfigSanitizer].
 */
class ConfigBuilderForceSniffingTest {
    private val coreOff = XrayCoreSettings.DEFAULT.copy(sniffing = false)
    private val coreOn = XrayCoreSettings.DEFAULT.copy(sniffing = true)

    @Test
    fun user_sniffing_toggle_forces_sniffing() {
        assertTrue(
            ConfigBuilder.forceSniffingFor(coreOn, RoutingSettings.USER_DEFAULT),
        )
    }

    @Test
    fun routing_that_needs_domain_rules_forces_sniffing() {
        val blocked = RoutingSettings(
            RoutingMode.BLOCKED_ONLY, RoutingCountry.RU, bypassLan = false, blockAds = false,
        )
        assertTrue(ConfigBuilder.forceSniffingFor(coreOff, blocked))
        assertTrue(
            ConfigBuilder.forceSniffingFor(
                coreOff,
                RoutingSettings.USER_DEFAULT.copy(blockAds = true),
            ),
        )
    }

    @Test
    fun default_proxy_all_without_ads_does_not_force_sniffing() {
        assertFalse(ConfigBuilder.forceSniffingFor(coreOff, RoutingSettings.USER_DEFAULT))
        assertFalse(ConfigBuilder.forceSniffingFor(coreOff, routing = null))
    }
}
