package com.justme.xtls_core_proxy.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnConnectionStateExtTest {

    @Test
    fun isActiveSessionState_returnsTrueForConnectingConnectedPausedBlackholed() {
        assertTrue(VpnConnectionState.CONNECTING.isActiveSessionState())
        assertTrue(VpnConnectionState.CONNECTED.isActiveSessionState())
        assertTrue(VpnConnectionState.PAUSED.isActiveSessionState())
        assertTrue(VpnConnectionState.BLACKHOLED.isActiveSessionState())
    }

    @Test
    fun isActiveSessionState_returnsFalseForDisconnectedAndError() {
        assertFalse(VpnConnectionState.DISCONNECTED.isActiveSessionState())
        assertFalse(VpnConnectionState.ERROR.isActiveSessionState())
    }

    @Test
    fun isActiveSessionState_classifiesAllEnumMembers() {
        val expectedMap = mapOf(
            VpnConnectionState.CONNECTING to true,
            VpnConnectionState.CONNECTED to true,
            VpnConnectionState.PAUSED to true,
            VpnConnectionState.BLACKHOLED to true,
            VpnConnectionState.DISCONNECTED to false,
            VpnConnectionState.ERROR to false,
        )
        assertEquals(
            "A VpnConnectionState was added without an explicit isActiveSessionState decision.",
            VpnConnectionState.entries.toSet(),
            expectedMap.keys,
        )
        for ((state, expected) in expectedMap) {
            assertEquals(expected, state.isActiveSessionState())
        }
    }
}
