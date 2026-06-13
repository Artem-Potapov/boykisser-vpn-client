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
     * lease value to persist. FIRE never alters the lease; REARM clears it; DISARM
     * sets it; UNKNOWN leaves it and falls back to the date gate when armed.
     */
    fun evaluate(signal: Signal, wasDisarmed: Boolean, today: LocalDate): Outcome =
        when (signal) {
            Signal.FIRE -> Outcome(show = true, disarmed = wasDisarmed)
            Signal.DISARM -> Outcome(show = false, disarmed = true)
            Signal.REARM -> Outcome(show = !today.isBefore(ACTIVATION_DATE), disarmed = false)
            Signal.UNKNOWN -> Outcome(
                show = if (wasDisarmed) false else !today.isBefore(ACTIVATION_DATE),
                disarmed = wasDisarmed,
            )
        }
}
