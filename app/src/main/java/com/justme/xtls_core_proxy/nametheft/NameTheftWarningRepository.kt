package com.justme.xtls_core_proxy.nametheft

import android.content.Context

/**
 * Persistence for the name-theft warning's revocable "disarm" lease.
 *
 * Stored in the shared "xray_prefs" file alongside the kill-switch, split-tunnel
 * and sideload preferences. The lease is set by a 409 (DISARM), cleared by a 451
 * (REARM), and read on every launch when the probe is inconclusive — see
 * [NameTheftWarning.resolve].
 */
object NameTheftWarningRepository {
    private const val PREFS_NAME = "xray_prefs"
    private const val KEY_DISARMED = "name_theft_disarmed"

    /** True when a 409 has disarmed the bomb and no later 451 has re-armed it. */
    fun isDisarmed(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DISARMED, false)
    }

    /** Persists the lease: true on DISARM (409), false on REARM (451). */
    fun setDisarmed(context: Context, disarmed: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DISARMED, disarmed).apply()
    }
}
