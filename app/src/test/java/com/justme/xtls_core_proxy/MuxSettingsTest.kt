package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.MuxSettings
import com.justme.xtls_core_proxy.config.QuicHandling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MuxSettingsTest {
    @Test fun off_is_disabled_with_defaults() {
        val off = MuxSettings.OFF
        assertFalse(off.enabled)
        assertEquals(8, off.concurrency)
        assertEquals(16, off.xudpConcurrency)
        assertEquals(QuicHandling.BLOCK, off.quicHandling)
    }

    @Test fun quic_handling_wire_values() {
        assertEquals("reject", QuicHandling.BLOCK.wire)
        assertEquals("allow", QuicHandling.ALLOW.wire)
        assertEquals("skip", QuicHandling.SKIP.wire)
    }
}
