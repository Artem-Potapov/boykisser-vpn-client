package com.justme.xtls_core_proxy.log

import com.justme.xtls_core_proxy.R

/**
 * Which packet-truth line a failover give-up is showing. Recorded at give-up beside the connection
 * state (same shape as the service's in-memory `giveUpLine` for 1101) so a later disable that clears
 * `giveUpOutcome` cannot re-derive the wrong copy. Published on [LogRepository.giveUpLine] for
 * home/tile.
 */
enum class GiveUpOngoingLine {
    /** `CONTAINED_BY_LIVE_TUNNEL`: the tunnel is up and still proxying; nothing is being dropped. */
    STILL_PROXYING,

    /** `CONTAINED_BY_BLACKHOLE`: packets enter an fd nobody reads and are deliberately dropped. */
    TRAFFIC_HELD,

    /**
     * `UNPROTECTED`: no tunnel at all — the user IS on the clear network. It rides
     * [VpnConnectionState.ERROR] rather than `BLACKHOLED`, which is exactly why it needs a recorded
     * line of its own: `ERROR` alone cannot tell an uncontained give-up from an ordinary failed
     * connection.
     */
    UNPROTECTED,
}

/**
 * String-resource id for the home state line and the QS tile label.
 *
 * [giveUpLine] is consulted for the **two** states that host more than one give-up outcome:
 *
 * - `BLACKHOLED` carries both contained outcomes, whose packet truths are opposite, so they must
 *   never share one string.
 * - `ERROR` carries an uncontained give-up **and** an ordinary connection failure. Those must not
 *   share one either, in both directions: an `UNPROTECTED` give-up reading "Error" understates real
 *   exposure on the two most-looked-at surfaces, while telling a user whose connection simply failed
 *   that they are "not protected" by auto-failover would be a different lie. The recorded line is
 *   what tells them apart — `null` means no give-up produced this state, so the ordinary string wins.
 *
 * A line that cannot describe the given state falls back to that state's own generic string rather
 * than rendering another outcome's copy. Those cross arms are unreachable in production (one
 * producer writes the state and the line in the same locked block) and are defensive only.
 */
internal fun vpnConnectionStateLabelRes(
    state: VpnConnectionState,
    giveUpLine: GiveUpOngoingLine? = null,
): Int = when (state) {
    VpnConnectionState.DISCONNECTED -> R.string.main_state_disconnected
    VpnConnectionState.CONNECTING -> R.string.main_state_connecting
    VpnConnectionState.CONNECTED -> R.string.main_state_connected
    VpnConnectionState.PAUSED -> R.string.main_state_paused
    VpnConnectionState.BLACKHOLED -> when (giveUpLine) {
        GiveUpOngoingLine.STILL_PROXYING -> R.string.main_state_blackholed_still_proxying
        GiveUpOngoingLine.TRAFFIC_HELD -> R.string.main_state_blackholed_traffic_held
        GiveUpOngoingLine.UNPROTECTED, null -> R.string.main_state_blackholed
    }
    VpnConnectionState.ERROR -> when (giveUpLine) {
        GiveUpOngoingLine.UNPROTECTED -> R.string.main_state_unprotected
        GiveUpOngoingLine.STILL_PROXYING,
        GiveUpOngoingLine.TRAFFIC_HELD,
        null -> R.string.main_state_error
    }
}
