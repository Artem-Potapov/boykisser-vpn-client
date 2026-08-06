package com.justme.xtls_core_proxy.log

import com.justme.xtls_core_proxy.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Home and tile both map [VpnConnectionState] through [vpnConnectionStateLabelRes]. Three give-up
 * outcomes are squeezed into two connection states, so the recorded [GiveUpOngoingLine] must reach
 * this mapping or outcomes with different packet truths share one user-facing string — the absolute
 * rule in AGENTS.md / docs/features/auto-failover.md.
 */
class VpnConnectionLabelsTest {

    @Test
    fun theTwoContainedOutcomesDoNotShareAHomeOrTileString() {
        val stillProxying = vpnConnectionStateLabelRes(
            VpnConnectionState.BLACKHOLED,
            GiveUpOngoingLine.STILL_PROXYING,
        )
        val trafficHeld = vpnConnectionStateLabelRes(
            VpnConnectionState.BLACKHOLED,
            GiveUpOngoingLine.TRAFFIC_HELD,
        )
        assertNotEquals(
            "CONTAINED_BY_LIVE_TUNNEL and CONTAINED_BY_BLACKHOLE must never share one message",
            stillProxying,
            trafficHeld,
        )
        assertEquals(R.string.main_state_blackholed_still_proxying, stillProxying)
        assertEquals(R.string.main_state_blackholed_traffic_held, trafficHeld)
    }

    @Test
    fun theUncontainedOutcomeIsNotShownAsAGenericError() {
        // The one outcome that admits exposure. The error banner, the 1101 ongoing line and the 1105
        // heads-up all say "not protected"; home and the tile said "Error" — the same word an
        // ordinary failed connection shows, on the two most-looked-at surfaces. It does not inherit
        // containment copy (the absolute rule holds either way), it UNDERSTATES.
        val unprotected = vpnConnectionStateLabelRes(
            VpnConnectionState.ERROR,
            GiveUpOngoingLine.UNPROTECTED,
        )
        assertEquals(R.string.main_state_unprotected, unprotected)
        assertNotEquals(
            "an uncontained give-up must not read as an ordinary error",
            R.string.main_state_error,
            unprotected,
        )
        assertNotEquals(
            "and must never inherit either containment string",
            vpnConnectionStateLabelRes(
                VpnConnectionState.BLACKHOLED,
                GiveUpOngoingLine.TRAFFIC_HELD,
            ),
            unprotected,
        )
    }

    @Test
    fun anOrdinaryConnectionErrorKeepsTheOrdinaryString() {
        // The other half of the same rule, and the reason the marker exists rather than a widened
        // ERROR mapping: a session that simply failed to connect has no give-up line, and telling
        // that user they are "not protected" by auto-failover would be a different lie.
        assertEquals(
            R.string.main_state_error,
            vpnConnectionStateLabelRes(VpnConnectionState.ERROR, giveUpLine = null),
        )
    }

    @Test
    fun aMissingLineFallsBackWithoutClaimingEitherOutcome() {
        // Defensive: a BLACKHOLED publish that somehow lacks a recorded line must not pick one
        // outcome's copy. The shared fallback is only for that gap — never the steady-state path.
        assertEquals(
            R.string.main_state_blackholed,
            vpnConnectionStateLabelRes(VpnConnectionState.BLACKHOLED, giveUpLine = null),
        )
    }

    @Test
    fun aLineThatCannotDescribeTheStateIsIgnoredRatherThanTrusted() {
        // Cross arms are unreachable in production (one producer writes state and line together),
        // so each state falls back to its own generic string instead of rendering the other's copy.
        assertEquals(
            R.string.main_state_connected,
            vpnConnectionStateLabelRes(
                VpnConnectionState.CONNECTED,
                GiveUpOngoingLine.TRAFFIC_HELD,
            ),
        )
        assertEquals(
            R.string.main_state_error,
            vpnConnectionStateLabelRes(
                VpnConnectionState.ERROR,
                GiveUpOngoingLine.STILL_PROXYING,
            ),
        )
        assertEquals(
            R.string.main_state_blackholed,
            vpnConnectionStateLabelRes(
                VpnConnectionState.BLACKHOLED,
                GiveUpOngoingLine.UNPROTECTED,
            ),
        )
    }
}
