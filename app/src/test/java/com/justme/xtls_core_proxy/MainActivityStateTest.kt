package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.log.VpnConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for `isActive` — the "which row renders as the live session" rule that
 * `MainActivity` applies to every profile row, to the island header, and (via `isConnectedProfile`)
 * to the long-press action menu.
 *
 * It had **zero** test references before this class existed, despite being one of the boolean
 * chains `AGENTS.md` calls out as needing a grep sweep rather than a green build when
 * `VpnConnectionState` is widened. [everyStateIsClassifiedExplicitly] is the guard that turns that
 * grep sweep into a failing test.
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
        // `isActive` is a boolean chain over the enum, not an exhaustive `when`, so adding a
        // VpnConnectionState constant compiles cleanly and silently lands in the inactive branch.
        // For a new LIVE state that is a leak of the wrong kind: the UI would report nothing
        // connected while the service still owns a TUN. Enumerating the full enum here is what
        // makes widening it a test failure instead of a grep everyone forgets to run.
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
