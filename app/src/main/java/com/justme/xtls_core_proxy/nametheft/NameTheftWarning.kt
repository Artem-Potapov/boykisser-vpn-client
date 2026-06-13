package com.justme.xtls_core_proxy.nametheft

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Decision logic + remote probe for the name-theft launch warning (the "time bomb").
 *
 * [signalFor]/[evaluate] are pure (no Android, no network) and unit-tested.
 * [probe] queries the primary status host and, only when it yields no verdict,
 * the fallback host. [resolve] ties probe + lease + date together for callers.
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

    const val PRIMARY_URL = "https://boykiss3r.site/didtheyconfess"
    const val FALLBACK_URL = "https://somenewsteps.space/didtheyconfess"

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
     * it changed, and returns whether to show the warning this launch.
     */
    suspend fun resolve(context: Context, today: LocalDate): Boolean {
        val wasDisarmed = NameTheftWarningRepository.isDisarmed(context)
        val signal = probe()
        val outcome = evaluate(signal, wasDisarmed, today)
        if (outcome.disarmed != wasDisarmed) {
            NameTheftWarningRepository.setDisarmed(context, outcome.disarmed)
        }
        return outcome.show
    }
}
