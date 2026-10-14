package com.justme.xtls_core_proxy.tile

import com.justme.xtls_core_proxy.log.GiveUpOngoingLine
import com.justme.xtls_core_proxy.log.VpnConnectionState
import com.justme.xtls_core_proxy.log.isActiveSessionState
import com.justme.xtls_core_proxy.log.vpnConnectionStateLabelRes

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

/**
 * The tile's Stop gate: `true` for every state in which the SERVICE IS RUNNING, not just the pretty
 * ones. `BLACKHOLED` still owns a TUN, so a Start dispatched from it would only reach `startVpn`'s
 * "VPN already running" early return — the tile would render STATE_ACTIVE and do nothing.
 *
 * **Deliberately a function of [state] alone.** `XrayVpnTileService.handleClick` calls this directly
 * for its no-IO fast path, *before* the `ActiveProfileRepository` lookup and the
 * `VpnService.prepare` / `POST_NOTIFICATIONS` checks — taking a `profileId` or the permission flags
 * here would force that lookup on the one path that must not need it. Keeping the gate parameterless
 * beyond the state is what lets both call sites share one copy instead of hand-duplicating it, which
 * is how a live-looking dead tile shipped once already.
 *
 * Note this delegates the membership set to [VpnConnectionState.isActiveSessionState]. The function
 * stays because `XrayVpnTileService.handleClick` calls it before profile and permission work.
 * `TileClickDecisionTest` sweeps the whole enum against this function so a future state cannot fall
 * through unnoticed.
 */
internal fun shouldStopOnTileClick(state: VpnConnectionState): Boolean =
    state.isActiveSessionState()

internal fun decideTileClick(
    state: VpnConnectionState,
    profileId: Long?,
    needsVpnConsent: Boolean,
    needsNotifPermission: Boolean,
): TileClickDecision {
    if (shouldStopOnTileClick(state)) return TileClickDecision.Stop
    if (profileId == null) return TileClickDecision.NoProfileToast
    return if (needsVpnConsent || needsNotifPermission) {
        TileClickDecision.HandoffToMainActivity(profileId)
    } else {
        TileClickDecision.Start(profileId)
    }
}

/** Active/inactive half of the QS tile. Labels are [tileLabelChoice], not this predicate. */
internal fun tileShowsActive(state: VpnConnectionState): Boolean = state.isActiveSessionState()

internal sealed interface TileLabelChoice {
    data class Localized(val labelRes: Int) : TileLabelChoice
    data class ResolvedProfileName(val fallbackLabelRes: Int) : TileLabelChoice
}

/**
 * Exhaustive tile label split. CONNECTED still resolves the profile name in the service;
 * BLACKHOLED and ERROR still pass the recorded give-up line. ERROR stays a label, not an active tile.
 */
internal fun tileLabelChoice(
    state: VpnConnectionState,
    giveUpLine: GiveUpOngoingLine?,
): TileLabelChoice = when (state) {
    VpnConnectionState.CONNECTED ->
        TileLabelChoice.ResolvedProfileName(vpnConnectionStateLabelRes(state))
    VpnConnectionState.DISCONNECTED,
    VpnConnectionState.CONNECTING,
    VpnConnectionState.PAUSED ->
        TileLabelChoice.Localized(vpnConnectionStateLabelRes(state))
    VpnConnectionState.BLACKHOLED,
    VpnConnectionState.ERROR ->
        TileLabelChoice.Localized(vpnConnectionStateLabelRes(state, giveUpLine))
}
