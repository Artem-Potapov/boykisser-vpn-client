package com.justme.xtls_core_proxy.sideload

import android.content.Context

/**
 * Persistence for the sideloading / developer-verification warning.
 *
 * Stored in the shared "xray_prefs" SharedPreferences file alongside the
 * kill-switch and split-tunnel preferences. Tracks the last app version code
 * for which the warning dialog has been shown, so the launch dialog appears at
 * most once per app version (mirrors FreeDroidWarn's once-per-upgrade behavior).
 *
 * No StateFlow (unlike KillSwitchRepository): this is a one-shot check at
 * launch, not a live-observed preference.
 */
object SideloadWarningRepository {
    private const val PREFS_NAME = "xray_prefs"
    private const val KEY_LAST_VERSION = "sideload_warning_last_version"

    /** True when the warning has not yet been shown for [currentVersionCode]. */
    fun shouldShow(context: Context, currentVersionCode: Int): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_LAST_VERSION, 0) < currentVersionCode
    }

    /** Records that the warning has been shown for [versionCode]. */
    fun markShown(context: Context, versionCode: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_LAST_VERSION, versionCode).apply()
    }
}
