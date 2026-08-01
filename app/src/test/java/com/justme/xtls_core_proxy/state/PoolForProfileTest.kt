package com.justme.xtls_core_proxy.state

import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.db.Subscription
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [poolForProfile] resolves the "Connect fastest" candidate pool for a profile picked from the
 * ProfileActionsDialog long-press menu, which only ever knows a single [Profile] — the pool has to
 * be derived from the group ([ProfilesView]) that profile currently renders under.
 */
class PoolForProfileTest {

    private fun profile(id: Long, subscriptionId: Long? = null) =
        Profile(id = id, name = "p$id", config = "{}", subscriptionId = subscriptionId)

    private fun subscription(id: Long) = Subscription(id = id, name = "sub$id", url = "https://example/$id")

    @Test
    fun manualProfile_returnsWholeManualPartition() {
        val manual = listOf(profile(1), profile(2), profile(3))
        val view = ProfilesView(manual = manual, groups = emptyList())

        assertEquals(manual, poolForProfile(view, manual[1]))
    }

    @Test
    fun subscriptionProfile_returnsItsSubGroupProfiles() {
        val sub1 = subscription(10)
        val sub2 = subscription(20)
        val group1 = listOf(profile(1, subscriptionId = 10), profile(2, subscriptionId = 10))
        val group2 = listOf(profile(3, subscriptionId = 20))
        val view = ProfilesView(
            manual = emptyList(),
            groups = listOf(SubGroup(sub1, group1), SubGroup(sub2, group2))
        )

        assertEquals(group1, poolForProfile(view, group1[0]))
        assertEquals(group2, poolForProfile(view, group2[0]))
    }

    @Test
    fun subscriptionProfile_withNoMatchingGroup_fallsBackToJustThatProfile() {
        // A stale reference mid-recomposition (e.g. the subscription was deleted in the same
        // frame): the action must never disappear or crash, just degrade to a single-server probe.
        val orphan = profile(1, subscriptionId = 99)
        val view = ProfilesView(manual = emptyList(), groups = emptyList())

        assertEquals(listOf(orphan), poolForProfile(view, orphan))
    }
}
