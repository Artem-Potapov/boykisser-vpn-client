package com.justme.xtls_core_proxy.state

import com.justme.xtls_core_proxy.db.Profile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard for the auto-ping-skips-subscriptions race (QA sheet §4, P10).
 *
 * At launch the flat `profiles` Room query and the `subscriptions` Room query load independently.
 * The old auto-ping trigger rebuilt its server set from the *subscription-grouped* view, so in the
 * window after `profiles` had loaded but before `subscriptions` did (~30% of launches) the union was
 * manual-only, the once-per-launch [AutoPingLatch] was already spent, and every subscription server
 * was silently skipped — the observed 70/30 "sometimes works" flake.
 *
 * [autoPingServers] must derive the probe set straight from the single, atomic `profiles` query —
 * which already contains subscription-imported rows (`ProfileDao.getAll`) — so it never depends on
 * the `subscriptions` query having loaded.
 */
class AutoPingServersTest {

    private fun profile(id: Long, subscriptionId: Long?) =
        Profile(id = id, name = "p$id", config = "{}", subscriptionId = subscriptionId)

    @Test
    fun includes_subscription_servers_when_subscriptions_query_has_not_loaded_yet() {
        val manual = profile(id = 1, subscriptionId = null)
        val subscriptionImported = profile(id = 2, subscriptionId = 10)

        // `profiles` has emitted manual + subscription-imported rows atomically; the `subscriptions`
        // query is still empty — the exact transient that dropped subscription servers ~30% of runs.
        val servers = autoPingServers(
            profiles = listOf(manual, subscriptionImported),
            subscriptions = emptyList()
        )

        assertEquals(
            "auto-ping must probe subscription-imported servers without waiting on the subscriptions query",
            listOf(1L, 2L),
            servers.map { it.id }
        )
    }

    @Test
    fun orders_manual_profiles_before_subscription_servers() {
        // The subscription server has the LOWER id (its subscription was imported before this manual
        // profile was added), so the flat query's id-asc order would probe it first. But "My profiles"
        // renders at the top of the list, so auto-ping should probe manual servers first — top to
        // bottom — matching the visual order.
        val subscriptionImported = profile(id = 1, subscriptionId = 10)
        val manual = profile(id = 2, subscriptionId = null)

        val servers = autoPingServers(
            profiles = listOf(subscriptionImported, manual), // id-asc, as ProfileDao.getAll returns
            subscriptions = emptyList()
        )

        assertEquals(
            "manual ('My profiles') servers must be probed before subscription servers, matching the list UI",
            listOf(2L, 1L),
            servers.map { it.id }
        )
    }
}
