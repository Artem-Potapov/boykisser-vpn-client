package com.justme.xtls_core_proxy.state

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-scoped once-per-app-launch latch for auto-ping-on-open (P3-R4).
 *
 * The consumed bit lives here — NOT on [VpnViewModel] — so it survives replacement of the
 * Activity/ViewModel instance within the same OS process and resets only on process death. A new
 * MainActivity/VpnViewModel is only a facade reading this holder, so it still sees the consumed
 * state and does not re-ping.
 *
 * It is deliberately NOT persisted: the requirement is once per app *launch*, not once per install,
 * so a real process death (and only that) re-arms it.
 */
object AutoPingLatch {
    private val consumed = AtomicBoolean(false)

    /** Whether auto-ping has already fired in this process. */
    val isConsumed: Boolean
        get() = consumed.get()

    /**
     * Atomically mark the latch consumed. Returns true if THIS call won the transition (was
     * previously unconsumed), false if it was already consumed — callers gate the actual probe on
     * the winning call so two overlapping consumers can't both launch.
     */
    fun consume(): Boolean = consumed.compareAndSet(false, true)

    /** Test-only reset seam. Resets in-memory process state only; persists nothing. */
    fun resetForTest() {
        consumed.set(false)
    }
}
