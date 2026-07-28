package com.justme.xtls_core_proxy.failover

import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.db.ProfileDao

/**
 * Resolves the set of servers failover may rotate between.
 *
 * SPEC 2 SEAM: this is the single place that changes when user-curated pools land. Keep the
 * signature stable and keep the logic here rather than inlining it into the service.
 *
 * The pool is derived at runtime and never persisted, which sidesteps the fact that Room profile
 * IDs churn — replaceProfilesForSubscription deletes and re-inserts rows on every refresh.
 */
object FailoverPoolResolver {

    suspend fun resolve(dao: ProfileDao, current: Profile): List<Profile> {
        val subId = current.subscriptionId
        return if (subId == null) dao.getManualList() else dao.getBySubscriptionId(subId)
    }
}
