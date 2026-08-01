package com.justme.xtls_core_proxy.tile

import com.justme.xtls_core_proxy.log.VpnConnectionState

/**
 * Pure result of a QS-tile click, decided from observable inputs:
 *
 *  - the current `LogRepository.connectionState`,
 *  - the resolved active profile id (`null` when no profile exists in the DB),
 *  - whether `VpnService.prepare` would prompt for consent,
 *  - whether `POST_NOTIFICATIONS` runtime permission is missing on API 33+.
 *
 * Extracted out of `XrayVpnTileService.handleClick` so the decision can be
 * exercised by fast JVM unit tests without needing the QS framework, a Context,
 * or any system services.
 */
internal sealed interface TileClickDecision {
    /** State is active (CONNECTING / CONNECTED / PAUSED / BLACKHOLED) — dispatch ACTION_STOP. */
    data object Stop : TileClickDecision

    /** No profile exists — toast the user; do nothing else. */
    data object NoProfileToast : TileClickDecision

    /** All preconditions met — dispatch ACTION_START with this profile id. */
    data class Start(val profileId: Long) : TileClickDecision

    /**
     * VPN consent or POST_NOTIFICATIONS still missing — hand off to MainActivity
     * which will drive the consent dialog / runtime grant and then auto-connect
     * to this profile id.
     */
    data class HandoffToMainActivity(val profileId: Long) : TileClickDecision
}

internal fun decideTileClick(
    state: VpnConnectionState,
    profileId: Long?,
    needsVpnConsent: Boolean,
    needsNotifPermission: Boolean,
): TileClickDecision {
    // Every state in which the SERVICE IS RUNNING belongs here, not just the pretty ones.
    // BLACKHOLED still owns a TUN, so a Start dispatched from it would only reach startVpn's
    // "VPN already running" early return — the tile would render STATE_ACTIVE and do nothing.
    if (state == VpnConnectionState.CONNECTING ||
        state == VpnConnectionState.CONNECTED ||
        state == VpnConnectionState.PAUSED ||
        state == VpnConnectionState.BLACKHOLED
    ) {
        return TileClickDecision.Stop
    }
    if (profileId == null) return TileClickDecision.NoProfileToast
    return if (needsVpnConsent || needsNotifPermission) {
        TileClickDecision.HandoffToMainActivity(profileId)
    } else {
        TileClickDecision.Start(profileId)
    }
}
