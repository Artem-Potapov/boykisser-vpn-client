package com.justme.xtls_core_proxy.subs

import com.justme.xtls_core_proxy.db.AppDatabase
import com.justme.xtls_core_proxy.db.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object SubscriptionRefreshCoordinator {

    private val inFlight = ConcurrentHashMap<Long, Job>()

    fun refresh(
        scope: CoroutineScope,
        subId: Long,
        activeProfileIdProvider: () -> Long?,
        db: AppDatabase,
        defaultUserAgent: String
    ): Job {
        inFlight[subId]?.let { existing ->
            if (existing.isActive) return existing
        }
        val job = scope.launch(Dispatchers.IO) {
            try {
                runRefresh(subId, activeProfileIdProvider, db, defaultUserAgent)
            } finally {
                inFlight.remove(subId)
            }
        }
        inFlight[subId] = job
        return job
    }

    private suspend fun runRefresh(
        subId: Long,
        activeProfileIdProvider: () -> Long?,
        db: AppDatabase,
        defaultUserAgent: String
    ) {
        val subDao = db.subscriptionDao()
        val profileDao = db.profileDao()
        val sub = subDao.getById(subId) ?: return

        when (val result = SubscriptionFetcher.fetch(sub, defaultUserAgent)) {
            is FetchResult.Failure -> {
                subDao.markError(subId, result.message)
            }
            is FetchResult.Success -> {
                val outcome = SubscriptionBodyParser.parseBody(result.body)
                val newProfiles = outcome.parsed.map { p ->
                    Profile(name = p.displayName, config = p.config, subscriptionId = subId)
                }

                val activeId = activeProfileIdProvider()
                val keepProfileId = activeId
                    ?.let { profileDao.getById(it) }
                    ?.takeIf { it.subscriptionId == subId }
                    ?.id

                profileDao.replaceProfilesForSubscription(subId, keepProfileId, newProfiles)

                val warning = if (outcome.parseErrorCount > 0) {
                    "${outcome.parseErrorCount} line(s) failed to parse"
                } else {
                    null
                }
                subDao.markFetchResult(
                    id = subId,
                    lastFetchedAt = System.currentTimeMillis(),
                    lastSeenIntervalHours = result.intervalHoursFromHeader,
                    lastError = warning
                )
            }
        }
    }
}
