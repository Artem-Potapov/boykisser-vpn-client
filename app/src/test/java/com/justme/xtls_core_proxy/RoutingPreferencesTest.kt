package com.justme.xtls_core_proxy

import android.content.Context
import com.justme.xtls_core_proxy.config.RoutingCountry
import com.justme.xtls_core_proxy.config.RoutingMode
import com.justme.xtls_core_proxy.config.RoutingPreferences
import com.justme.xtls_core_proxy.config.RoutingSettings
import com.justme.xtls_core_proxy.testutil.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class RoutingPreferencesTest {

    private val prefs = InMemorySharedPreferences()
    private val context: Context = mock {
        on { getSharedPreferences("xray_prefs", Context.MODE_PRIVATE) } doReturn prefs
    }
    // NOTE: getAssets() is intentionally left UNSTUBBED. On the mock it returns null, so
    // availableGeoFiles() hits an NPE inside its runCatching and returns emptySet(). Both
    // assertions below are invariant under the resulting availability sanitize:
    //  - PROXY_ALL never downgrades;  - sanitizeForAvailability never rewrites `country`.
    // Do NOT try to mock AssetManager — it is a final class and would require the inline mock-maker.

    @Test fun defaults_to_user_default() {
        assertEquals(RoutingSettings.USER_DEFAULT.mode, RoutingPreferences.load(context).mode)
    }

    @Test fun round_trips_country() {
        val s = RoutingSettings(RoutingMode.EXCEPT_COUNTRY, RoutingCountry.RU, bypassLan = true, blockAds = true)
        RoutingPreferences.save(context, s)
        assertEquals(s.country, RoutingPreferences.load(context).country)
    }

    // Guards the wire keys: save() must write each field under its exact pref key. load()'s
    // availability sanitize masks mode/LAN/ads on the asset-less JVM mock (only `country` round-trips),
    // so we assert the raw writes directly — a typo in KEY_MODE/KEY_LAN/KEY_ADS would slip past load().
    @Test fun save_writes_each_field_under_its_wire_key() {
        RoutingPreferences.save(
            context,
            RoutingSettings(RoutingMode.BLOCKED_ONLY, RoutingCountry.IR, bypassLan = false, blockAds = true)
        )
        assertEquals("BLOCKED_ONLY", prefs.getString("route_mode", null))
        assertEquals("IR", prefs.getString("route_country", null))
        assertFalse(prefs.getBoolean("route_bypass_lan", true))
        assertTrue(prefs.getBoolean("route_block_ads", false))
    }
}
