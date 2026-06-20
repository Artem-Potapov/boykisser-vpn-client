package com.justme.xtls_core_proxy.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelPlannerTest {

    private val self = "com.justme.xtls_core_proxy"

    @Test
    fun allowOnly_alwaysIncludesSelf() {
        val plan = SplitTunnelPlanner.plan(
            SplitTunnelMode.ALLOW_ONLY, setOf("com.foo", "com.bar"), self
        )
        assertTrue(plan.allowedPackages.contains(self))
        assertTrue(plan.allowedPackages.contains("com.foo"))
        assertTrue(plan.allowedPackages.contains("com.bar"))
        assertTrue(plan.disallowedPackages.isEmpty())
    }

    @Test
    fun allowOnly_doesNotDuplicateSelfIfUserAddedIt() {
        val plan = SplitTunnelPlanner.plan(
            SplitTunnelMode.ALLOW_ONLY, setOf(self, "com.foo"), self
        )
        assertEquals(1, plan.allowedPackages.count { it == self })
    }

    @Test
    fun blockMode_doesNotDisallowSelf() {
        val plan = SplitTunnelPlanner.plan(
            SplitTunnelMode.BLOCK_ALL_EXCEPT_SELECTED, setOf("com.foo", self), self
        )
        assertFalse(plan.disallowedPackages.contains(self))
        assertTrue(plan.disallowedPackages.contains("com.foo"))
        assertTrue(plan.allowedPackages.isEmpty())
    }
}
