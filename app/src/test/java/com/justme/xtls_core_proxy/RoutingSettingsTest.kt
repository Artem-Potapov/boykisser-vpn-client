package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.RoutingCountry
import com.justme.xtls_core_proxy.config.RoutingMode
import com.justme.xtls_core_proxy.config.RoutingSettings
import com.justme.xtls_core_proxy.config.requiredGeoFiles
import com.justme.xtls_core_proxy.config.routingNeedsDomainRules
import com.justme.xtls_core_proxy.config.sanitizeForAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingSettingsTest {
    @Test fun user_default_is_proxy_all_lan_on() {
        val d = RoutingSettings.USER_DEFAULT
        assertEquals(RoutingMode.PROXY_ALL, d.mode)
        assertTrue(d.bypassLan)
        assertFalse(d.blockAds)
    }

    @Test fun needs_domain_rules_for_modes_and_ads() {
        assertFalse(routingNeedsDomainRules(null))
        assertFalse(routingNeedsDomainRules(RoutingSettings.USER_DEFAULT))
        assertTrue(routingNeedsDomainRules(RoutingSettings.USER_DEFAULT.copy(blockAds = true)))
        assertTrue(routingNeedsDomainRules(RoutingSettings.USER_DEFAULT.copy(mode = RoutingMode.EXCEPT_COUNTRY)))
        assertTrue(routingNeedsDomainRules(RoutingSettings.USER_DEFAULT.copy(mode = RoutingMode.BLOCKED_ONLY)))
    }

    @Test fun ru_blocked_requires_ru_files() {
        val s = RoutingSettings(RoutingMode.BLOCKED_ONLY, RoutingCountry.RU, bypassLan = false, blockAds = false)
        assertEquals(setOf("geosite_RU.dat", "geoip_RU.dat"), requiredGeoFiles(s))
    }

    @Test fun downgrades_when_files_missing() {
        val s = RoutingSettings(RoutingMode.BLOCKED_ONLY, RoutingCountry.RU, bypassLan = true, blockAds = false)
        val sane = sanitizeForAvailability(s, availableFiles = setOf("geoip.dat", "geosite.dat"))
        assertEquals(RoutingMode.PROXY_ALL, sane.mode)
        assertTrue(sane.bypassLan) // LAN needs only geoip.dat (present) → preserved
    }

    @Test fun ir_blocked_is_never_supported() {
        val s = RoutingSettings(RoutingMode.BLOCKED_ONLY, RoutingCountry.IR, bypassLan = true, blockAds = false)
        // Even with all files, IR blocked downgrades (no dataset exists).
        val sane = sanitizeForAvailability(s, availableFiles = setOf("geoip.dat", "geosite.dat", "geoip_IR.dat", "geosite_IR.dat"))
        assertEquals(RoutingMode.PROXY_ALL, sane.mode)
    }

    @Test fun ir_except_country_needs_only_the_general_bundles() {
        // directTags(IR) is geosite:category-ir + geoip:ir — both resolve out of the general
        // bundles, so geosite_IR.dat must NOT be required. A build shipping only the general pair
        // (a supported setup: the per-country files are a separate manual download) must keep the mode.
        val s = RoutingSettings(RoutingMode.EXCEPT_COUNTRY, RoutingCountry.IR, bypassLan = false, blockAds = false)
        assertEquals(setOf("geosite.dat", "geoip.dat"), requiredGeoFiles(s))
        val sane = sanitizeForAvailability(s, availableFiles = setOf("geoip.dat", "geosite.dat"))
        assertEquals(RoutingMode.EXCEPT_COUNTRY, sane.mode)
    }

    @Test fun ru_except_country_still_requires_its_ext_file() {
        // RU's directTags DO include ext:geosite_RU.dat, so the per-country file stays required.
        val s = RoutingSettings(RoutingMode.EXCEPT_COUNTRY, RoutingCountry.RU, bypassLan = false, blockAds = false)
        assertEquals(setOf("geosite.dat", "geoip.dat", "geosite_RU.dat"), requiredGeoFiles(s))
        val sane = sanitizeForAvailability(s, availableFiles = setOf("geoip.dat", "geosite.dat"))
        assertEquals(RoutingMode.PROXY_ALL, sane.mode)
    }
}
