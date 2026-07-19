package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.state.shouldAutoPing
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPingTest {
    @Test
    fun fires_only_when_enabled_and_not_consumed() {
        assertTrue(shouldAutoPing(autoOnOpen = true, alreadyConsumed = false))
        assertFalse(shouldAutoPing(autoOnOpen = true, alreadyConsumed = true))
        assertFalse(shouldAutoPing(autoOnOpen = false, alreadyConsumed = false))
    }
}
