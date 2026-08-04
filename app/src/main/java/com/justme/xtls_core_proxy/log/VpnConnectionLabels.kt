package com.justme.xtls_core_proxy.log

import com.justme.xtls_core_proxy.R

/**
 * Which packet-truth line a [VpnConnectionState.BLACKHOLED] session is showing. Recorded at
 * give-up beside the state (same shape as the service's in-memory `blackholedLine` for 1101) so a
 * later disable that clears `giveUpOutcome` cannot re-derive the wrong copy. Published on
 * [LogRepository.blackholedLine] for home/tile.
 */
enum class BlackholedOngoingLine {
    /** `CONTAINED_BY_LIVE_TUNNEL`: the tunnel is up and still proxying; nothing is being dropped. */
    STILL_PROXYING,

    /** `CONTAINED_BY_BLACKHOLE`: packets enter an fd nobody reads and are deliberately dropped. */
    TRAFFIC_HELD,
}

/**
 * String-resource id for the home state line and the QS tile label. [blackholedLine] is consulted
 * only for [VpnConnectionState.BLACKHOLED] — the two contained outcomes must never share one.
 */
internal fun vpnConnectionStateLabelRes(
    state: VpnConnectionState,
    blackholedLine: BlackholedOngoingLine? = null,
): Int = when (state) {
    VpnConnectionState.DISCONNECTED -> R.string.main_state_disconnected
    VpnConnectionState.CONNECTING -> R.string.main_state_connecting
    VpnConnectionState.CONNECTED -> R.string.main_state_connected
    VpnConnectionState.PAUSED -> R.string.main_state_paused
    VpnConnectionState.BLACKHOLED -> when (blackholedLine) {
        BlackholedOngoingLine.STILL_PROXYING -> R.string.main_state_blackholed_still_proxying
        BlackholedOngoingLine.TRAFFIC_HELD -> R.string.main_state_blackholed_traffic_held
        null -> R.string.main_state_blackholed
    }
    VpnConnectionState.ERROR -> R.string.main_state_error
}
