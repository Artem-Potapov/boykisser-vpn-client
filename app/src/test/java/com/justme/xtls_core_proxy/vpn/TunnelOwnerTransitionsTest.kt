package com.justme.xtls_core_proxy.vpn

import com.justme.xtls_core_proxy.vpn.SessionTunnelState.CONNECTED
import com.justme.xtls_core_proxy.vpn.SessionTunnelState.PAUSED
import com.justme.xtls_core_proxy.vpn.SessionTunnelState.REVIVING
import com.justme.xtls_core_proxy.vpn.SessionTunnelState.ROTATING
import com.justme.xtls_core_proxy.vpn.SessionTunnelState.STARTING
import com.justme.xtls_core_proxy.vpn.SessionTunnelState.STOPPED
import com.justme.xtls_core_proxy.vpn.TunnelRole.LIVE_PROXY
import com.justme.xtls_core_proxy.vpn.TunnelRole.NONE
import com.justme.xtls_core_proxy.vpn.TunnelRole.UNREAD_BLACKHOLE
import com.justme.xtls_core_proxy.vpn.TunnelRole.UNREAD_ROTATION_BRIDGE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelOwnerTransitionsTest {

    /** Token handle: reference identity is what the transitions must preserve. */
    private class Token(val name: String) {
        var closeCount = 0

        fun close() {
            closeCount++
        }
    }

    private fun ownerFor(role: TunnelRole, handle: Token): TunnelInterfaceOwner<Token> = when (role) {
        NONE -> TunnelInterfaceOwner.None
        LIVE_PROXY -> TunnelInterfaceOwner.LiveProxy(handle)
        UNREAD_ROTATION_BRIDGE -> TunnelInterfaceOwner.UnreadRotationBridge(handle)
        UNREAD_BLACKHOLE -> TunnelInterfaceOwner.UnreadBlackhole(handle)
    }

    private fun assertPreparationRejected(expectedState: SessionTunnelState, role: TunnelRole) {
        val error = assertThrows(IllegalStateException::class.java) {
            TunnelOwnerTransitions.prepareRealEstablishment(
                owner = ownerFor(role, Token(role.name)),
                expectedState = expectedState,
            )
        }
        val message = error.message!!
        assertTrue("state missing from '$message'", message.contains(expectedState.name))
        assertTrue("role missing from '$message'", message.contains(role.name))
    }

    // ---- preparation: the three allowed state/role pairs ----

    @Test
    fun `preparation from None in STARTING keeps None and reports no bridge`() {
        val result = TunnelOwnerTransitions.prepareRealEstablishment(
            owner = TunnelInterfaceOwner.None,
            expectedState = STARTING,
        )
        assertTrue(result.owner is TunnelInterfaceOwner.None)
        assertNull(result.priorBridge)
    }

    @Test
    fun `preparation from None in REVIVING keeps None and reports no bridge`() {
        val result = TunnelOwnerTransitions.prepareRealEstablishment(
            owner = TunnelInterfaceOwner.None,
            expectedState = REVIVING,
        )
        assertTrue(result.owner is TunnelInterfaceOwner.None)
        assertNull(result.priorBridge)
    }

    @Test
    fun `preparation from a rotation bridge in ROTATING keeps the bridge authoritative`() {
        val handle = Token("bridge")
        val owner: TunnelInterfaceOwner<Token> = TunnelInterfaceOwner.UnreadRotationBridge(handle)

        val result = TunnelOwnerTransitions.prepareRealEstablishment(
            owner = owner,
            expectedState = ROTATING,
        )

        assertTrue(result.owner is TunnelInterfaceOwner.UnreadRotationBridge)
        assertSame(owner, result.owner)
        assertSame(handle, result.priorBridge)
    }

    // ---- preparation: every rejected state/role pair ----

    @Test
    fun `preparation rejects LIVE_PROXY in STARTING`() =
        assertPreparationRejected(STARTING, LIVE_PROXY)

    @Test
    fun `preparation rejects UNREAD_ROTATION_BRIDGE in STARTING`() =
        assertPreparationRejected(STARTING, UNREAD_ROTATION_BRIDGE)

    @Test
    fun `preparation rejects UNREAD_BLACKHOLE in STARTING`() =
        assertPreparationRejected(STARTING, UNREAD_BLACKHOLE)

    @Test
    fun `preparation rejects LIVE_PROXY in REVIVING`() =
        assertPreparationRejected(REVIVING, LIVE_PROXY)

    @Test
    fun `preparation rejects UNREAD_ROTATION_BRIDGE in REVIVING`() =
        assertPreparationRejected(REVIVING, UNREAD_ROTATION_BRIDGE)

    @Test
    fun `preparation rejects UNREAD_BLACKHOLE in REVIVING`() =
        assertPreparationRejected(REVIVING, UNREAD_BLACKHOLE)

    @Test
    fun `preparation rejects NONE in ROTATING`() =
        assertPreparationRejected(ROTATING, NONE)

    @Test
    fun `preparation rejects LIVE_PROXY in ROTATING`() =
        assertPreparationRejected(ROTATING, LIVE_PROXY)

    @Test
    fun `preparation rejects UNREAD_BLACKHOLE in ROTATING`() =
        assertPreparationRejected(ROTATING, UNREAD_BLACKHOLE)

    @Test
    fun `preparation rejects NONE in PAUSED`() =
        assertPreparationRejected(PAUSED, NONE)

    @Test
    fun `preparation rejects LIVE_PROXY in PAUSED`() =
        assertPreparationRejected(PAUSED, LIVE_PROXY)

    @Test
    fun `preparation rejects UNREAD_ROTATION_BRIDGE in PAUSED`() =
        assertPreparationRejected(PAUSED, UNREAD_ROTATION_BRIDGE)

    @Test
    fun `preparation rejects UNREAD_BLACKHOLE in PAUSED`() =
        assertPreparationRejected(PAUSED, UNREAD_BLACKHOLE)

    @Test
    fun `preparation rejects NONE in CONNECTED`() =
        assertPreparationRejected(CONNECTED, NONE)

    @Test
    fun `preparation rejects LIVE_PROXY in CONNECTED`() =
        assertPreparationRejected(CONNECTED, LIVE_PROXY)

    @Test
    fun `preparation rejects UNREAD_ROTATION_BRIDGE in CONNECTED`() =
        assertPreparationRejected(CONNECTED, UNREAD_ROTATION_BRIDGE)

    @Test
    fun `preparation rejects UNREAD_BLACKHOLE in CONNECTED`() =
        assertPreparationRejected(CONNECTED, UNREAD_BLACKHOLE)

    @Test
    fun `preparation rejects NONE in STOPPED`() =
        assertPreparationRejected(STOPPED, NONE)

    @Test
    fun `preparation rejects LIVE_PROXY in STOPPED`() =
        assertPreparationRejected(STOPPED, LIVE_PROXY)

    @Test
    fun `preparation rejects UNREAD_ROTATION_BRIDGE in STOPPED`() =
        assertPreparationRejected(STOPPED, UNREAD_ROTATION_BRIDGE)

    @Test
    fun `preparation rejects UNREAD_BLACKHOLE in STOPPED`() =
        assertPreparationRejected(STOPPED, UNREAD_BLACKHOLE)

    // ---- live proxy install ----

    @Test
    fun `live proxy installs over None with no cleanup handle`() {
        val candidate = Token("candidate")

        val result = TunnelOwnerTransitions.installLiveProxy(
            owner = TunnelInterfaceOwner.None,
            handle = candidate,
        )

        assertEquals(LIVE_PROXY, result.owner.toRole())
        assertSame(candidate, result.owner.handle)
        assertNull(result.priorBridgeToClose)
    }

    @Test
    fun `live proxy installs over a rotation bridge and defers that bridge to the caller`() {
        val bridgeHandle = Token("bridge")
        val candidate = Token("candidate")

        val result = TunnelOwnerTransitions.installLiveProxy(
            owner = TunnelInterfaceOwner.UnreadRotationBridge(bridgeHandle),
            handle = candidate,
        )

        assertEquals(LIVE_PROXY, result.owner.toRole())
        assertSame(candidate, result.owner.handle)
        assertSame(bridgeHandle, result.priorBridgeToClose)
    }

    @Test
    fun `live proxy install rejects an existing live proxy`() {
        val error = assertThrows(IllegalStateException::class.java) {
            TunnelOwnerTransitions.installLiveProxy(
                owner = TunnelInterfaceOwner.LiveProxy(Token("live")),
                handle = Token("candidate"),
            )
        }
        assertTrue(error.message!!.contains(LIVE_PROXY.name))
    }

    @Test
    fun `live proxy install rejects an unread blackhole`() {
        val error = assertThrows(IllegalStateException::class.java) {
            TunnelOwnerTransitions.installLiveProxy(
                owner = TunnelInterfaceOwner.UnreadBlackhole(Token("blackhole")),
                handle = Token("candidate"),
            )
        }
        assertTrue(error.message!!.contains(UNREAD_BLACKHOLE.name))
    }

    // ---- unread installs ----

    @Test
    fun `rotation bridge installs over None`() {
        val handle = Token("bridge")

        val installed = TunnelOwnerTransitions.installRotationBridge(
            owner = TunnelInterfaceOwner.None,
            handle = handle,
        )

        assertEquals(UNREAD_ROTATION_BRIDGE, installed.toRole())
        assertSame(handle, installed.handle)
    }

    @Test
    fun `rotation bridge install rejects an existing live proxy`() {
        val error = assertThrows(IllegalStateException::class.java) {
            TunnelOwnerTransitions.installRotationBridge(
                owner = TunnelInterfaceOwner.LiveProxy(Token("live")),
                handle = Token("bridge"),
            )
        }
        assertTrue(error.message!!.contains(LIVE_PROXY.name))
    }

    @Test
    fun `rotation bridge install rejects an already held rotation bridge`() {
        val error = assertThrows(IllegalStateException::class.java) {
            TunnelOwnerTransitions.installRotationBridge(
                owner = TunnelInterfaceOwner.UnreadRotationBridge(Token("bridge")),
                handle = Token("bridge2"),
            )
        }
        assertTrue(error.message!!.contains(UNREAD_ROTATION_BRIDGE.name))
    }

    @Test
    fun `rotation bridge install rejects an unread blackhole`() {
        val error = assertThrows(IllegalStateException::class.java) {
            TunnelOwnerTransitions.installRotationBridge(
                owner = TunnelInterfaceOwner.UnreadBlackhole(Token("blackhole")),
                handle = Token("bridge"),
            )
        }
        assertTrue(error.message!!.contains(UNREAD_BLACKHOLE.name))
    }

    @Test
    fun `blackhole installs over None`() {
        val handle = Token("blackhole")

        val installed = TunnelOwnerTransitions.installBlackhole(
            owner = TunnelInterfaceOwner.None,
            handle = handle,
        )

        assertEquals(UNREAD_BLACKHOLE, installed.toRole())
        assertSame(handle, installed.handle)
    }

    @Test
    fun `blackhole install rejects an existing live proxy`() {
        val error = assertThrows(IllegalStateException::class.java) {
            TunnelOwnerTransitions.installBlackhole(
                owner = TunnelInterfaceOwner.LiveProxy(Token("live")),
                handle = Token("blackhole"),
            )
        }
        assertTrue(error.message!!.contains(LIVE_PROXY.name))
    }

    @Test
    fun `blackhole install rejects a held rotation bridge`() {
        val error = assertThrows(IllegalStateException::class.java) {
            TunnelOwnerTransitions.installBlackhole(
                owner = TunnelInterfaceOwner.UnreadRotationBridge(Token("bridge")),
                handle = Token("blackhole"),
            )
        }
        assertTrue(error.message!!.contains(UNREAD_ROTATION_BRIDGE.name))
    }

    @Test
    fun `blackhole install rejects an already held blackhole`() {
        val error = assertThrows(IllegalStateException::class.java) {
            TunnelOwnerTransitions.installBlackhole(
                owner = TunnelInterfaceOwner.UnreadBlackhole(Token("blackhole")),
                handle = Token("blackhole2"),
            )
        }
        assertTrue(error.message!!.contains(UNREAD_BLACKHOLE.name))
    }

    // ---- bridge adoption ----

    @Test
    fun `adoption converts a held rotation bridge into a blackhole with the same handle`() {
        val handle = Token("bridge")

        val adopted = TunnelOwnerTransitions.adoptBridgeAsBlackhole(
            owner = TunnelInterfaceOwner.UnreadRotationBridge(handle),
        )

        assertEquals(UNREAD_BLACKHOLE, adopted.toRole())
        assertSame(handle, adopted.handle)
    }

    @Test
    fun `adoption rejects None`() {
        val error = assertThrows(IllegalStateException::class.java) {
            TunnelOwnerTransitions.adoptBridgeAsBlackhole(owner = TunnelInterfaceOwner.None)
        }
        assertTrue(error.message!!.contains(NONE.name))
    }

    @Test
    fun `adoption rejects an existing live proxy`() {
        val error = assertThrows(IllegalStateException::class.java) {
            TunnelOwnerTransitions.adoptBridgeAsBlackhole(
                owner = TunnelInterfaceOwner.LiveProxy(Token("live")),
            )
        }
        assertTrue(error.message!!.contains(LIVE_PROXY.name))
    }

    @Test
    fun `adoption rejects an already adopted blackhole`() {
        val error = assertThrows(IllegalStateException::class.java) {
            TunnelOwnerTransitions.adoptBridgeAsBlackhole(
                owner = TunnelInterfaceOwner.UnreadBlackhole(Token("blackhole")),
            )
        }
        assertTrue(error.message!!.contains(UNREAD_BLACKHOLE.name))
    }

    // ---- release ----

    @Test
    fun `release of a live proxy hands back the proxy handle`() {
        val handle = Token("live")

        val result = TunnelOwnerTransitions.release(
            owner = TunnelInterfaceOwner.LiveProxy(handle),
        )

        assertTrue(result.owner is TunnelInterfaceOwner.None)
        assertSame(handle, result.handleToClose)
    }

    @Test
    fun `release of a rotation bridge hands back the bridge handle`() {
        val handle = Token("bridge")

        val result = TunnelOwnerTransitions.release(
            owner = TunnelInterfaceOwner.UnreadRotationBridge(handle),
        )

        assertTrue(result.owner is TunnelInterfaceOwner.None)
        assertSame(handle, result.handleToClose)
    }

    @Test
    fun `release of a blackhole hands back the blackhole handle`() {
        val handle = Token("blackhole")

        val result = TunnelOwnerTransitions.release(
            owner = TunnelInterfaceOwner.UnreadBlackhole(handle),
        )

        assertTrue(result.owner is TunnelInterfaceOwner.None)
        assertSame(handle, result.handleToClose)
    }

    @Test
    fun `release of None hands back no handle`() {
        val result = TunnelOwnerTransitions.release(owner = TunnelInterfaceOwner.None)

        assertTrue(result.owner is TunnelInterfaceOwner.None)
        assertNull(result.handleToClose)
    }

    // ---- owner identity and the no-side-effect guarantee ----

    @Test
    fun `two live proxy owners holding equal handles are not equal`() {
        assertNotEquals(
            TunnelInterfaceOwner.LiveProxy("live"),
            TunnelInterfaceOwner.LiveProxy("live"),
        )
    }

    @Test
    fun `no transition closes or mutates the handle it is given`() {
        val liveHandle = Token("live")
        val bridgeHandle = Token("bridge")
        val blackholeHandle = Token("blackhole")
        val candidate = Token("candidate")

        TunnelOwnerTransitions.installLiveProxy(
            owner = TunnelInterfaceOwner.UnreadRotationBridge(bridgeHandle),
            handle = candidate,
        )
        TunnelOwnerTransitions.installRotationBridge(
            owner = TunnelInterfaceOwner.None,
            handle = bridgeHandle,
        )
        TunnelOwnerTransitions.installBlackhole(
            owner = TunnelInterfaceOwner.None,
            handle = blackholeHandle,
        )
        TunnelOwnerTransitions.adoptBridgeAsBlackhole(
            owner = TunnelInterfaceOwner.UnreadRotationBridge(bridgeHandle),
        )
        TunnelOwnerTransitions.release(owner = TunnelInterfaceOwner.LiveProxy(liveHandle))
        TunnelOwnerTransitions.release(
            owner = TunnelInterfaceOwner.UnreadRotationBridge(bridgeHandle),
        )
        TunnelOwnerTransitions.release(
            owner = TunnelInterfaceOwner.UnreadBlackhole(blackholeHandle),
        )
        TunnelOwnerTransitions.prepareRealEstablishment(
            owner = TunnelInterfaceOwner.UnreadRotationBridge(bridgeHandle),
            expectedState = ROTATING,
        )

        assertEquals(0, liveHandle.closeCount)
        assertEquals(0, bridgeHandle.closeCount)
        assertEquals(0, blackholeHandle.closeCount)
        assertEquals(0, candidate.closeCount)
    }
}
