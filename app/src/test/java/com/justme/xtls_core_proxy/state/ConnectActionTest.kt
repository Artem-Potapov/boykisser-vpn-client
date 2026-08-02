package com.justme.xtls_core_proxy.state

import com.justme.xtls_core_proxy.R
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
    fun everyConnectionStateHasADeliberateMapping() {
        // Guards the branch's signature defect: BLACKHOLED was once added to VpnConnectionState and
        // picked up by the two `when` sites the compiler forced, while four boolean chains that also
        // read it were missed. This pins the whole partition, so adding a state fails here with the
        // new state named rather than silently inheriting a neighbour's behaviour.
        val expected = mapOf(
            VpnConnectionState.DISCONNECTED to ConnectAction.CONNECT,
            VpnConnectionState.ERROR to ConnectAction.CONNECT,
            VpnConnectionState.BLACKHOLED to ConnectAction.RECONNECT,
            VpnConnectionState.CONNECTING to ConnectAction.UNAVAILABLE,
            VpnConnectionState.CONNECTED to ConnectAction.UNAVAILABLE,
            VpnConnectionState.PAUSED to ConnectAction.UNAVAILABLE,
        )
        assertEquals(VpnConnectionState.entries.toSet(), expected.keys)
        expected.forEach { (state, action) -> assertEquals(state.name, action, connectAction(state)) }
    }

    @Test
    fun aGiveUpStateLabelsTheAffordanceReconnect() {
        // The label half of the rule must agree with the enablement half: RECONNECT is the one
        // action whose button text differs, and it is the whole point of the give-up affordance.
        assertEquals(R.string.main_button_reconnect, connectLabelRes(ConnectAction.RECONNECT, false))
        assertEquals(R.string.main_button_connect, connectLabelRes(ConnectAction.CONNECT, false))
        assertEquals(
            R.string.main_button_connect,
            connectLabelRes(ConnectAction.UNAVAILABLE, false)
        )
    }

    @Test
    fun aConnectingSessionLabelsProgressRatherThanItsAction() {
        // isConnecting is a transient progress state, not a fourth action, and it outranks every
        // action's own label while it holds.
        for (action in ConnectAction.entries) {
            assertEquals(
                action.name,
                R.string.main_button_connecting,
                connectLabelRes(action, isConnecting = true)
            )
        }
    }
}
