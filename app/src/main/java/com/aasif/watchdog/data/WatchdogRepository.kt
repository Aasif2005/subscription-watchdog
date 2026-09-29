package com.aasif.watchdog.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.aasif.watchdog.BuildConfig
import com.aasif.watchdog.core.engine.SubscriptionEngine
import com.aasif.watchdog.core.engine.isLikelyDuplicateOf
import com.aasif.watchdog.core.gmail.GmailQueryBuilder
import com.aasif.watchdog.core.model.Confidence
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.RenewalSource
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.core.model.Subscription
import com.aasif.watchdog.core.model.SubscriptionStatus
import com.aasif.watchdog.core.parser.SmsParser
import com.aasif.watchdog.data.db.EventEntity
import com.aasif.watchdog.data.db.ManualSubscriptionEntity
import com.aasif.watchdog.data.db.UserFactEntity
import com.aasif.watchdog.data.db.UserMarkEntity
import com.aasif.watchdog.data.db.WatchdogDao
import com.aasif.watchdog.data.db.toEntity
import com.aasif.watchdog.data.db.toEvent
import com.aasif.watchdog.data.gmail.GmailApiClient
import com.aasif.watchdog.data.gmail.GmailAuth
import com.aasif.watchdog.data.gmail.GmailAuthManager
import com.aasif.watchdog.data.gmail.GmailFetch
import com.aasif.watchdog.data.sms.SmsReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** What a Gmail sync run ended up doing, so the worker knows whether to come back. */
enum class GmailSyncOutcome {
    DONE,

    /** History is not fully covered yet; run again soon rather than waiting for tomorrow. */
    MORE,
    NEEDS_CONSENT,
    RETRY,
}

/** Where a charge was spotted, and when the alert arrived. */
data class ChargeSource(val label: String, val atMillis: Long)

class WatchdogRepository(
    private val dao: WatchdogDao,
    private val reader: SmsReader,
    private val prefs: SharedPreferences,
    val gmailAuth: GmailAuthManager,
    private val gmailApi: GmailApiClient,
) {
    // Debug builds accept SMS from phone numbers so `adb emu sms send` can be used for testing.
    private val parser = SmsParser(allowPersonalSenders = BuildConfig.DEBUG)

    /** Mail needs the stricter reading; see [SmsParser]'s `fromEmail`. */
    private val mailParser = SmsParser(fromEmail = true)
    private val engine = SubscriptionEngine()

    var reminderLeadDays: Int
        get() = prefs.getInt(KEY_LEAD_DAYS, 3)
        set(value) = prefs.edit { putInt(KEY_LEAD_DAYS, value) }

    /** Off until the user asks for it: this is the one feature that uses the network. */
    var gmailImportEnabled: Boolean
        get() = prefs.getBoolean(KEY_GMAIL_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_GMAIL_ENABLED, value) }

    /** Local record of which Google account is connected, for the profile page. Never sent anywhere. */
    val gmailAccountEmail: String? get() = prefs.getString(KEY_GMAIL_ACCOUNT, null)

    fun rememberGmailAccount(email: String?) {
        if (email != null) prefs.edit { putString(KEY_GMAIL_ACCOUNT, email) }
    }

    /** True once a sync has come back needing the scope approved again — the case the dashboard's
     *  import card used to go silent on, since [gmailImportEnabled] alone can't tell them apart. */
    var gmailNeedsConsent: Boolean
        get() = prefs.getBoolean(KEY_GMAIL_NEEDS_CONSENT, false)
        set(value) = prefs.edit { putBoolean(KEY_GMAIL_NEEDS_CONSENT, value) }

    /** Parses new inbox SMS since the last scan. The first scan looks back [FIRST_SCAN_DAYS]. */
    suspend fun scanInbox() {
        val since = prefs.getLong(KEY_LAST_SCAN, -1L).takeIf { it >= 0 }
            ?: LocalDate.now().minusDays(FIRST_SCAN_DAYS).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val (messages, newest) = reader.readSince(since)
        ingest(messages, EventSource.SMS)
        prefs.edit { putLong(KEY_LAST_SCAN, newest) }
    }

    /**
     * Reads new subscription mail, then continues walking back through older mail.
     *
     * Both cursors only move on a clean run, so a rate-limited sync re-reads its window rather
     * than stepping over it. Returns [GmailSyncOutcome.MORE] while history is still being covered.
     */
    suspend fun syncGmail(): GmailSyncOutcome {
        if (!gmailImportEnabled) return GmailSyncOutcome.DONE
        val token = when (val auth = gmailAuth.authorize()) {
            is GmailAuth.Granted -> { gmailNeedsConsent = false; rememberGmailAccount(auth.accountEmail); auth.accessToken }
            // A worker cannot show the consent screen; the dashboard card asks again instead.
            is GmailAuth.NeedsConsent, GmailAuth.Unavailable -> { gmailNeedsConsent = true; return GmailSyncOutcome.NEEDS_CONSENT }
        }
        val now = System.currentTimeMillis()

        // Anything that arrived since the last run. The first run only starts the cursor, because
        // everything before it is the backfill's job.
        val lastSync = prefs.getLong(KEY_GMAIL_SYNC, -1L)
        if (lastSync < 0) {
            prefs.edit { putLong(KEY_GMAIL_SYNC, now) }
        } else {
            when (val fetched = gmailApi.fetch(token, GmailQueryBuilder.build(lastSync / 1000), MAX_GMAIL_MESSAGES)) {
                is GmailFetch.Ok -> {
                    ingest(fetched.messages, EventSource.GMAIL)
                    // A throttled run only read the newest part of the window, so the cursor stays
                    // put and the next run re-reads it; the duplicate check absorbs the overlap.
                    if (!fetched.throttled) prefs.edit { putLong(KEY_GMAIL_SYNC, now) }
                }
                GmailFetch.RateLimited -> return GmailSyncOutcome.RETRY
                GmailFetch.AuthRevoked -> return GmailSyncOutcome.NEEDS_CONSENT
                GmailFetch.Failed -> return GmailSyncOutcome.DONE
            }
        }
        return backfillStep(token, now)
    }

    /**
     * One slice of history, newest slice first.
     *
     * Gmail lists newest first, so a capped run can only ever see the newest part of a window.
     * Each run therefore pulls the newest uncovered slice and moves the frontier back to the
     * oldest message it saw; repeated runs eventually reach [FIRST_SCAN_DAYS].
     */
    private suspend fun backfillStep(token: String, now: Long): GmailSyncOutcome {
        val frontier = prefs.getLong(KEY_GMAIL_BACKFILL, -1L)
        if (frontier == BACKFILL_DONE) return GmailSyncOutcome.DONE
        val before = if (frontier > 0) frontier else now
        val start = LocalDate.now().minusDays(FIRST_SCAN_DAYS)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // The frontier is forced to move, so this is only a stop against a slice that somehow
        // cannot make progress; the limit is far beyond any believable volume of payment mail.
        val slice = prefs.getInt(KEY_GMAIL_SLICES, 0)
        if (before <= start || slice >= MAX_BACKFILL_SLICES) return finishBackfill()

        return when (val fetched = gmailApi.fetch(token, GmailQueryBuilder.build(start / 1000, before / 1000), BACKFILL_SLICE)) {
            is GmailFetch.Ok -> {
                // Counted here rather than before the fetch: a run that read nothing has not used
                // up a slice, and counting attempts instead of progress let a spell of throttling
                // burn the whole budget and declare the backfill finished having imported nothing.
                prefs.edit { putInt(KEY_GMAIL_SLICES, slice + 1) }
                val stored = ingest(fetched.messages, EventSource.GMAIL)
                if (BuildConfig.DEBUG) {
                    Log.i("GmailSync", "backfill slice=$slice stored=$stored msgs=${fetched.messages.size} exhausted=${fetched.exhausted} throttled=${fetched.throttled}")
                }
                // Forced to strictly decrease, so a window Gmail rounds to whole days still ends.
                val next = fetched.oldestScannedMillis?.let { minOf(it, before - 1_000L) }
                if (fetched.exhausted || next == null || next <= start) finishBackfill()
                else {
                    prefs.edit { putLong(KEY_GMAIL_BACKFILL, next) }
                    GmailSyncOutcome.MORE
                }
            }
            GmailFetch.RateLimited -> logOutcome(GmailSyncOutcome.RETRY)
            GmailFetch.AuthRevoked -> logOutcome(GmailSyncOutcome.NEEDS_CONSENT)
            GmailFetch.Failed -> logOutcome(GmailSyncOutcome.DONE)
        }
    }

    private fun logOutcome(outcome: GmailSyncOutcome): GmailSyncOutcome {
        if (BuildConfig.DEBUG) Log.w("GmailSync", "backfill ended early: $outcome")
        return outcome
    }

    private fun finishBackfill(): GmailSyncOutcome {
        prefs.edit { putLong(KEY_GMAIL_BACKFILL, BACKFILL_DONE) }
        return GmailSyncOutcome.DONE
    }

    /**
     * Drops the connection. The Authorization API has no revoke method (see [GmailAuthManager]),
     * so this clears what the app remembers locally and asks Google's token endpoint to end the
     * grant itself; either step can be the one that actually matters, so both run. Gmail-sourced
     * events and cursors go; SMS and notification events are untouched.
     */
    suspend fun disconnectGmail() {
        val token = (gmailAuth.authorize() as? GmailAuth.Granted)?.accessToken
        if (token != null) gmailApi.revoke(token)
        gmailImportEnabled = false
        gmailNeedsConsent = false
        clearGmailCursors()
        prefs.edit { remove(KEY_GMAIL_ACCOUNT) }
        dao.clearGmailEvents()
    }

    /**
     * Starts fresh against a different Google account. Its history has never been read from this
     * phone, so the cursors reset the same way they were on the very first connect.
     *
     * A brand-new account has never approved this app, so Play Services cannot grant this
     * silently — it comes back [GmailAuth.NeedsConsent] and the actual grant only lands later,
     * out of [GmailAuthManager.fromConsentResult] after the consent screen. [finishAccountSwitch]
     * is what applies the reset in that case; skipping it left the previous account's sync cursor
     * in place, so a switch to a new account "succeeded" but silently synced nothing.
     */
    suspend fun switchGmailAccount(accountName: String): GmailAuth {
        val auth = gmailAuth.authorize(gmailAuth.account(accountName))
        if (auth is GmailAuth.Granted) finishAccountSwitch(auth, accountName)
        return auth
    }

    /** Completes a switch once the token is actually granted, whichever path produced it. */
    suspend fun finishAccountSwitch(auth: GmailAuth.Granted, accountName: String) {
        gmailNeedsConsent = false
        rememberGmailAccount(auth.accountEmail ?: accountName)
        clearGmailCursors()
        dao.clearGmailEvents()
        gmailImportEnabled = true
    }

    private fun clearGmailCursors() = prefs.edit {
        remove(KEY_GMAIL_SYNC)
        remove(KEY_GMAIL_BACKFILL)
        remove(KEY_GMAIL_SLICES)
    }

    /**
     * Stores the payment messages among [messages]; returns true if any were new.
     *
     * A charge announced by SMS, by the bank's app and by e-mail is one charge, so anything that
     * matches an event already held for that merchant is dropped rather than stored again.
     */
    suspend fun ingest(messages: List<Sms>, channel: String): Boolean {
        val entities = mutableListOf<EventEntity>()
        val knownByMerchant = mutableMapOf<String, MutableList<PaymentEvent>>()
        val sourceParser = if (channel == EventSource.GMAIL) mailParser else parser
        for (sms in messages) {
            val event = sourceParser.parse(sms) ?: continue
            val known = knownByMerchant.getOrPut(event.merchantId) {
                dao.eventsForMerchant(event.merchantId).mapNotNull { it.toEvent() }.toMutableList()
            }
            if (known.any { event.isLikelyDuplicateOf(it) }) continue
            known += event
            entities += event.toEntity(smsKey(sms), EventSource.of(channel, sms.sender), sms.receivedAt.toMillis())
        }
        if (entities.isNotEmpty()) dao.insertEvents(entities)
        return entities.isNotEmpty()
    }

    /** Where each charge of a merchant was spotted, keyed by [chargeKey]. */
    suspend fun chargeSources(merchantId: String): Map<String, ChargeSource> =
        dao.eventsForMerchant(merchantId).mapNotNull { row ->
            val debit = row.toEvent() as? PaymentEvent.Debit ?: return@mapNotNull null
            chargeKey(debit.date, debit.amount) to ChargeSource(EventSource.label(row.source), row.receivedAtMillis)
        }.toMap()

    /** All subscriptions, recomputed whenever events, manual entries, user marks or facts change. */
    fun subscriptions(): Flow<List<Subscription>> =
        combine(dao.events(), dao.manual(), dao.marks(), dao.userFacts()) { events, manual, marks, facts ->
            val today = LocalDate.now()
            val detected = engine.build(events.mapNotNull { it.toEvent() }, today)
            val added = manual.map {
                engine.manual(
                    key = manualKey(it.id), name = it.name, amount = Money(BigDecimal(it.amount)),
                    frequency = Frequency.valueOf(it.frequency), firstCharge = LocalDate.parse(it.firstCharge),
                    isTrial = it.isTrial, today = today,
                )
            }
            val markByKey = marks.associate { it.subscriptionKey to it.mark }
            val factByKey = facts.filterNot { it.dismissed }.associateBy { it.subscriptionKey }
            (detected + added)
                .filter { markByKey[it.key] != UserMarkEntity.HIDDEN }
                .map { if (markByKey[it.key] == UserMarkEntity.CANCELLED) it.copy(status = SubscriptionStatus.CANCELLED, nextRenewal = null) else it }
                .map { sub -> factByKey[sub.key]?.let { applyUserFact(sub, it) } ?: sub }
                .sortedWith(compareBy<Subscription> { !it.isLive }.thenBy { it.nextRenewal ?: LocalDate.MAX })
        }

    /**
     * What the user typed on the "Needs your input" card beats every guess — it is the only place
     * a date like a NordVPN renewal, which lives inside that app and nowhere else on the phone,
     * can ever come from.
     */
    private fun applyUserFact(sub: Subscription, fact: UserFactEntity): Subscription = sub.copy(
        amount = fact.amount?.let { Money(BigDecimal(it), fact.currency ?: sub.amount.currency) } ?: sub.amount,
        amountKnown = sub.amountKnown || fact.amount != null,
        frequency = fact.frequency?.let(Frequency::valueOf) ?: sub.frequency,
        frequencyKnown = sub.frequencyKnown || fact.frequency != null,
        nextRenewal = fact.nextRenewal?.let(LocalDate::parse) ?: sub.nextRenewal,
        renewalSource = if (fact.nextRenewal != null) RenewalSource.USER_SUPPLIED else sub.renewalSource,
        confidence = if (fact.nextRenewal != null) Confidence.HIGH else sub.confidence,
    )

    /** Saves what the user typed for a subscription the app couldn't fully work out on its own. */
    suspend fun setUserFact(key: String, frequency: Frequency?, nextRenewal: LocalDate?, amount: BigDecimal?, currency: String?) =
        dao.setUserFact(UserFactEntity(key, frequency?.name, nextRenewal?.toString(), amount?.toPlainString(), currency))

    /** "I don't know this either, and don't want to be asked again" — distinct from hiding it. */
    suspend fun dismissUserFact(key: String) = dao.setUserFact(UserFactEntity(key, null, null, null, null, dismissed = true))

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

    private fun LocalDateTime.toMillis() =
        atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    companion object {
        private const val KEY_LAST_SCAN = "last_scan_millis"
        private const val KEY_GMAIL_SYNC = "last_gmail_sync_millis"
        private const val KEY_GMAIL_BACKFILL = "gmail_backfill_before_millis"
        private const val KEY_GMAIL_SLICES = "gmail_backfill_slices"
        private const val KEY_GMAIL_ENABLED = "gmail_import_enabled"
        private const val KEY_GMAIL_ACCOUNT = "gmail_account_email"
        private const val KEY_GMAIL_NEEDS_CONSENT = "gmail_needs_consent"

        /** Frontier value meaning the whole [FIRST_SCAN_DAYS] window has been covered. */
        private const val BACKFILL_DONE = 0L

        /** 120 slices of [BACKFILL_SLICE] is far more payment mail than a year can hold. */
        private const val MAX_BACKFILL_SLICES = 120
        private const val KEY_LEAD_DAYS = "reminder_lead_days"
        private const val FIRST_SCAN_DAYS = 400L // just over a year, enough to see yearly plans renew once
        private const val MANUAL_PREFIX = "manual:"

        /** A capped run is safe to repeat: the duplicate check absorbs whatever it re-reads. */
        private const val MAX_GMAIL_MESSAGES = 500

        /**
         * Gmail's per-minute quota stops a full-format read at roughly a hundred messages, so a
         * slice is sized to finish inside one window rather than to be as large as possible.
         * Measured on a real inbox: ~100 reads in ~40s, then `totalQueryCostPerMinutePerUser`.
         */
        private const val BACKFILL_SLICE = 75

        /** Amount scale differs between sources, so the pair is keyed as text, not BigDecimal. */
        fun chargeKey(date: LocalDate, amount: Money) =
            "$date|${amount.currency}|${amount.amount.stripTrailingZeros().toPlainString()}"

        fun create(context: Context, dao: WatchdogDao) = WatchdogRepository(
            dao, SmsReader(context), context.getSharedPreferences("watchdog", Context.MODE_PRIVATE),
            GmailAuthManager(context.applicationContext), GmailApiClient(),
        )
    }
}
