package com.justme.xtls_core_proxy.tile

import com.justme.xtls_core_proxy.log.VpnConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM unit tests for `decideTileClick` — the click-decision function
 * extracted out of `XrayVpnTileService` so it can be exercised without the QS
 * framework, a Context, or system services.
 *
 * Coverage matrix: every `VpnConnectionState` value × profile-present vs.
 * absent × VPN-consent-needed vs. not × notification-permission-missing vs.
 * not. The interesting branches collapse to four observable outcomes:
 * Stop, NoProfileToast, Start, HandoffToMainActivity.
 */
class TileClickDecisionTest {

    @Test
    fun connecting_alwaysDecidesStop_regardlessOfOtherInputs() {
        // Other inputs should not influence the decision when state is active.
        // We vary them to catch any accidental coupling.
        assertEquals(TileClickDecision.Stop,
            decideTileClick(VpnConnectionState.CONNECTING, profileId = 12L,
                needsVpnConsent = false, needsNotifPermission = false))
        assertEquals(TileClickDecision.Stop,
            decideTileClick(VpnConnectionState.CONNECTING, profileId = null,
                needsVpnConsent = true, needsNotifPermission = true))
    }

    @Test
    fun connected_alwaysDecidesStop_regardlessOfOtherInputs() {
        assertEquals(TileClickDecision.Stop,
            decideTileClick(VpnConnectionState.CONNECTED, profileId = 12L,
                needsVpnConsent = false, needsNotifPermission = false))
        assertEquals(TileClickDecision.Stop,
            decideTileClick(VpnConnectionState.CONNECTED, profileId = null,
                needsVpnConsent = true, needsNotifPermission = true))
    }

    @Test
    fun paused_alwaysDecidesStop_regardlessOfOtherInputs() {
        assertEquals(TileClickDecision.Stop,
            decideTileClick(VpnConnectionState.PAUSED, profileId = 12L,
                needsVpnConsent = false, needsNotifPermission = false))
        assertEquals(TileClickDecision.Stop,
            decideTileClick(VpnConnectionState.PAUSED, profileId = null,
                needsVpnConsent = true, needsNotifPermission = true))
    }

    @Test
    fun blackholed_alwaysDecidesStop_regardlessOfOtherInputs() {
        // BLACKHOLED means the service is RUNNING and still owns a TUN, so the tile renders
        // STATE_ACTIVE. Without this branch the click falls through to Start -> ACTION_START ->
        // "VPN already running", i.e. a tile that looks live and does nothing — a worse failure
        // than one that visibly looks dead. Consent is already granted while running, so the
        // handoff branch does not save it either.
        assertEquals(TileClickDecision.Stop,
            decideTileClick(VpnConnectionState.BLACKHOLED, profileId = 12L,
                needsVpnConsent = false, needsNotifPermission = false))
        assertEquals(TileClickDecision.Stop,
            decideTileClick(VpnConnectionState.BLACKHOLED, profileId = null,
                needsVpnConsent = true, needsNotifPermission = true))
    }

    @Test
    fun disconnected_noProfile_decidesNoProfileToast() {
        assertEquals(TileClickDecision.NoProfileToast,
            decideTileClick(VpnConnectionState.DISCONNECTED, profileId = null,
                needsVpnConsent = false, needsNotifPermission = false))
    }

    @Test
    fun error_noProfile_decidesNoProfileToast() {
        // ERROR is treated as inactive for click semantics — same branch as DISCONNECTED.
        assertEquals(TileClickDecision.NoProfileToast,
            decideTileClick(VpnConnectionState.ERROR, profileId = null,
                needsVpnConsent = false, needsNotifPermission = false))
    }

    @Test
    fun disconnected_withProfile_allPermissionsOk_decidesStart() {
        assertEquals(TileClickDecision.Start(42L),
            decideTileClick(VpnConnectionState.DISCONNECTED, profileId = 42L,
                needsVpnConsent = false, needsNotifPermission = false))
    }

    @Test
    fun error_withProfile_allPermissionsOk_decidesStart() {
        assertEquals(TileClickDecision.Start(42L),
            decideTileClick(VpnConnectionState.ERROR, profileId = 42L,
                needsVpnConsent = false, needsNotifPermission = false))
    }

    @Test
    fun disconnected_withProfile_needsVpnConsent_decidesHandoff() {
        assertEquals(TileClickDecision.HandoffToMainActivity(42L),
            decideTileClick(VpnConnectionState.DISCONNECTED, profileId = 42L,
                needsVpnConsent = true, needsNotifPermission = false))
    }

    @Test
    fun disconnected_withProfile_needsNotifPermission_decidesHandoff() {
        assertEquals(TileClickDecision.HandoffToMainActivity(42L),
            decideTileClick(VpnConnectionState.DISCONNECTED, profileId = 42L,
                needsVpnConsent = false, needsNotifPermission = true))
    }

    @Test
    fun disconnected_withProfile_needsBothPermissions_decidesHandoff() {
        // Both missing → still a single handoff, not duplicated.
        assertEquals(TileClickDecision.HandoffToMainActivity(42L),
            decideTileClick(VpnConnectionState.DISCONNECTED, profileId = 42L,
                needsVpnConsent = true, needsNotifPermission = true))
    }

    @Test
    fun error_withProfile_needsVpnConsent_decidesHandoff() {
        assertEquals(TileClickDecision.HandoffToMainActivity(42L),
            decideTileClick(VpnConnectionState.ERROR, profileId = 42L,
                needsVpnConsent = true, needsNotifPermission = false))
    }

    @Test
    fun start_carriesExactProfileId() {
        // Non-trivial id to catch any accidental constant-folding.
        assertEquals(TileClickDecision.Start(987_654_321L),
            decideTileClick(VpnConnectionState.DISCONNECTED, profileId = 987_654_321L,
                needsVpnConsent = false, needsNotifPermission = false))
    }

    @Test
    fun handoff_carriesExactProfileId() {
        assertEquals(TileClickDecision.HandoffToMainActivity(987_654_321L),
            decideTileClick(VpnConnectionState.DISCONNECTED, profileId = 987_654_321L,
                needsVpnConsent = true, needsNotifPermission = false))
    }
}
