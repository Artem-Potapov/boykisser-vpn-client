package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.log.VpnConnectionState
import com.justme.xtls_core_proxy.log.isActiveSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for `isActive` — the "which row renders as the live session" rule that
 * `MainActivity` applies to every profile row, to the island header, and (via `isConnectedProfile`)
 * to the long-press action menu.
 *
 * `isActive` delegates state membership to the exhaustive shared `isActiveSessionState` helper.
 * [everyStateIsClassifiedExplicitly] remains a whole-enum guard for the caller's highlighting
 * contract, including profile identity; compiler coverage of the helper alone cannot pin that wiring.
 *
 * This is deliberately NOT the connect-affordance rule — that one is `state/connectAction`, covered
 * by `ConnectActionTest`. The two disagree on purpose: `BLACKHOLED` is *active* here (the service
 * owns a TUN, so the row shows as live and its menu offers Disconnect) while `connectAction` maps
 * it to RECONNECT.
 */
class MainActivityStateTest {

    private val profile = Profile(id = 7L, name = "s7", config = "{}")

    @Test
    fun theActiveProfileStaysHighlightedInEveryLiveState() {
        // BLACKHOLED is live and stoppable: the service still owns a TUN, so the row must keep
        // showing as active. Omitting it here would let the UI claim nothing is connected while
        // traffic is held in the tunnel.
        for (state in listOf(
            VpnConnectionState.CONNECTED,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.PAUSED,
            VpnConnectionState.BLACKHOLED,
        )) {
            assertTrue("$state must keep the active profile highlighted", isActive(profile, 7L, state))
        }
    }

    @Test
    fun aDeadSessionHighlightsNothing() {
        assertFalse(isActive(profile, 7L, VpnConnectionState.DISCONNECTED))
        assertFalse(isActive(profile, 7L, VpnConnectionState.ERROR))
    }

    @Test
    fun anotherProfileIsNeverHighlighted() {
        assertFalse(isActive(profile, 99L, VpnConnectionState.CONNECTED))
        assertFalse(isActive(profile, null, VpnConnectionState.CONNECTED))
    }

    @Test
    fun everyStateIsClassifiedExplicitly() {
        // Shared membership is exhaustive, but keep this independent whole-enum classification:
        // it pins Main's highlighting contract and verifies that isActive still delegates the
        // state half to the helper. A widened enum requires an explicit caller-policy decision.
        val live = setOf(
            VpnConnectionState.CONNECTED,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.PAUSED,
            VpnConnectionState.BLACKHOLED,
        )
        val dead = setOf(
            VpnConnectionState.DISCONNECTED,
            VpnConnectionState.ERROR,
        )
        assertTrue(
            "A VpnConnectionState was added without deciding whether it highlights the active " +
                "profile row. Classify it as live or dead here, and check MainActivity.isActive, " +
                "decideTileClick, XrayVpnTileService.handleClick and connectAction as a set.",
            (live + dead).containsAll(VpnConnectionState.entries.toSet()),
        )
        for (state in VpnConnectionState.entries) {
            assertTrue("$state", isActive(profile, 7L, state) == (state in live))
            assertEquals(
                "$state: isActive must share only the helper's state classification",
                state.isActiveSessionState(),
                isActive(profile, 7L, state),
            )
        }
    }

    @Test
    fun theDisconnectGateIncludesErrorAndEveryLiveState() {
        // Deliberately NOT the tile Stop / isActive set: ERROR is disconnectable here (an
        // UNPROTECTED give-up leaves the service running and tells the user to turn the VPN off)
        // while the tile maps ERROR to INACTIVE/Start. Omitting BLACKHOLED or ERROR here would
        // hide Disconnect in the states that most need it.
        for (state in listOf(
            VpnConnectionState.CONNECTED,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.PAUSED,
            VpnConnectionState.BLACKHOLED,
            VpnConnectionState.ERROR,
        )) {
            assertTrue("$state must show Disconnect", shouldShowDisconnect(state))
        }
        assertFalse(shouldShowDisconnect(VpnConnectionState.DISCONNECTED))
    }

    @Test
    fun everyStateIsClassifiedForTheDisconnectGate() {
        val shows = setOf(
            VpnConnectionState.CONNECTED,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.PAUSED,
            VpnConnectionState.BLACKHOLED,
            VpnConnectionState.ERROR,
        )
        val hides = setOf(
            VpnConnectionState.DISCONNECTED,
        )
        assertTrue(
            "A VpnConnectionState was added without deciding whether the top-bar Disconnect " +
                "button shows. Classify it here — this gate includes ERROR on purpose; do not " +
                "copy the tile Stop set.",
            (shows + hides).containsAll(VpnConnectionState.entries.toSet()),
        )
        for (state in VpnConnectionState.entries) {
            assertTrue("$state", shouldShowDisconnect(state) == (state in shows))
        }
    }
}
