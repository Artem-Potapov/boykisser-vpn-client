package com.justme.xtls_core_proxy.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rule [shouldStopSessionForDeletedSubscription] owns: which profile a subscription delete
 * has to look at before it lets a CASCADE remove rows out from under a live or half-started session.
 */
class SubscriptionDeleteDecisionTest {

    @Test
    fun theActiveProfilesSubscriptionIsTornDown() {
        assertTrue(
            shouldStopSessionForDeletedSubscription(
                deletedSubscriptionId = 7L,
                activeProfileSubscriptionId = 7L,
                reconnectTargetSubscriptionId = null,
            )
        )
    }

    @Test
    fun aReconnectTargetIsCheckedEvenWithNoActiveProfile() {
        // THE case this rule exists for. ReconnectFlow's first step is disconnect(), which clears
        // ActiveProfileRepository — so for the whole settle window (up to STOP_TIMEOUT_MS) there is
        // NO active profile, and an active-id-only check waves the delete through. The flow then
        // calls start() on a profile the CASCADE has already removed: "Profile not found" → ERROR.
        assertTrue(
            shouldStopSessionForDeletedSubscription(
                deletedSubscriptionId = 7L,
                activeProfileSubscriptionId = null,
                reconnectTargetSubscriptionId = 7L,
            )
        )
    }

    @Test
    fun anUnrelatedSubscriptionLeavesTheSessionAlone() {
        // Deleting someone else's subscription must not cancel a reconnect the user asked for, so
        // the check is per-subscription rather than "is anything in flight".
        assertFalse(
            shouldStopSessionForDeletedSubscription(
                deletedSubscriptionId = 7L,
                activeProfileSubscriptionId = 9L,
                reconnectTargetSubscriptionId = 9L,
            )
        )
    }

    @Test
    fun manualProfilesAreNeverOwnedByASubscription() {
        // A manual profile carries subscriptionId == null; no subscription id may ever match it.
        assertFalse(
            shouldStopSessionForDeletedSubscription(
                deletedSubscriptionId = 7L,
                activeProfileSubscriptionId = null,
                reconnectTargetSubscriptionId = null,
            )
        )
    }
}
