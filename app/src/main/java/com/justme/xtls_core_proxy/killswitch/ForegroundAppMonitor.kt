package com.justme.xtls_core_proxy.killswitch

/**
 * Abstraction over the mechanism that detects when a "controlled" app
 * (an app in the kill-list) becomes the foreground app.
 *
 * Implementations:
 *   - UsageStatsForegroundAppMonitor: polls UsageStatsManager.queryEvents
 *   - (future) AccessibilityForegroundAppMonitor: event-driven via AccessibilityService
 */
interface ForegroundAppMonitor {

    interface Listener {
        /** A controlled app has just become the foreground app. */
        fun onControlledAppForeground(packageName: String)

        /** A previously-foregrounded controlled app is no longer foreground. */
        fun onControlledAppLeftForeground()
    }

    /**
     * Start observing. Idempotent — calling start while already started should
     * replace the listener and package set without losing state.
     */
    fun start(packages: Set<String>, listener: Listener)

    /** Stop observing. Idempotent. */
    fun stop()

    /**
     * Replace the package set without stopping. Must reconcile current foreground
     * against the new set: fire onControlledAppForeground if a now-listed app is
     * already foreground; fire onControlledAppLeftForeground if the previously
     * listed foreground app is no longer in the list.
     */
    fun updatePackages(packages: Set<String>)

    /**
     * Suspend polling without losing state. The internal `currentForeground` and
     * controlled-state flag are preserved so a subsequent [resumePolling] can
     * reconcile correctly when the next poll fires. Idempotent.
     *
     * Use case: screen-off broadcasts — pause the poll loop to save battery while
     * preserving the fact that we are mid-paused-state.
     */
    fun pausePolling()

    /**
     * Resume polling. The first tick after resume reconciles current foreground
     * against the preserved state and fires any pending transition. Idempotent.
     */
    fun resumePolling()
}
