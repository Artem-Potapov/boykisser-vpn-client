package com.justme.xtls_core_proxy

import android.content.Context
import com.justme.xtls_core_proxy.state.PingPreferences
import com.justme.xtls_core_proxy.state.PingTester
import com.justme.xtls_core_proxy.testutil.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class PingPreferencesTest {

    private val prefs = InMemorySharedPreferences()
    private val context: Context = mock {
        on { getSharedPreferences("xray_prefs", Context.MODE_PRIVATE) } doReturn prefs
    }

    @Test
    fun defaults_match_live_ping_tester_constants() {
        assertEquals(PingTester.PING_TEST_TARGET, PingPreferences.DEFAULT.targetUrl)
        assertEquals(PingTester.PING_TIMEOUT_MS, PingPreferences.DEFAULT.timeoutMs)
        assertEquals(PingTester.DEFAULT_PING_CONCURRENCY, PingPreferences.DEFAULT.concurrency)
        assertFalse(PingPreferences.DEFAULT.autoOnOpen)
        assertEquals(PingPreferences.DEFAULT, PingPreferences.load(context))
    }

    @Test
    fun round_trips() {
        val settings = PingPreferences(
            targetUrl = "http://example.com/generate_204",
            timeoutMs = 12_000L,
            concurrency = 4,
            autoOnOpen = true,
        )
        PingPreferences.save(context, settings)
        assertEquals(settings, PingPreferences.load(context))
    }

    @Test
    fun load_falls_back_on_invalid_target() {
        prefs.edit()
            .putString("ping_target_url", "https://cp.cloudflare.com/generate_204")
            .apply()

        assertEquals(PingPreferences.DEFAULT.targetUrl, PingPreferences.load(context).targetUrl)
    }

    @Test
    fun load_clamps_timeout_and_concurrency() {
        prefs.edit()
            .putLong("ping_timeout_ms", 999_999L)
            .putInt("ping_concurrency", 99)
            .apply()

        val high = PingPreferences.load(context)
        assertEquals(PingPreferences.TIMEOUT_MAX, high.timeoutMs)
        assertEquals(PingPreferences.CONCURRENCY_MAX, high.concurrency)

        prefs.edit()
            .putLong("ping_timeout_ms", 1L)
            .putInt("ping_concurrency", 0)
            .apply()

        val low = PingPreferences.load(context)
        assertEquals(PingPreferences.TIMEOUT_MIN, low.timeoutMs)
        assertEquals(PingPreferences.CONCURRENCY_MIN, low.concurrency)
    }

    @Test
    fun save_clamps_timeout_and_concurrency() {
        PingPreferences.save(
            context,
            PingPreferences(
                targetUrl = "http://example.com/",
                timeoutMs = 999_999L,
                concurrency = 99,
                autoOnOpen = false,
            ),
        )
        assertEquals(PingPreferences.TIMEOUT_MAX, prefs.getLong("ping_timeout_ms", -1L))
        assertEquals(PingPreferences.CONCURRENCY_MAX, prefs.getInt("ping_concurrency", -1))

        PingPreferences.save(
            context,
            PingPreferences(
                targetUrl = "http://example.com/",
                timeoutMs = 1L,
                concurrency = 0,
                autoOnOpen = false,
            ),
        )
        assertEquals(PingPreferences.TIMEOUT_MIN, prefs.getLong("ping_timeout_ms", -1L))
        assertEquals(PingPreferences.CONCURRENCY_MIN, prefs.getInt("ping_concurrency", -1))
    }

    @Test
    fun save_writes_each_field_under_its_wire_key() {
        PingPreferences.save(
            context,
            PingPreferences(
                targetUrl = "  http://example.com/generate_204  ",
                timeoutMs = 12_000L,
                concurrency = 4,
                autoOnOpen = true,
            ),
        )
        assertEquals("http://example.com/generate_204", prefs.getString("ping_target_url", null))
        assertEquals(12_000L, prefs.getLong("ping_timeout_ms", -1L))
        assertEquals(4, prefs.getInt("ping_concurrency", -1))
        assertEquals(true, prefs.getBoolean("ping_auto_on_open", false))
    }
}
