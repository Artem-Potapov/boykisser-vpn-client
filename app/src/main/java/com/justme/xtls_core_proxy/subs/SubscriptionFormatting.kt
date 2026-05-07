package com.justme.xtls_core_proxy.subs

import com.justme.xtls_core_proxy.db.Subscription
import java.util.concurrent.TimeUnit

object SubscriptionFormatting {

    fun lastSeenSummary(sub: Subscription, now: Long = System.currentTimeMillis()): String {
        val last = sub.lastFetchedAt
        val updatedText = if (last == null) "Never updated" else "Updated ${relativeTime(now - last)}"
        val intervalText = "every ${sub.effectiveIntervalHours()}h"
        val errorText = sub.lastError?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        return "$updatedText · $intervalText$errorText"
    }

    fun relativeTime(deltaMs: Long): String {
        if (deltaMs < 0) return "just now"
        val seconds = TimeUnit.MILLISECONDS.toSeconds(deltaMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(deltaMs)
        val hours = TimeUnit.MILLISECONDS.toHours(deltaMs)
        val days = TimeUnit.MILLISECONDS.toDays(deltaMs)
        return when {
            seconds < 60 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 30 -> "${days}d ago"
            else -> "${days / 30}mo ago"
        }
    }
}
