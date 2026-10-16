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

    /** Only the rows a new event could duplicate, so ingest never loads the whole table. */
    @Query("SELECT * FROM events WHERE merchantId = :merchantId")
    suspend fun eventsForMerchant(merchantId: String): List<EventEntity>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsage(rows: List<AppUsageDayEntity>)

    @Query("SELECT * FROM app_usage_daily WHERE packageName IN (:packages) AND day >= :fromDay")
    suspend fun usageSince(packages: List<String>, fromDay: String): List<AppUsageDayEntity>

    @Query("DELETE FROM app_usage_daily")
    suspend fun clearUsage()

    @Query("DELETE FROM app_usage_daily WHERE day < :beforeDay")
    suspend fun pruneUsage(beforeDay: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSpans(rows: List<AppUsageSpanEntity>)

    @Query("SELECT * FROM app_usage_span WHERE packageName IN (:packages) AND endMillis >= :fromMillis")
    suspend fun spansSince(packages: List<String>, fromMillis: Long): List<AppUsageSpanEntity>

    @Query("DELETE FROM app_usage_span")
    suspend fun clearSpans()

    @Query("DELETE FROM app_usage_daily WHERE packageName IN (:packages)")
    suspend fun clearUsageFor(packages: List<String>)

    @Query("DELETE FROM app_usage_span WHERE packageName IN (:packages)")
    suspend fun clearSpansFor(packages: List<String>)

    @Query("SELECT DISTINCT packageName FROM app_usage_span")
    suspend fun spanPackages(): List<String>

    @Query("DELETE FROM app_usage_span WHERE endMillis < :beforeMillis")
    suspend fun pruneSpans(beforeMillis: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setUserFact(fact: UserFactEntity)

    @Query("DELETE FROM user_facts WHERE subscriptionKey = :key")
    suspend fun clearUserFact(key: String)

    @Query("SELECT * FROM user_facts")
    fun userFacts(): Flow<List<UserFactEntity>>

    /** Switching Gmail accounts re-imports from scratch; SMS and notification events are untouched. */
    @Query("DELETE FROM events WHERE source LIKE 'gmail:%'")
    suspend fun clearGmailEvents()

    /** One account's mail events, tagged `gmail:<account>|<sender>`; compared by prefix so `_` in an address is not a wildcard. */
    @Query("DELETE FROM events WHERE substr(source, 1, length('gmail:' || :account || '|')) = 'gmail:' || :account || '|'")
    suspend fun clearGmailEventsFor(account: String)

    /**
     * Drops everything read from SMS (`sms:` tags, plus older rows that stored a bare sender) while
     * leaving mail and payment-app notification events alone. Used to re-derive SMS under changed
     * parsing rules, and to discard SMS data in builds that no longer read SMS. `insertEvents`
     * is IGNORE-on-conflict by [smsKey][EventEntity.smsKey], so re-parsing the same SMS without
     * clearing its old row first would keep the stale classification forever.
     */
    @Query("DELETE FROM events WHERE source NOT LIKE 'gmail:%' AND source NOT LIKE 'app:%'")
    suspend fun clearSmsEvents()
}
