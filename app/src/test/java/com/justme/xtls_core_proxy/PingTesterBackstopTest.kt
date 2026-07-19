package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.state.PingTester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PingTesterBackstopTest {
    @Test
    fun backstop_is_timeout_plus_margin_and_greater() {
        assertEquals(10_000L + PingTester.BACKSTOP_MARGIN_MS, PingTester.backstopFor(10_000L))
        listOf(1_000L, 10_000L, 30_000L).forEach { assertTrue(PingTester.backstopFor(it) > it) }
    }
}
