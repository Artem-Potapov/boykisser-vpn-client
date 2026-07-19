package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.state.shouldRebuildPingTester
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PingTesterRebuildDecisionTest {
    @Test
    fun keeps_existing_tester_when_concurrency_is_unchanged() {
        assertFalse(shouldRebuildPingTester(previousConcurrency = 3, requestedConcurrency = 3))
    }

    @Test
    fun rebuilds_tester_when_concurrency_changes() {
        assertTrue(shouldRebuildPingTester(previousConcurrency = 3, requestedConcurrency = 4))
    }
}
