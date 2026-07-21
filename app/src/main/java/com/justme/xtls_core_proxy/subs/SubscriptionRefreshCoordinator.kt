package com.justme.xtls_core_proxy.subs

import android.os.Build
import android.content.Context
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.db.AppDatabase
import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import com.justme.xtls_core_proxy.privacy.DeviceIdentityHeaders
import com.justme.xtls_core_proxy.privacy.DeviceIdentityRepository
import com.justme.xtls_core_proxy.privacy.HwidRejection
import com.justme.xtls_core_proxy.privacy.HwidRejectionDetector
import com.justme.xtls_core_proxy.privacy.UaHint
import com.justme.xtls_core_proxy.privacy.UserAgentBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object SubscriptionRefreshCoordinator {

    private val inFlight = ConcurrentHashMap<Long, Job>()

    fun refresh(
        scope: CoroutineScope,
        context: Context,
        subId: Long,
        activeProfileIdProvider: () -> Long?,
        db: AppDatabase,
        defaultUserAgent: String
    ): Job {
        inFlight[subId]?.let { existing ->
            if (existing.isActive) return existing
        }
        val localizedContext = SupportedLanguage.localize(context)
        val job = scope.launch(Dispatchers.IO) {
            try {
                runRefresh(localizedContext, subId, activeProfileIdProvider, db, defaultUserAgent)
            } finally {
                inFlight.remove(subId)
            }
        }
        inFlight[subId] = job
        return job
    }

    private suspend fun runRefresh(
        context: Context,
        subId: Long,
        activeProfileIdProvider: () -> Long?,
        db: AppDatabase,
        defaultUserAgent: String
    ) {
        val subDao = db.subscriptionDao()
        val profileDao = db.profileDao()
        val sub = subDao.getById(subId) ?: return

        // Load device-identity settings and derive the request headers + effective UA once.
        val settings = DeviceIdentityRepository.load(context)
        val identityHeaders = DeviceIdentityHeaders.build(
            settings = settings,
            realOsVersion = Build.VERSION.RELEASE ?: "",
            realModel = Build.MODEL ?: "",
            realLanguage = Locale.getDefault().language,
        )
        val effectiveDefaultUa = UserAgentBuilder.build(settings, defaultUserAgent)
        val effectiveUa = sub.userAgentOverride?.takeIf { it.isNotBlank() } ?: effectiveDefaultUa
        val uaIsHappLike = effectiveUa.startsWith("Happ/")
        val hwidWasSent = identityHeaders.containsKey("x-hwid")

        when (val result = SubscriptionFetcher.fetch(context, sub, effectiveDefaultUa, identityHeaders)) {
            is FetchResult.Failure -> {
                val message = when (HwidRejectionDetector.detect(result.responseHeaders, hwidWasSent)) {
                    HwidRejection.MAX_DEVICES -> context.getString(R.string.subs_error_hwid_limit)
                    HwidRejection.NOT_SUPPORTED -> context.getString(R.string.subs_error_hwid_required)
                    null ->
                        if (UaHint.shouldSuggest(result.httpStatus, parsedCount = -1, uaIsHappLike)) {
                            context.getString(R.string.subs_error_forbidden_try_happ_ua, result.message)
                        } else {
                            result.message
                        }
                }
                subDao.markError(subId, message)
            }
            is FetchResult.Success -> {
                val outcome = SubscriptionBodyParser.parseBody(result.body)
                val newProfiles = outcome.parsed.map { p ->
                    Profile(
                        name = p.displayName,
                        config = p.config,
                        subscriptionId = subId,
                        sanitizedDns = p.sanitizedDns
                    )
                }

                val activeId = activeProfileIdProvider()
                val keepProfileId = activeId
                    ?.let { profileDao.getById(it) }
                    ?.takeIf { it.subscriptionId == subId }
                    ?.id

                profileDao.replaceProfilesForSubscription(subId, keepProfileId, newProfiles)

                val warning = when {
                    UaHint.shouldSuggest(httpStatus = 200, parsedCount = outcome.parsed.size, uaIsHappLike) ->
                        context.getString(R.string.subs_error_no_servers_try_happ_ua)
                    outcome.parseErrorCount > 0 ->
                        context.getString(R.string.subs_error_parse_lines_prefix, outcome.parseErrorCount)
                    else -> null
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
