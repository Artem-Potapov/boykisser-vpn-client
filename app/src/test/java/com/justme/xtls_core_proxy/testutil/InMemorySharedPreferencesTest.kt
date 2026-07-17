package com.justme.xtls_core_proxy.testutil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [InMemorySharedPreferences]'s `Editor.clear()` semantics to match Android's documented
 * [android.content.SharedPreferences.Editor.clear] contract: `clear()` only marks the persisted
 * store for wipe at commit time — any `put*`/`remove` staged in the SAME editor (before or after
 * `clear()`) is still applied on top, in order.
 */
class InMemorySharedPreferencesTest {

    @Test
    fun put_survives_clear_in_same_editor() {
        val prefs = InMemorySharedPreferences()

        prefs.edit().putString("a", "1").clear().apply()

        assertEquals("1", prefs.getString("a", null))
    }

    @Test
    fun standalone_clear_wipes_committed() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("b", "x").apply()

        prefs.edit().clear().apply()

        assertNull(prefs.getString("b", null))
    }

    @Test
    fun basic_round_trip_and_default() {
        val prefs = InMemorySharedPreferences()

        prefs.edit().putInt("count", 42).putBoolean("flag", true).apply()

        assertEquals(42, prefs.getInt("count", -1))
        assertEquals(true, prefs.getBoolean("flag", false))
        assertEquals("missing", prefs.getString("nope", "missing"))
    }
}
