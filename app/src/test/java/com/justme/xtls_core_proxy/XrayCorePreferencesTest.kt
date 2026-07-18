package com.justme.xtls_core_proxy

import android.content.Context
import com.justme.xtls_core_proxy.config.XrayCorePreferences
import com.justme.xtls_core_proxy.config.XrayCoreSettings
import com.justme.xtls_core_proxy.config.XrayDomainStrategy
import com.justme.xtls_core_proxy.testutil.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class XrayCorePreferencesTest {

    private val prefs = InMemorySharedPreferences()
    private val context: Context = mock {
        on { getSharedPreferences("xray_prefs", Context.MODE_PRIVATE) } doReturn prefs
    }

    @Test fun defaults() {
        assertEquals(XrayCoreSettings.DEFAULT, XrayCorePreferences.load(context))
    }

    @Test fun clamps_mtu() {
        XrayCorePreferences.save(context, XrayCoreSettings.DEFAULT.copy(mtu = 99999))
        assertEquals(1500, XrayCorePreferences.load(context).mtu)
    }

    @Test fun round_trips() {
        val s = XrayCoreSettings(1360, ipv6 = false, sniffing = true, domainStrategy = XrayDomainStrategy.IP_IF_NON_MATCH)
        XrayCorePreferences.save(context, s)
        assertEquals(s, XrayCorePreferences.load(context))
    }
}
