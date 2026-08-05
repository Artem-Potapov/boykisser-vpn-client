package com.justme.xtls_core_proxy.state

/**
 * Whether deleting a subscription must stop the current session before the CASCADE removes its
 * profiles.
 *
 * The rule is one `||`, but its **second operand is the whole point**, and it is not the operand a
 * maintainer reaches for first.
 *
 * `VpnViewModel.deleteSubscription` used to ask only "does the ACTIVE profile belong to this
 * subscription". That question is unanswerable during a Reconnect: [ReconnectFlow]'s first step is
 * `disconnect()`, which clears `ActiveProfileRepository`, so for the whole settle window — up to
 * [ReconnectFlow.STOP_TIMEOUT_MS] — there is **no active profile at all**. A delete landing in that
 * window saw `null`, skipped the teardown entirely, and dropped the rows; the flow then reached
 * `start(profileId)` for a profile the CASCADE had just removed, which `startVpn` answers with
 * "Profile not found" → ERROR. The reconnect **target** is what the session is heading towards, so
 * it has to be examined alongside the profile the session currently names.
 *
 * Deliberately scoped per subscription rather than "is anything in flight": a reconnect to a
 * manual server, or to a server in a different subscription, is unaffected by this delete and must
 * not be cancelled as a side effect of it. `subscriptionId` is `null` for manual profiles, and a
 * `Long` id can never equal `null`, so those fall out for free.
 *
 * Framework-free so the rule is JVM-testable (`SubscriptionDeleteDecisionTest`); the two Room reads
 * that resolve the ids stay at the call site.
 */
internal fun shouldStopSessionForDeletedSubscription(
    deletedSubscriptionId: Long,
    activeProfileSubscriptionId: Long?,
    reconnectTargetSubscriptionId: Long?,
): Boolean = deletedSubscriptionId == activeProfileSubscriptionId ||
    deletedSubscriptionId == reconnectTargetSubscriptionId
