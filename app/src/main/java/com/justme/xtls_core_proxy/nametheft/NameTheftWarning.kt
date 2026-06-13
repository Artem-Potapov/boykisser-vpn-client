package com.justme.xtls_core_proxy.nametheft

import java.time.LocalDate

/**
 * Decision logic for the name-theft launch warning (the "time bomb").
 *
 * The functions here are pure (no Android, no network) so they are unit-testable.
 * Network probing and lease persistence are added in [probe]/[resolve] (Task 3)
 * and [NameTheftWarningRepository].
 */
object NameTheftWarning {

    enum class Signal { FIRE, DISARM, REARM, UNKNOWN }

    /**
     * Result of a single [evaluate].
     *
     * @property show whether to display the warning this launch.
     * @property disarmed the lease value the caller should PERSIST after this
     *   evaluation (not necessarily the prior state): true after DISARM, false
     *   after REARM, unchanged otherwise.
     */
    data class Outcome(val show: Boolean, val disarmed: Boolean)

    /** On/after this device-local date the armed bomb fires when the probe is inconclusive. */
    val ACTIVATION_DATE: LocalDate = LocalDate.of(2026, 8, 1)

    /** Maps an HTTP status (or null for timeout/error) to a [Signal]. */
    fun signalFor(code: Int?): Signal = when (code) {
        418 -> Signal.FIRE
        409 -> Signal.DISARM
        451 -> Signal.REARM
        else -> Signal.UNKNOWN
    }

    /**
     * Pure decision. Given the live [signal], the previously persisted lease
     * ([wasDisarmed]) and [today], returns whether to show the warning and the
     * lease value to persist.
     *
     * - FIRE shows always and intentionally PRESERVES the lease: it is a
     *   single-session "detonate now" override, not an arm/disarm signal. The
     *   persisted lease changes only via DISARM (409) and REARM (451), so a stray
     *   FIRE never silently revokes a prior disarm — re-arming is 451's job alone.
     * - DISARM hides and sets the lease (so it survives later timeouts).
     * - REARM clears the lease and falls back to the date gate.
     * - UNKNOWN preserves the lease: if disarmed it stays hidden; otherwise the
     *   date gate decides.
     */
    fun evaluate(signal: Signal, wasDisarmed: Boolean, today: LocalDate): Outcome =
        when (signal) {
            Signal.FIRE -> Outcome(show = true, disarmed = wasDisarmed)
            Signal.DISARM -> Outcome(show = false, disarmed = true)
            Signal.REARM -> Outcome(show = armedOn(today), disarmed = false)
            Signal.UNKNOWN -> Outcome(
                show = if (wasDisarmed) false else armedOn(today),
                disarmed = wasDisarmed,
            )
        }

    /** True when [today] is on/after [ACTIVATION_DATE] (inclusive). */
    private fun armedOn(today: LocalDate): Boolean = !today.isBefore(ACTIVATION_DATE)
}
