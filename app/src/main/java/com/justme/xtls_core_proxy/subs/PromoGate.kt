package com.justme.xtls_core_proxy.subs

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Decision logic + remote probe for the promoted-subscription gate.
 *
 * A deliberate behavioral clone of
 * [com.justme.xtls_core_proxy.nametheft.NameTheftWarning] (same code semantics,
 * date fallback and lease behavior) with its own /dowepromote endpoints and lease
 * key. The duplication is intentional — do not extract a shared helper.
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

    const val PRIMARY_URL = "https://boykiss3r.site/dowepromote"
    const val FALLBACK_URL = "https://somenewsteps.space/dowepromote"

    private const val TIMEOUT_MS = 10_000L
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

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

    /**
     * Probes the primary host; if it returns no verdict (UNKNOWN — timeout,
     * connection error, or any non-418/409/451 status), probes the fallback host.
     */
    suspend fun probe(): Signal {
        val primary = probeHost(PRIMARY_URL)
        if (primary != Signal.UNKNOWN) return primary
        return probeHost(FALLBACK_URL)
    }

    /** GET [spec], map its status to a [Signal]; any timeout/throw becomes UNKNOWN. */
    private suspend fun probeHost(spec: String): Signal =
        withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val connection = (URL(spec).openConnection() as HttpURLConnection).apply {
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        requestMethod = "GET"
                        instanceFollowRedirects = true
                    }
                    try {
                        signalFor(connection.responseCode)
                    } finally {
                        connection.disconnect()
                    }
                }.getOrDefault(Signal.UNKNOWN)
            }
        } ?: Signal.UNKNOWN

    /**
     * Reads the persisted lease, probes, applies [evaluate], persists the lease if
     * it changed, and returns whether to show the promo this launch (caller ANDs
     * with the "already a customer" suppression).
     */
    suspend fun resolve(context: Context, today: LocalDate): Boolean {
        val wasDisarmed = PromoGateRepository.isDisarmed(context)
        val signal = probe()
        val outcome = evaluate(signal, wasDisarmed, today)
        if (outcome.disarmed != wasDisarmed) {
            PromoGateRepository.setDisarmed(context, outcome.disarmed)
        }
        return outcome.show
    }
}
