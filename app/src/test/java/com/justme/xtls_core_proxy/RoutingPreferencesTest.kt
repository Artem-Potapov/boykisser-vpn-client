package com.justme.xtls_core_proxy

import android.content.Context
import com.justme.xtls_core_proxy.config.RoutingCountry
import com.justme.xtls_core_proxy.config.RoutingMode
import com.justme.xtls_core_proxy.config.RoutingPreferences
import com.justme.xtls_core_proxy.config.RoutingSettings
import com.justme.xtls_core_proxy.testutil.InMemorySharedPreferences
import org.junit.Assert.assertEquals
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
}
