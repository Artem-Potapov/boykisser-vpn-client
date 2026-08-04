package com.justme.xtls_core_proxy.tile

import com.justme.xtls_core_proxy.log.VpnConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for `decideTileClick` and `shouldStopOnTileClick` — the click-decision
 * functions extracted out of `XrayVpnTileService` so they can be exercised without the QS
 * framework, a Context, or system services. `shouldStopOnTileClick` is the whole of the Stop rule
 * `XrayVpnTileService.handleClick` applies on its no-IO fast path, so covering it here covers that
 * path even though the `TileService` method itself is out of reach of a plain-JVM test.
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
    fun everyLiveStateDecidesStop() {
        // Asserted against BOTH entry points, because XrayVpnTileService.handleClick reaches the
        // gate through shouldStopOnTileClick and never through decideTileClick — its no-IO fast
        // path returns before the profile lookup. handleClick itself is a TileService method no
        // plain-JVM test can call, but the whole of the rule it applies is this one function, so
        // pinning the function pins the fast path. It used to be duplicated by hand there, and that
        // duplicate drifted once and shipped a tile that rendered as an active Stop control and did
        // nothing.
        for (state in listOf(
            VpnConnectionState.CONNECTING,
            VpnConnectionState.CONNECTED,
            VpnConnectionState.PAUSED,
            VpnConnectionState.BLACKHOLED,
        )) {
            assertTrue(
                "$state is a live state: the tile's fast path must stop in it",
                shouldStopOnTileClick(state),
            )
            assertEquals(
                "$state must decide Stop",
                TileClickDecision.Stop,
                decideTileClick(state, profileId = 7L, needsVpnConsent = false,
                    needsNotifPermission = false),
            )
        }
    }

    @Test
    fun everyStateIsClassifiedExplicitly() {
        // The Stop gate is a boolean chain over the enum, not an exhaustive `when`, so adding a
        // VpnConnectionState constant compiles cleanly and silently falls through to the Start
        // branch. For a new LIVE state that reproduces the exact shipped defect this test exists
        // for: ACTION_START reaches startVpn's "VPN already running" early return, so the tile
        // renders STATE_ACTIVE and does nothing. Enumerating the full enum makes widening it a test
        // failure rather than a grep nobody runs.
        //
        // The sweep covers BOTH tile call sites, because both go through shouldStopOnTileClick —
        // decideTileClick on the start path, XrayVpnTileService.handleClick on its no-IO fast path.
        // It does NOT reach MainActivity.isActive or state/connectAction; those have their own
        // whole-enum guards in MainActivityStateTest and ConnectActionTest.
        val stops = setOf(
            VpnConnectionState.CONNECTING,
            VpnConnectionState.CONNECTED,
            VpnConnectionState.PAUSED,
            VpnConnectionState.BLACKHOLED,
        )
        val startable = setOf(
            VpnConnectionState.DISCONNECTED,
            VpnConnectionState.ERROR,
        )
        assertTrue(
            "A VpnConnectionState was added without deciding whether the QS tile stops in it.",
            (stops + startable).containsAll(VpnConnectionState.entries.toSet()),
        )
        for (state in VpnConnectionState.entries) {
            assertEquals(
                "$state: the shared gate handleClick's fast path calls",
                state in stops,
                shouldStopOnTileClick(state),
            )
            val decision = decideTileClick(state, profileId = 7L, needsVpnConsent = false,
                needsNotifPermission = false)
            assertEquals(
                "$state",
                if (state in stops) TileClickDecision.Stop else TileClickDecision.Start(7L),
                decision,
            )
        }
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
