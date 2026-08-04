package com.justme.xtls_core_proxy.state

import androidx.annotation.StringRes
import com.justme.xtls_core_proxy.R

/**
 * The body of [VpnViewModel.clearError]: empty the banner and revoke any refusal reprieve in one
 * step. Kept as a top-level so the wiring is JVM-testable — a test that only calls
 * [ConnectErrorRetention.onErrorCleared] would stay green if [VpnViewModel.clearError] dropped that
 * half.
 *
 * [VpnViewModel.clearError] currently has no production callers (the banner has no dismiss
 * affordance); this exists so a future dismiss path can call through without inventing a second
 * clear sequence. Until then, the `CONNECTING` auto-clear remains the only live clear path.
 */
internal fun clearVpnError(
    clearBanner: () -> Unit,
    retention: ConnectErrorRetention,
) {
    clearBanner()
    retention.onErrorCleared()
}

/**
 * Decides whether a transition to [com.justme.xtls_core_proxy.log.VpnConnectionState.CONNECTING]
 * clears the message in `VpnViewModel.error`.
 *
 * ### Why the auto-clear exists, and must stay
 * `error` has **no dismiss control and no timeout** — [VpnViewModel.clearError] has no production
 * callers yet — so the `CONNECTING` transition is the only thing that ever clears the banner. It is
 * keyed on the state rather than on `VpnViewModel.connect()` because the QS tile starts the VPN by
 * dispatching `ACTION_START` straight to the service, never through `connect()`; clearing inside
 * `connect()` alone missed every tile-initiated start. Do not move it back.
 *
 * ### What it used to get wrong
 * It cleared **every** message, including a **refusal** — the message that explains why the request
 * the user just made did nothing. Refusals are emitted by a request that LOST a contention, and the
 * request that WON then announces `CONNECTING` milliseconds later, wiping the explanation. The
 * dominant path is a contended Reconnect: `ReconnectFlow` can spend seconds between `stop()` and
 * `start()` while the state still reads `BLACKHOLED`, so the affordance keeps rendering and a
 * re-tap is the natural user response. That re-tap is refused, and the winner's own `CONNECTING`
 * erased the only trace of it. Only the *message* was lost — nothing pointed at the wrong server,
 * because `activeProfileIdToRestoreOnRefusedStart` covers that half separately.
 *
 * ### The rule
 * A refusal survives **exactly one** `CONNECTING` — the winner's — and is then treated like any
 * other message. That bound is what keeps the first constraint intact: a refusal cannot outlive the
 * attempt it was contemporaneous with and become the stale banner the auto-clear exists to remove.
 * The reprieve belongs to the message currently in `error`, so replacing it or clearing it revokes
 * it.
 *
 * Framework-free and stateful-but-tiny so the whole rule — including the "exactly one" bound, which
 * is the part a maintainer would get wrong — is covered by `ConnectErrorRetentionTest` on the JVM.
 * `VpnViewModel` owns one instance and does nothing but delegate.
 */
internal class ConnectErrorRetention {

    /**
     * The string resource currently rendered in `error`, or `null` when nothing is (or when its
     * reprieve has already been spent). Confined to the main thread: `VpnViewModel` touches this
     * only from `viewModelScope` collectors, which run on `Dispatchers.Main.immediate`.
     */
    @StringRes
    private var shownResId: Int? = null

    /** A message has just been written to `error`. */
    fun onErrorShown(@StringRes resId: Int) {
        shownResId = resId
    }

    /** `error` has been emptied without a replacement, so there is nothing left to protect. */
    fun onErrorCleared() {
        shownResId = null
    }

    /**
     * A transition to `CONNECTING` has landed.
     *
     * @return `true` when the caller must clear `error`. Spends the reprieve either way, so the
     *   next transition clears a refusal that survived this one.
     */
    fun onConnectingTransition(): Boolean {
        val shown = shownResId
        shownResId = null
        return shown !in REFUSAL_MESSAGES
    }

    companion object {
        /**
         * Messages that report a request as REFUSED rather than reporting a session as failed.
         *
         * Both of `connect_request_superseded`'s emitters are contentions resolved in favour of an
         * earlier request — the parked `pendingProfileId` slot and a reconnect already in flight —
         * and both are followed by that earlier request's `CONNECTING`. A new "we did not do what
         * you asked" string belongs in here; a new "the connection failed" string does not.
         */
        internal val REFUSAL_MESSAGES = setOf(R.string.connect_request_superseded)
    }
}
