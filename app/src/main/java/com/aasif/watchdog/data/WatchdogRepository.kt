package com.aasif.watchdog.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.aasif.watchdog.BuildConfig
import com.aasif.watchdog.core.engine.SubscriptionEngine
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.core.model.Subscription
import com.aasif.watchdog.core.model.SubscriptionStatus
import com.aasif.watchdog.core.parser.SmsParser
import com.aasif.watchdog.data.db.ManualSubscriptionEntity
import com.aasif.watchdog.data.db.UserMarkEntity
import com.aasif.watchdog.data.db.WatchdogDao
import com.aasif.watchdog.data.db.toEntity
import com.aasif.watchdog.data.db.toEvent
import com.aasif.watchdog.data.sms.SmsReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

class WatchdogRepository(
    private val dao: WatchdogDao,
    private val reader: SmsReader,
    private val prefs: SharedPreferences,
) {
    // Debug builds accept SMS from phone numbers so `adb emu sms send` can be used for testing.
    private val parser = SmsParser(allowPersonalSenders = BuildConfig.DEBUG)
    private val engine = SubscriptionEngine()

    var reminderLeadDays: Int
        get() = prefs.getInt(KEY_LEAD_DAYS, 3)
        set(value) = prefs.edit { putInt(KEY_LEAD_DAYS, value) }

    /** Parses new inbox SMS since the last scan. The first scan looks back [FIRST_SCAN_DAYS]. */
    suspend fun scanInbox() {
        val since = prefs.getLong(KEY_LAST_SCAN, -1L).takeIf { it >= 0 }
            ?: LocalDate.now().minusDays(FIRST_SCAN_DAYS).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val (messages, newest) = reader.readSince(since)
        ingest(messages)
        prefs.edit { putLong(KEY_LAST_SCAN, newest) }
    }

    /** Stores the payment SMS among [messages]; returns true if any were found. */
    suspend fun ingest(messages: List<Sms>): Boolean {
        val entities = messages.mapNotNull { sms ->
            parser.parse(sms)?.toEntity(smsKey(sms))
        }
        if (entities.isNotEmpty()) dao.insertEvents(entities)
        return entities.isNotEmpty()
    }

    /** All subscriptions, recomputed whenever events, manual entries or user marks change. */
    fun subscriptions(): Flow<List<Subscription>> =
        combine(dao.events(), dao.manual(), dao.marks()) { events, manual, marks ->
            val today = LocalDate.now()
            val detected = engine.build(events.mapNotNull { it.toEvent() }, today)
            val added = manual.map {
                engine.manual(
                    key = manualKey(it.id), name = it.name, amount = BigDecimal(it.amount),
                    frequency = Frequency.valueOf(it.frequency), firstCharge = LocalDate.parse(it.firstCharge),
                    isTrial = it.isTrial, today = today,
                )
            }
            val markByKey = marks.associate { it.subscriptionKey to it.mark }
            (detected + added)
                .filter { markByKey[it.key] != UserMarkEntity.HIDDEN }
                .map { if (markByKey[it.key] == UserMarkEntity.CANCELLED) it.copy(status = SubscriptionStatus.CANCELLED, nextRenewal = null) else it }
                .sortedWith(compareBy<Subscription> { !it.isLive }.thenBy { it.nextRenewal ?: LocalDate.MAX })
        }

    suspend fun currentSubscriptions(): List<Subscription> = subscriptions().first()

    suspend fun markCancelled(key: String) = dao.setMark(UserMarkEntity(key, UserMarkEntity.CANCELLED))

    /** "Not a subscription": hide it for good. */
    suspend fun hide(key: String) = dao.setMark(UserMarkEntity(key, UserMarkEntity.HIDDEN))

    suspend fun clearMark(key: String) = dao.clearMark(key)

    suspend fun addManual(name: String, amount: BigDecimal, frequency: Frequency, firstCharge: LocalDate, isTrial: Boolean) =
        dao.insertManual(ManualSubscriptionEntity(name = name, amount = amount.toPlainString(), frequency = frequency.name, firstCharge = firstCharge.toString(), isTrial = isTrial))

    suspend fun deleteManual(key: String) {
        key.removePrefix(MANUAL_PREFIX).toLongOrNull()?.let { dao.deleteManual(it) }
    }

    fun isManual(key: String) = key.startsWith(MANUAL_PREFIX)

    /** Remembers which reminders were sent so each renewal is announced once. */
    fun wasNotified(key: String, date: LocalDate) = prefs.getBoolean("notified:$key:$date", false)
    fun setNotified(key: String, date: LocalDate) = prefs.edit { putBoolean("notified:$key:$date", true) }

    private fun smsKey(sms: Sms) = "${sms.sender}|${sms.receivedAt.toLocalDate()}|${sms.body.hashCode()}"
    private fun manualKey(id: Long) = "$MANUAL_PREFIX$id"

    companion object {
        private const val KEY_LAST_SCAN = "last_scan_millis"
        private const val KEY_LEAD_DAYS = "reminder_lead_days"
        private const val FIRST_SCAN_DAYS = 400L // just over a year, enough to see yearly plans renew once
        private const val MANUAL_PREFIX = "manual:"

        fun create(context: Context, dao: WatchdogDao) = WatchdogRepository(
            dao, SmsReader(context), context.getSharedPreferences("watchdog", Context.MODE_PRIVATE),
        )
    }
}
