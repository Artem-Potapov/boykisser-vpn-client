package com.justme.xtls_core_proxy.subs

import java.time.LocalDate

/**
 * Decision logic for the promoted-subscription remote gate.
 *
 * A deliberate behavioral clone of
 * [com.justme.xtls_core_proxy.nametheft.NameTheftWarning]: same status-code
 * semantics, same Aug 1 2026 date fallback, same revocable lease — but its own
 * /dowepromote endpoints and its own lease key. The duplication is intentional
 * (the two gates are kept independent; do not extract a shared helper). The pure
 * functions here have no Android / no network so they are unit-testable; the
 * network probe + persistence live in [probe]/[resolve] (Task 3) and
 * [PromoGateRepository].
 */
object PromoGate {

    enum class Signal { FIRE, DISARM, REARM, UNKNOWN }

    /**
     * Result of a single [evaluate].
     *
     * @property show whether to show the promo this launch (before the call site
     *   ANDs it with the "already a customer" suppression).
     * @property disarmed the lease value to PERSIST after this evaluation: true
     *   after DISARM, false after REARM, unchanged otherwise.
     */
    data class Outcome(val show: Boolean, val disarmed: Boolean)

    /** On/after this device-local date the armed gate shows when the probe is inconclusive. */
    val PROMO_CUTOFF_DATE: LocalDate = LocalDate.of(2026, 8, 1)

    /** Maps an HTTP status (or null for timeout/error) to a [Signal]. */
    fun signalFor(code: Int?): Signal = when (code) {
        418 -> Signal.FIRE
        409 -> Signal.DISARM
        451 -> Signal.REARM
        else -> Signal.UNKNOWN
    }

    /**
     * Pure decision (mirrors the name-theft bomb exactly):
     * - FIRE shows always and PRESERVES the lease (single-launch "show now"
     *   override; only DISARM/REARM change the persisted lease).
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

    /** True when [today] is on/after [PROMO_CUTOFF_DATE] (inclusive). */
    private fun armedOn(today: LocalDate): Boolean = !today.isBefore(PROMO_CUTOFF_DATE)
}
