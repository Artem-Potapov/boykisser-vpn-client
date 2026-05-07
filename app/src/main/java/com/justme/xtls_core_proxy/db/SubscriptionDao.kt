package com.justme.xtls_core_proxy.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY createdAt ASC")
    fun getAll(): Flow<List<Subscription>>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun getById(id: Long): Subscription?

    @Insert
    suspend fun insert(subscription: Subscription): Long

    @Update
    suspend fun update(subscription: Subscription)

    @Delete
    suspend fun delete(subscription: Subscription)

    @Query(
        "UPDATE subscriptions SET lastFetchedAt = :lastFetchedAt, " +
            "lastSeenIntervalHours = :lastSeenIntervalHours, lastError = :lastError WHERE id = :id"
    )
    suspend fun markFetchResult(
        id: Long,
        lastFetchedAt: Long,
        lastSeenIntervalHours: Int?,
        lastError: String?
    )

    @Query("UPDATE subscriptions SET lastError = :lastError WHERE id = :id")
    suspend fun markError(id: Long, lastError: String)
}
