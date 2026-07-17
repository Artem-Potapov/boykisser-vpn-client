package com.justme.xtls_core_proxy.testutil

import android.content.SharedPreferences

/**
 * A real, `HashMap`-backed fake of [SharedPreferences] for plain-JVM unit tests.
 *
 * Unlike a Mockito mock of [SharedPreferences] (which returns Mockito's own zero/false/null
 * defaults for any unstubbed getter, not the caller-supplied `defValue`), this fake actually
 * stores what is written and returns the real `defValue` on a miss — so production code that
 * relies on `getInt(key, default)` / `getString(key, default)` fallback behavior, or on a
 * genuine save-then-load round trip, is truthfully exercised without a device/emulator.
 *
 * Reusable across prefs-backed accessors (Mux, DNS, Routing, XrayCore, Ping, ...) — keep this
 * general rather than specializing it for one caller.
 */
class InMemorySharedPreferences : SharedPreferences {
    private val map = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)

    override fun getString(key: String, defValue: String?): String? {
        if (!map.containsKey(key)) return defValue
        @Suppress("UNCHECKED_CAST")
        return map[key] as? String ?: defValue
    }

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        if (!map.containsKey(key)) return defValues
        @Suppress("UNCHECKED_CAST")
        return map[key] as? MutableSet<String> ?: defValues
    }

    override fun getInt(key: String, defValue: Int): Int {
        if (!map.containsKey(key)) return defValue
        return map[key] as? Int ?: defValue
    }

    override fun getLong(key: String, defValue: Long): Long {
        if (!map.containsKey(key)) return defValue
        return map[key] as? Long ?: defValue
    }

    override fun getFloat(key: String, defValue: Float): Float {
        if (!map.containsKey(key)) return defValue
        return map[key] as? Float ?: defValue
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        if (!map.containsKey(key)) return defValue
        return map[key] as? Boolean ?: defValue
    }

    override fun contains(key: String): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = InMemoryEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        // No-op: these tests assert on load()/getX() directly, not on change notifications.
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        // No-op, mirrors registerOnSharedPreferenceChangeListener above.
    }

    /** Accumulates put/remove/clear ops and flushes them into the backing map on apply()/commit(). */
    private inner class InMemoryEditor : SharedPreferences.Editor {
        private val pendingWrites = HashMap<String, Any?>()
        private val pendingRemovals = mutableSetOf<String>()
        private var pendingClear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = stage(key, value)

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = stage(key, values)

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = stage(key, value)

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = stage(key, value)

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = stage(key, value)

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = stage(key, value)

        override fun remove(key: String): SharedPreferences.Editor {
            pendingWrites.remove(key)
            pendingRemovals += key
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            pendingClear = true
            pendingWrites.clear()
            pendingRemovals.clear()
            return this
        }

        override fun commit(): Boolean {
            flush()
            return true
        }

        override fun apply() {
            flush()
        }

        private fun stage(key: String, value: Any?): SharedPreferences.Editor {
            pendingRemovals -= key
            pendingWrites[key] = value
            return this
        }

        private fun flush() {
            if (pendingClear) {
                map.clear()
                pendingClear = false
            }
            pendingRemovals.forEach { map.remove(it) }
            pendingRemovals.clear()
            map.putAll(pendingWrites)
            pendingWrites.clear()
        }
    }
}
