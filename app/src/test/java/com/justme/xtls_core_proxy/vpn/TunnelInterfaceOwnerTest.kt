package com.justme.xtls_core_proxy.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelInterfaceOwnerTest {

    @Test
    fun `all four owner variants are constructible`() {
        val none: TunnelInterfaceOwner<String> = TunnelInterfaceOwner.None
        assertTrue(none is TunnelInterfaceOwner.None)
        assertEquals("live", (TunnelInterfaceOwner.LiveProxy("live")).handle)
        assertEquals("bridge", (TunnelInterfaceOwner.UnreadRotationBridge("bridge")).handle)
        assertEquals("blackhole", (TunnelInterfaceOwner.UnreadBlackhole("blackhole")).handle)
    }

    @Test
    fun `role projection is exhaustive`() {
        val owners = listOf(
            TunnelInterfaceOwner.None,
            TunnelInterfaceOwner.LiveProxy("live"),
            TunnelInterfaceOwner.UnreadRotationBridge("bridge"),
            TunnelInterfaceOwner.UnreadBlackhole("blackhole"),
        )
        assertEquals(
            listOf(
                TunnelRole.NONE,
                TunnelRole.LIVE_PROXY,
                TunnelRole.UNREAD_ROTATION_BRIDGE,
                TunnelRole.UNREAD_BLACKHOLE,
            ),
            owners.map { it.toRole() },
        )
    }
}
