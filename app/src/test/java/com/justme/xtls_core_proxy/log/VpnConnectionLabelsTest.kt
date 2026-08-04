package com.justme.xtls_core_proxy.log

import com.justme.xtls_core_proxy.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Home and tile both map [VpnConnectionState] through [vpnConnectionStateLabelRes]. The two
 * contained give-up outcomes share [VpnConnectionState.BLACKHOLED], so the recorded
 * [BlackholedOngoingLine] must reach this mapping or they share one user-facing string — the
 * absolute rule in AGENTS.md / docs/features/auto-failover.md.
 */
class VpnConnectionLabelsTest {

    @Test
    fun theTwoContainedOutcomesDoNotShareAHomeOrTileString() {
        val stillProxying = vpnConnectionStateLabelRes(
            VpnConnectionState.BLACKHOLED,
            BlackholedOngoingLine.STILL_PROXYING,
        )
        val trafficHeld = vpnConnectionStateLabelRes(
            VpnConnectionState.BLACKHOLED,
            BlackholedOngoingLine.TRAFFIC_HELD,
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
    fun aMissingLineFallsBackWithoutClaimingEitherOutcome() {
        // Defensive: a BLACKHOLED publish that somehow lacks a recorded line must not pick one
        // outcome's copy. The shared fallback is only for that gap — never the steady-state path.
        assertEquals(
            R.string.main_state_blackholed,
            vpnConnectionStateLabelRes(VpnConnectionState.BLACKHOLED, blackholedLine = null),
        )
    }

    @Test
    fun nonBlackholedStatesIgnoreTheLine() {
        assertEquals(
            R.string.main_state_connected,
            vpnConnectionStateLabelRes(
                VpnConnectionState.CONNECTED,
                BlackholedOngoingLine.TRAFFIC_HELD,
            ),
        )
        assertEquals(
            R.string.main_state_error,
            vpnConnectionStateLabelRes(
                VpnConnectionState.ERROR,
                BlackholedOngoingLine.STILL_PROXYING,
            ),
        )
    }
}
