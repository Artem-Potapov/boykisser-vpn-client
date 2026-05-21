package com.justme.xtls_core_proxy.subs

import android.content.Context
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.db.Subscription
import java.util.concurrent.TimeUnit

object SubscriptionFormatting {

    fun lastSeenSummary(context: Context, sub: Subscription, now: Long = System.currentTimeMillis()): String {
        val last = sub.lastFetchedAt
        val updatedText = if (last == null) {
            context.getString(R.string.subs_status_never_updated)
        } else {
            context.getString(R.string.subs_status_updated, relativeTime(context, now - last))
        }
        val intervalText = context.getString(R.string.subs_status_interval, sub.effectiveIntervalHours())
        val errorText = sub.lastError?.takeIf { it.isNotBlank() }?.let {
            context.getString(R.string.subs_status_error_suffix, it)
        }.orEmpty()
        return "$updatedText · $intervalText$errorText"
    }

    fun relativeTime(context: Context, deltaMs: Long): String {
        if (deltaMs < 0) return context.getString(R.string.subs_time_just_now)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(deltaMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(deltaMs)
        val hours = TimeUnit.MILLISECONDS.toHours(deltaMs)
        val days = TimeUnit.MILLISECONDS.toDays(deltaMs)
        return when {
            seconds < 60 -> context.getString(R.string.subs_time_just_now)
            minutes < 60 -> context.getString(R.string.subs_time_minutes_ago, minutes)
            hours < 24 -> context.getString(R.string.subs_time_hours_ago, hours)
            days < 30 -> context.getString(R.string.subs_time_days_ago, days)
            else -> context.getString(R.string.subs_time_months_ago, days / 30)
        }
    }
}
