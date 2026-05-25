package com.justme.xtls_core_proxy.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY id ASC")
    fun getAll(): Flow<List<Profile>>

    @Query("SELECT * FROM profiles WHERE subscriptionId IS NULL ORDER BY id ASC")
    fun getManual(): Flow<List<Profile>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: Long): Profile?

    @Query("SELECT * FROM profiles ORDER BY id ASC LIMIT 1")
    suspend fun getFirst(): Profile?

    @Insert
    suspend fun insert(profile: Profile): Long

    @Insert
    suspend fun insertAll(profiles: List<Profile>)

    @Update
    suspend fun update(profile: Profile)

    @Delete
    suspend fun delete(profile: Profile)

    @Query("DELETE FROM profiles WHERE subscriptionId = :subId")
    suspend fun deleteForSub(subId: Long)

    @Query("DELETE FROM profiles WHERE subscriptionId = :subId AND id != :keepId")
    suspend fun deleteForSubExceptId(subId: Long, keepId: Long)

    @Transaction
    suspend fun replaceProfilesForSubscription(
        subId: Long,
        keepProfileId: Long?,
        newProfiles: List<Profile>
    ) {
        if (keepProfileId != null) {
            deleteForSubExceptId(subId, keepProfileId)
        } else {
            deleteForSub(subId)
        }
        if (newProfiles.isNotEmpty()) {
            insertAll(newProfiles.map { it.copy(subscriptionId = subId) })
        }
    }
}
