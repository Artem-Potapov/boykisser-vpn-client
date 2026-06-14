package com.justme.xtls_core_proxy.subs

import android.content.Context

/**
 * Persistence for the promo gate's revocable "disarm" lease.
 *
 * Stored in the shared "xray_prefs" file under its own key (independent of the
 * name-theft bomb's name_theft_disarmed). Set by a 409 (DISARM), cleared by a 451
 * (REARM), read on every launch when the probe is inconclusive — see
 * [PromoGate.resolve].
 */
object PromoGateRepository {
    private const val PREFS_NAME = "xray_prefs"
    private const val KEY_DISARMED = "promote_disarmed"

    /** True when a 409 has disarmed the promo and no later 451 has re-armed it. */
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
