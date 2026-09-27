package com.aasif.watchdog.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchdogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvents(events: List<EventEntity>)

    @Query("SELECT * FROM events")
    fun events(): Flow<List<EventEntity>>

    @Insert
    suspend fun insertManual(entry: ManualSubscriptionEntity)

    @Query("DELETE FROM manual_subscriptions WHERE id = :id")
    suspend fun deleteManual(id: Long)

    @Query("SELECT * FROM manual_subscriptions")
    fun manual(): Flow<List<ManualSubscriptionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setMark(mark: UserMarkEntity)

    @Query("DELETE FROM user_marks WHERE subscriptionKey = :key")
    suspend fun clearMark(key: String)

    @Query("SELECT * FROM user_marks")
    fun marks(): Flow<List<UserMarkEntity>>
}
