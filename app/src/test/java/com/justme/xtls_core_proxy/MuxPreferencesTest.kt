package com.justme.xtls_core_proxy

import android.content.Context
import com.justme.xtls_core_proxy.config.MuxPreferences
import com.justme.xtls_core_proxy.config.MuxSettings
import com.justme.xtls_core_proxy.config.QuicHandling
import com.justme.xtls_core_proxy.testutil.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class MuxPreferencesTest {

    private val prefs = InMemorySharedPreferences()
    private val context: Context = mock {
        on { getSharedPreferences("xray_prefs", Context.MODE_PRIVATE) } doReturn prefs
    }

    @Test
    fun defaults_to_off_when_unset() {
        assertEquals(MuxSettings.OFF, MuxPreferences.load(context))
    }

    @Test
    fun round_trips() {
        val settings = MuxSettings(
            enabled = true,
            concurrency = 4,
            xudpConcurrency = 8,
            quicHandling = QuicHandling.SKIP,
        )
        MuxPreferences.save(context, settings)
        assertEquals(settings, MuxPreferences.load(context))
    }

    @Test
    fun load_coerces_and_falls_back() {
        prefs.edit()
            .putInt("mux_concurrency", 99999)
            .putString("mux_quic_handling", "GARBAGE")
            .apply()

        val loaded = MuxPreferences.load(context)

        assertEquals(1024, loaded.concurrency)
        assertEquals(QuicHandling.BLOCK, loaded.quicHandling)
    }

    @Test
    fun load_coerces_concurrency_lower_bound() {
        prefs.edit().putInt("mux_concurrency", 0).apply()

        assertEquals(1, MuxPreferences.load(context).concurrency)
    }

    @Test
    fun load_coerces_xudp_concurrency_lower_bound() {
        prefs.edit().putInt("mux_xudp_concurrency", -5).apply()

        assertEquals(0, MuxPreferences.load(context).xudpConcurrency)
    }
}
