package com.justme.xtls_core_proxy.state

import com.justme.xtls_core_proxy.log.VpnConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectActionTest {

    @Test
    fun aGiveUpStateOffersReconnectRatherThanNothing() {
        // The core did not fail — it gave up, and its tunnel is still there. Disabling every
        // connect affordance here contradicted the give-up alert, which tells the user to choose
        // another server.
        assertEquals(ConnectAction.RECONNECT, connectAction(VpnConnectionState.BLACKHOLED))
    }

    @Test
    fun aLiveSessionOffersNothing() {
        assertEquals(ConnectAction.UNAVAILABLE, connectAction(VpnConnectionState.CONNECTED))
        assertEquals(ConnectAction.UNAVAILABLE, connectAction(VpnConnectionState.CONNECTING))
        assertEquals(ConnectAction.UNAVAILABLE, connectAction(VpnConnectionState.PAUSED))
    }

    @Test
    fun aDeadOrUnprotectedSessionOffersAPlainConnect() {
        // ERROR covers both a dying session and the UNPROTECTED give-up, where no tunnel exists
        // and the core genuinely could not establish — "reconnect" would overstate what is left.
        assertEquals(ConnectAction.CONNECT, connectAction(VpnConnectionState.DISCONNECTED))
        assertEquals(ConnectAction.CONNECT, connectAction(VpnConnectionState.ERROR))
    }

    @Test
    fun everyStateIsMappedExplicitly() {
        // Guards the enum-widening hazard this branch hit once already: a new VpnConnectionState
        // must break this `when` at compile time rather than falling into a silent default.
        for (state in VpnConnectionState.entries) {
            assertEquals(
                "connectAction must map $state explicitly",
                true,
                connectAction(state) in ConnectAction.entries
            )
        }
    }
}
