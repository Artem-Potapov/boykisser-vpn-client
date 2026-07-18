package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.XrayCoreSettings
import com.justme.xtls_core_proxy.config.XrayDomainStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayCoreSettingsTest {
    @Test fun default_matches_current_behavior() {
        val d = XrayCoreSettings.DEFAULT
        assertEquals(1400, d.mtu)
        assertTrue(d.ipv6)
        assertEquals(false, d.sniffing)
        assertEquals(XrayDomainStrategy.FROM_CONFIG, d.domainStrategy)
    }

    @Test fun domain_strategy_wire() {
        assertNull(XrayDomainStrategy.FROM_CONFIG.wire)
        assertEquals("IPIfNonMatch", XrayDomainStrategy.IP_IF_NON_MATCH.wire)
        assertEquals("AsIs", XrayDomainStrategy.AS_IS.wire)
        assertEquals("IPOnDemand", XrayDomainStrategy.IP_ON_DEMAND.wire)
    }
}
