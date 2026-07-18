package com.justme.xtls_core_proxy

import android.content.Context
import com.justme.xtls_core_proxy.config.DnsPreferences
import com.justme.xtls_core_proxy.config.DnsQueryStrategy
import com.justme.xtls_core_proxy.config.DnsResolver
import com.justme.xtls_core_proxy.config.DnsSettings
import com.justme.xtls_core_proxy.testutil.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class DnsPreferencesTest {

    private val prefs = InMemorySharedPreferences()
    private val context: Context = mock {
        on { getSharedPreferences("xray_prefs", Context.MODE_PRIVATE) } doReturn prefs
    }

    @Test
    fun defaults_to_from_config() {
        assertEquals(DnsSettings.FROM_CONFIG, DnsPreferences.load(context))
    }

    @Test
    fun round_trips_custom() {
        val settings = DnsSettings(
            resolver = DnsResolver.CUSTOM,
            customUrl = "https://dns.ex.com/dns-query",
            customPinnedIp = "1.2.3.4",
            queryStrategy = DnsQueryStrategy.USE_IPV4,
        )
        DnsPreferences.save(context, settings)
        assertEquals(settings, DnsPreferences.load(context))
    }

    @Test
    fun load_falls_back_on_invalid_enum() {
        prefs.edit()
            .putString("dns_resolver", "BOGUS")
            .putString("dns_query_strategy", "BOGUS")
            .apply()

        val loaded = DnsPreferences.load(context)

        assertEquals(DnsResolver.FROM_CONFIG, loaded.resolver)
        assertEquals(DnsQueryStrategy.USE_IP, loaded.queryStrategy)
    }
}
