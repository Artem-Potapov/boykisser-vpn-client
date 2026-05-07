package com.justme.xtls_core_proxy.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscriptions")
data class Subscription(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val userAgentOverride: String? = null,
    val allowInsecureTls: Boolean = false,
    val userIntervalHours: Int? = null,
    val lastSeenIntervalHours: Int? = null,
    val lastFetchedAt: Long? = null,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun effectiveIntervalHours(): Int =
        userIntervalHours ?: lastSeenIntervalHours ?: DEFAULT_INTERVAL_HOURS

    companion object {
        const val DEFAULT_INTERVAL_HOURS: Int = 12
    }
}
