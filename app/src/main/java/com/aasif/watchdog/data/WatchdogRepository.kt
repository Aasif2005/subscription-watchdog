package com.aasif.watchdog.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.aasif.watchdog.BuildConfig
import com.aasif.watchdog.core.engine.ReminderAnswer
import com.aasif.watchdog.core.engine.ReminderState
import com.aasif.watchdog.core.engine.SourcedEvent
import com.aasif.watchdog.core.engine.StampedEvent
import com.aasif.watchdog.core.engine.SubscriptionEngine
import com.aasif.watchdog.core.engine.isDuplicateOfAny
import com.aasif.watchdog.core.engine.nameChannelEvents
import com.aasif.watchdog.core.engine.channelRekeyTarget
import com.aasif.watchdog.core.engine.stoppedRenewing
import com.aasif.watchdog.core.engine.withoutEndedChannelPlans
import com.aasif.watchdog.core.engine.worthKeepingFromSms
import com.aasif.watchdog.core.gmail.CatchUpCursor
import com.aasif.watchdog.core.gmail.CatchUpStep
import com.aasif.watchdog.core.gmail.GmailQueryBuilder
import com.aasif.watchdog.core.gmail.next
import com.aasif.watchdog.core.merchant.MerchantCatalog
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
import com.aasif.watchdog.data.gmail.MailAudit
import com.aasif.watchdog.data.sms.SmsAudit
import com.aasif.watchdog.data.sms.SmsReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** How many notifications an app has posted that the listener looked at, and how many yielded a payment. */
data class NotificationStat(val packageName: String, val seen: Int, val found: Int, val lastSeenMillis: Long)

/** What a Gmail sync run ended up doing, so the worker knows whether to come back. */
enum class GmailSyncOutcome {
    DONE,

    /** History is not fully covered yet; run again straight away, Gmail did not push back. */
    MORE,

    /** Like [MORE], but Gmail throttled this run, so the next one should wait out the quota window. */
    MORE_AFTER_THROTTLE,
    NEEDS_CONSENT,
    RETRY,
}

/** What the profile page shows for one connected Google account. */
data class GmailAccountStatus(val email: String, val needsConsent: Boolean, val importing: Boolean)

/** One thing the app saw that supports a subscription existing: what it was, where, and when. */
data class EvidenceItem(
    val kind: String,
    val amount: Money?,
    val source: String,
    val atMillis: Long,
    val date: LocalDate,
)

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

    /**
     * The Google accounts whose mail is read, kept locally for the profile page and never sent
     * anywhere. Every account has its own cursors and its own consent, so subscriptions billed
     * to one account are not hidden by connecting another.
     */
    val gmailAccounts: List<String>
        get() = prefs.getStringSet(KEY_GMAIL_ACCOUNTS, null)?.sorted()
            // Installs from before several accounts could be connected only stored the one.
            ?: listOfNotNull(prefs.getString(KEY_GMAIL_ACCOUNT, null))

    /**
     * True once a connected account's sync has come back needing the scope approved again. Only
     * accounts still connected count: a sync that was running while its account was removed can
     * flag it after the removal, and that flag must not keep the reconnect card on screen.
     */
    val gmailNeedsConsent: Boolean
        get() = gmailAccounts.any { it in prefs.getStringSet(KEY_GMAIL_CONSENT, null).orEmpty() }

    private fun setNeedsConsent(account: String, needs: Boolean) {
        if (needs && account !in gmailAccounts) return
        val current = prefs.getStringSet(KEY_GMAIL_CONSENT, null).orEmpty()
        val updated = if (needs) current + account else current - account
        if (updated != current) prefs.edit(commit = true) { putStringSet(KEY_GMAIL_CONSENT, updated) }
    }

    /**
     * False while any account's [backfillStep] still has history left. The import card
     * disappearing the moment [gmailImportEnabled] turns on (deliberately, so it doesn't get stuck
     * telling a connected user to connect) otherwise leaves a multi-minute backfill
     * indistinguishable from a stalled one. This is what the dashboard's "still importing" card
     * keys off.
     */
    val gmailBackfillComplete: Boolean
        get() = gmailAccounts.all { backfillDone(it) }

    private fun backfillDone(account: String) = prefs.getLong(key(KEY_GMAIL_BACKFILL, account), -1L) == BACKFILL_DONE

    /** One row of the profile page: an account and what the import is doing for it. */
    fun gmailAccountStatuses(): List<GmailAccountStatus> {
        val needing = prefs.getStringSet(KEY_GMAIL_CONSENT, null).orEmpty()
        return gmailAccounts.map { GmailAccountStatus(it, needsConsent = it in needing, importing = !backfillDone(it)) }
    }

    /** True when the mail rules changed since the last import, so the next sync starts over. */
    val gmailNeedsReread: Boolean
        get() = prefs.getInt(KEY_GMAIL_CLASSIFIER_VERSION, 0) < GMAIL_CLASSIFIER_VERSION

    /** Parses new inbox SMS since the last scan. The first scan looks back [FIRST_SCAN_DAYS]. */
    suspend fun scanInbox() {
        if (!BuildConfig.SMS_ENABLED) return
        reclassifyIfNeeded()
        val since = prefs.getLong(KEY_LAST_SCAN, -1L).takeIf { it >= 0 }
            ?: LocalDate.now().minusDays(FIRST_SCAN_DAYS).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val (messages, newest) = reader.readSince(since)
        ingest(messages, EventSource.SMS)
        prefs.edit { putLong(KEY_LAST_SCAN, newest) }
        rekeyChannelEntries()
    }

    /**
     * Re-derives every SMS event under the current parsing logic, once per app update that changes
     * it. `scanInbox` only reads SMS newer than [KEY_LAST_SCAN] and inserts are IGNORE-on-conflict,
     * so without this a parser or catalog fix would never reach an SMS already scanned. Bump
     * [CLASSIFIER_VERSION] whenever such a change would classify old SMS differently. Mail and
     * notification events are untouched.
     */
    private suspend fun reclassifyIfNeeded() {
        if (prefs.getInt(KEY_CLASSIFIER_VERSION, 0) >= CLASSIFIER_VERSION) return
        dao.clearSmsEvents()
        prefs.edit {
            remove(KEY_LAST_SCAN)
            putInt(KEY_CLASSIFIER_VERSION, CLASSIFIER_VERSION)
        }
    }

    /**
     * Moves names and marks saved under a bare billing-channel key ("googleplay") to the plan they
     * were meant for, now that each app on a channel is its own plan ("googleplay#1999"). Without
     * this the "Claude Pro" a user typed would silently stop applying. Runs until it has placed
     * everything: right after an update the SMS are being read again, and until they are there is
     * no plan to move a name to.
     */
    suspend fun rekeyChannelEntries() {
        if (prefs.getBoolean(KEY_CHANNEL_REKEYED, false)) return
        val channels = MerchantCatalog.all.filter { it.sharedBilling }.map { it.id }.toSet()
        val facts = dao.userFacts().first().filter { it.subscriptionKey in channels }
        val marks = dao.marks().first().filter { it.subscriptionKey in channels }
        var placedAll = true
        if (facts.isNotEmpty() || marks.isNotEmpty()) {
            val subs = engine.build(namedEvents(dao.events().first()).map { it.second }, LocalDate.now())
            for (fact in facts) {
                val target = channelRekeyTarget(fact.subscriptionKey, subs) ?: run { placedAll = false; null } ?: continue
                if (target == fact.subscriptionKey) continue
                dao.setUserFact(fact.copy(subscriptionKey = target))
                dao.clearUserFact(fact.subscriptionKey)
            }
            for (mark in marks) {
                val target = channelRekeyTarget(mark.subscriptionKey, subs) ?: run { placedAll = false; null } ?: continue
                if (target == mark.subscriptionKey) continue
                dao.setMark(mark.copy(subscriptionKey = target))
                dao.clearMark(mark.subscriptionKey)
            }
        }
        if (placedAll) prefs.edit(commit = true) { putBoolean(KEY_CHANNEL_REKEYED, true) }
    }

    /** The Profile page's manual escape hatch: re-derive every SMS event now, then scan again. */
    suspend fun forceFullRescan() {
        dao.clearSmsEvents()
        prefs.edit { remove(KEY_LAST_SCAN) }
    }

    /**
     * In a build that does not read SMS, discards whatever an earlier build collected from it, once.
     * (Google Play restricts the SMS permissions, so the `play` variant ships without them.)
     */
    suspend fun purgeSmsIfUnsupported() {
        if (BuildConfig.SMS_ENABLED || prefs.getBoolean(KEY_SMS_PURGED, false)) return
        dao.clearSmsEvents()
        prefs.edit(commit = true) {
            remove(KEY_LAST_SCAN)
            remove(KEY_CLASSIFIER_VERSION)
            putBoolean(KEY_SMS_PURGED, true)
        }
    }

    /**
     * Mail already imported keeps whatever the old
     * rules made of it, and the backfill never revisits a window it has covered. Bump
     * [GMAIL_CLASSIFIER_VERSION] when mail parsing (or how mail is tagged) changes; the next sync
     * then clears mail-sourced events and walks every account's history again.
     */
    private suspend fun rereadMailIfNeeded() {
        if (prefs.getInt(KEY_GMAIL_CLASSIFIER_VERSION, 0) >= GMAIL_CLASSIFIER_VERSION) return
        val accounts = gmailAccounts
        dao.clearGmailEvents()
        prefs.edit(commit = true) {
            // Persisted now so the one-account layout of older installs is carried over.
            putStringSet(KEY_GMAIL_ACCOUNTS, accounts.toSet())
            for (account in accounts) clearCursors(account)
            remove(KEY_GMAIL_SYNC)
            remove(KEY_GMAIL_BACKFILL)
            remove(KEY_GMAIL_SLICES)
            putInt(KEY_GMAIL_CLASSIFIER_VERSION, GMAIL_CLASSIFIER_VERSION)
        }
    }

    /**
     * Reads new subscription mail for every connected account, then continues walking back
     * through each one's older mail.
     *
     * Both cursors only move on a clean run, so a rate-limited sync re-reads its window rather
     * than stepping over it. Returns [GmailSyncOutcome.MORE] (or [GmailSyncOutcome.MORE_AFTER_THROTTLE]
     * when Gmail pushed back) while any account's history is still being covered. An account that
     * needs consent again only stops itself, not the others.
     */
    suspend fun syncGmail(): GmailSyncOutcome {
        if (!gmailImportEnabled) return GmailSyncOutcome.DONE
        rereadMailIfNeeded()
        var result = GmailSyncOutcome.DONE
        for (account in gmailAccounts) {
            val outcome = syncAccount(account)
            if (outcome.priority > result.priority) result = outcome
        }
        rekeyChannelEntries()
        return result
    }

    private val GmailSyncOutcome.priority: Int
        get() = when (this) {
            GmailSyncOutcome.DONE -> 0
            GmailSyncOutcome.NEEDS_CONSENT -> 1
            GmailSyncOutcome.RETRY -> 2
            GmailSyncOutcome.MORE -> 3
            GmailSyncOutcome.MORE_AFTER_THROTTLE -> 4
        }

    private suspend fun syncAccount(account: String): GmailSyncOutcome {
        var token = when (val auth = gmailAuth.authorize(gmailAuth.account(account))) {
            is GmailAuth.Granted -> { setNeedsConsent(account, false); auth.accessToken }
            // A worker cannot show the consent screen; the dashboard card asks again instead.
            is GmailAuth.NeedsConsent, GmailAuth.Unavailable -> { setNeedsConsent(account, true); return GmailSyncOutcome.NEEDS_CONSENT }
        }
        val now = System.currentTimeMillis()

        // Anything that arrived since the last run. The first run only starts the cursor, because
        // everything before it is the backfill's job.
        val syncKey = key(KEY_GMAIL_SYNC, account)
        val lastSync = prefs.getLong(syncKey, -1L)
        if (lastSync < 0) {
            // commit(), not apply(): this runs inside a background WorkManager job with nothing
            // keeping the process alive afterward, and an async write that loses the race against
            // process death makes the next run re-derive "now" from scratch — see backfillStep's
            // frontier write below, where that exact race was the reason the backfill never
            // advanced past its first slice.
            prefs.edit(commit = true) { putLong(syncKey, now) }
        } else {
            val boundKey = key(KEY_GMAIL_CATCHUP_BEFORE, account)
            val targetKey = key(KEY_GMAIL_CATCHUP_TARGET, account)
            val cursor = CatchUpCursor(
                lastSync,
                upperBound = prefs.getLong(boundKey, -1L).takeIf { it > 0 },
                target = prefs.getLong(targetKey, -1L).takeIf { it > 0 },
            )
            val query = GmailQueryBuilder.build(lastSync / 1000, cursor.upperBound?.let { it / 1000 })
            val (fetched, usedToken) = fetchMail(account, token, query, MAX_GMAIL_MESSAGES)
            token = usedToken
            when (fetched) {
                is GmailFetch.Ok -> {
                    ingest(fetched.messages, EventSource.GMAIL, account)
                    // A throttled or capped run only read the newest part of the window. Restarting
                    // from the newest next time would re-read that part forever once the window
                    // holds more than one run can fetch, so the read walks backwards instead.
                    when (val step = cursor.next(now, fetched.exhausted, fetched.oldestScannedMillis)) {
                        is CatchUpStep.Done -> prefs.edit(commit = true) {
                            putLong(syncKey, step.lastSync)
                            remove(boundKey)
                            remove(targetKey)
                        }
                        is CatchUpStep.Continue -> {
                            prefs.edit(commit = true) {
                                putLong(boundKey, step.upperBound)
                                putLong(targetKey, step.target)
                            }
                            return if (fetched.throttled) GmailSyncOutcome.MORE_AFTER_THROTTLE else GmailSyncOutcome.MORE
                        }
                    }
                }
                GmailFetch.RateLimited -> return GmailSyncOutcome.RETRY
                GmailFetch.AuthRevoked -> return GmailSyncOutcome.NEEDS_CONSENT
                GmailFetch.Failed -> return GmailSyncOutcome.DONE
            }
        }
        val history = backfillStep(account, token, now)
        // Once the history is in, look for "welcome to Premium" mail too: it is what names the
        // app behind a Google Play AutoPay when no receipt does.
        if (BuildConfig.DEBUG && history == GmailSyncOutcome.DONE) auditProbe(account, token)
        return if (history == GmailSyncOutcome.DONE) welcomeStep(account, token, now) else history
    }

    /** Debug only: once per [AUDIT_PROBE_VERSION], see [MailAudit.probe]. */
    private suspend fun auditProbe(account: String, token: String) {
        val probeKey = key("audit_probe_version", account)
        if (prefs.getInt(probeKey, 0) >= AUDIT_PROBE_VERSION) return
        if (MailAudit.probe(gmailApi, token)) prefs.edit(commit = true) { putInt(probeKey, AUDIT_PROBE_VERSION) }
    }

    /**
     * Reads mail from catalogued services saying a plan started, since the last time this ran
     * (the first run looks back as far as the backfill). Such mail is rare, so one capped read
     * per run is enough; a throttled one is simply tried again later.
     */
    private suspend fun welcomeStep(account: String, token: String, now: Long): GmailSyncOutcome {
        val cursorKey = key(KEY_GMAIL_WELCOME, account)
        val after = prefs.getLong(cursorKey, -1L).takeIf { it > 0 }
            ?: LocalDate.now().minusDays(FIRST_SCAN_DAYS).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val query = GmailQueryBuilder.buildSubscriptionStarted(welcomeSenders, after / 1000)
        return when (val fetched = fetchMail(account, token, query, MAX_WELCOME_MESSAGES).first) {
            is GmailFetch.Ok -> {
                ingest(fetched.messages, EventSource.GMAIL, account)
                if (fetched.throttled) GmailSyncOutcome.MORE_AFTER_THROTTLE
                else {
                    prefs.edit(commit = true) { putLong(cursorKey, now) }
                    GmailSyncOutcome.DONE
                }
            }
            GmailFetch.RateLimited -> GmailSyncOutcome.RETRY
            GmailFetch.AuthRevoked -> GmailSyncOutcome.NEEDS_CONSENT
            GmailFetch.Failed -> GmailSyncOutcome.DONE
        }
    }

    /** Every spelling of every subscription service the catalog knows, except billing channels and prepaid telecom. */
    private val welcomeSenders: List<String> =
        MerchantCatalog.all.filter { !it.sharedBilling && !it.autopayOnly }.flatMap { it.aliases }

    /**
     * One slice of one account's history, newest slice first.
     *
     * Gmail lists newest first, so a capped run can only ever see the newest part of a window.
     * Each run therefore pulls the newest uncovered slice and moves the frontier back to the
     * oldest message it saw; repeated runs eventually reach [FIRST_SCAN_DAYS].
     */
    private suspend fun backfillStep(account: String, token: String, now: Long): GmailSyncOutcome {
        val backfillKey = key(KEY_GMAIL_BACKFILL, account)
        val slicesKey = key(KEY_GMAIL_SLICES, account)
        val frontier = prefs.getLong(backfillKey, -1L)
        if (frontier == BACKFILL_DONE) return GmailSyncOutcome.DONE
        val before = if (frontier > 0) frontier else now
        val start = LocalDate.now().minusDays(FIRST_SCAN_DAYS)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // The frontier is forced to move, so this is only a stop against a slice that somehow
        // cannot make progress; the limit is far beyond any believable volume of payment mail.
        val slice = prefs.getInt(slicesKey, 0)
        if (before <= start || slice >= MAX_BACKFILL_SLICES) return finishBackfill(account)

        return when (val fetched = fetchMail(account, token, GmailQueryBuilder.build(start / 1000, before / 1000), BACKFILL_SLICE).first) {
            is GmailFetch.Ok -> {
                // Counted here rather than before the fetch: a run that read nothing has not used
                // up a slice, and counting attempts instead of progress let a spell of throttling
                // burn the whole budget and declare the backfill finished having imported nothing.
                // commit(), not apply() — see the sync cursor comment above.
                prefs.edit(commit = true) { putInt(slicesKey, slice + 1) }
                val stored = ingest(fetched.messages, EventSource.GMAIL, account)
                if (BuildConfig.DEBUG) {
                    Log.i("GmailSync", "backfill $account slice=$slice stored=$stored msgs=${fetched.messages.size} exhausted=${fetched.exhausted} throttled=${fetched.throttled}")
                }
                // Forced to strictly decrease, so a window Gmail rounds to whole days still ends.
                val next = fetched.oldestScannedMillis?.let { minOf(it, before - 1_000L) }
                if (fetched.exhausted || next == null || next <= start) finishBackfill(account)
                else {
                    prefs.edit(commit = true) { putLong(backfillKey, next) }
                    if (fetched.throttled) GmailSyncOutcome.MORE_AFTER_THROTTLE else GmailSyncOutcome.MORE
                }
            }
            GmailFetch.RateLimited -> logOutcome(account, GmailSyncOutcome.RETRY)
            GmailFetch.AuthRevoked -> logOutcome(account, GmailSyncOutcome.NEEDS_CONSENT)
            GmailFetch.Failed -> logOutcome(account, GmailSyncOutcome.DONE)
        }
    }

    /**
     * Play Services caches access tokens, and one that was revoked (or whose grant was reset) is
     * still handed out until it expires, so a 401 is not yet proof the user must approve again.
     * Drop the cached token, ask for a fresh one and retry once; only a second rejection counts.
     * Returns the token it ended up using, so the next call in the same run does not repeat this.
     */
    private suspend fun fetchMail(account: String, token: String, query: String, max: Int): Pair<GmailFetch, String> {
        val first = gmailApi.fetch(token, query, max)
        if (first != GmailFetch.AuthRevoked) return first to token
        gmailAuth.clearToken(token)
        val fresh = (gmailAuth.authorize(gmailAuth.account(account)) as? GmailAuth.Granted)?.accessToken
            ?: return GmailFetch.AuthRevoked to token
        if (BuildConfig.DEBUG) Log.i("GmailSync", "401 for $account, retrying with a fresh token")
        return gmailApi.fetch(fresh, query, max) to fresh
    }

    private fun logOutcome(account: String, outcome: GmailSyncOutcome): GmailSyncOutcome {
        if (BuildConfig.DEBUG) Log.w("GmailSync", "backfill $account ended early: $outcome")
        return outcome
    }

    private fun finishBackfill(account: String): GmailSyncOutcome {
        prefs.edit(commit = true) { putLong(key(KEY_GMAIL_BACKFILL, account), BACKFILL_DONE) }
        return GmailSyncOutcome.DONE
    }

    /**
     * Adds [accountName] to the accounts being read, without touching the others. Its history has
     * never been read from this phone, so only its own cursors start fresh.
     *
     * A brand-new account has never approved this app, so Play Services cannot grant this
     * silently — it comes back [GmailAuth.NeedsConsent] and the actual grant only lands later,
     * out of [GmailAuthManager.fromConsentResult] after the consent screen; [finishAddAccount] is
     * what records it in that case.
     */
    suspend fun addGmailAccount(accountName: String): GmailAuth {
        val auth = gmailAuth.authorize(gmailAuth.account(accountName))
        if (auth is GmailAuth.Granted) finishAddAccount(auth, accountName)
        return auth
    }

    /** Records a granted account, whichever path produced the token; false if it can't be named. */
    suspend fun finishAddAccount(auth: GmailAuth.Granted, accountName: String): Boolean {
        val email = auth.accountEmail ?: accountName
        if (email.isBlank()) return false
        dao.clearGmailEventsFor(email)
        prefs.edit(commit = true) {
            putStringSet(KEY_GMAIL_ACCOUNTS, gmailAccounts.toSet() + email)
            clearCursors(email)
            putBoolean(KEY_GMAIL_ENABLED, true)
        }
        setNeedsConsent(email, false)
        return true
    }

    /**
     * Stops reading one account: asks Google to drop its grant (the Authorization API has no
     * revoke call — see [GmailAuthManager] — so this is the token endpoint plus clearing what the
     * app remembers), and forgets that account's cursors and mail-sourced events. The other
     * accounts and notification events are untouched; removing the last one turns the import off.
     */
    suspend fun removeGmailAccount(email: String) {
        val token = (gmailAuth.authorize(gmailAuth.account(email)) as? GmailAuth.Granted)?.accessToken
        if (token != null) gmailApi.revoke(token)
        val remaining = gmailAccounts - email
        prefs.edit(commit = true) {
            putStringSet(KEY_GMAIL_ACCOUNTS, remaining.toSet())
            // Dropped in the same write as the account, so no sync can re-flag it in between.
            putStringSet(KEY_GMAIL_CONSENT, prefs.getStringSet(KEY_GMAIL_CONSENT, null).orEmpty() - email)
            clearCursors(email)
            if (remaining.isEmpty()) putBoolean(KEY_GMAIL_ENABLED, false)
        }
        dao.clearGmailEventsFor(email)
    }

    private fun key(base: String, account: String) = "$base:$account"

    private fun SharedPreferences.Editor.clearCursors(account: String) {
        remove(key(KEY_GMAIL_SYNC, account))
        remove(key(KEY_GMAIL_BACKFILL, account))
        remove(key(KEY_GMAIL_SLICES, account))
        remove(key(KEY_GMAIL_CATCHUP_BEFORE, account))
        remove(key(KEY_GMAIL_CATCHUP_TARGET, account))
        remove(key(KEY_GMAIL_WELCOME, account))
    }

    /**
     * Stores the payment messages among [messages]; returns true if any were new.
     *
     * A charge announced by the bank's app and by e-mail is one charge, so anything that
     * matches an event already held for that merchant is dropped rather than stored again.
     */
    suspend fun ingest(messages: List<Sms>, channel: String, account: String? = null): Boolean {
        val entities = mutableListOf<EventEntity>()
        val knownByMerchant = mutableMapOf<String, MutableList<StampedEvent>>()
        val sourceParser = if (channel == EventSource.GMAIL) mailParser else parser
        var unparsed = 0
        var notWorth = 0
        var duplicates = 0
        for (sms in messages) {
            val audit = BuildConfig.DEBUG && channel == EventSource.GMAIL
            val smsAudit = BuildConfig.DEBUG && channel != EventSource.GMAIL
            val event = sourceParser.parse(sms) ?: run {
                if (audit) MailAudit.parsed(sms, "parser-null", mailParser.assessMail(sms))
                if (smsAudit) { unparsed++; SmsAudit.message(sms, channel, "parser-null") }
                null
            } ?: continue
            // Payment-app notifications announce every payment ("You paid ... to ..."), exactly as
            // bank SMS does, so they get the same rule. Mail is the exception: a receipt is the point.
            if (channel != EventSource.GMAIL && !event.worthKeepingFromSms()) {
                if (smsAudit) { notWorth++; SmsAudit.message(sms, channel, "not-worth-keeping:${event::class.simpleName}") }
                continue
            }
            val atMillis = sms.receivedAt.toMillis()
            val known = knownByMerchant.getOrPut(event.merchantId) {
                dao.eventsForMerchant(event.merchantId)
                    .mapNotNull { row -> row.toEvent()?.let { StampedEvent(it, row.receivedAtMillis) } }
                    .toMutableList()
            }
            if (event.isDuplicateOfAny(atMillis, known)) {
                if (audit) MailAudit.parsed(sms, "duplicate:${event::class.simpleName}", mailParser.assessMail(sms))
                if (smsAudit) { duplicates++; SmsAudit.message(sms, channel, "duplicate:${event::class.simpleName}") }
                continue
            }
            if (audit) MailAudit.parsed(sms, "stored:${event::class.simpleName}", mailParser.assessMail(sms))
            if (smsAudit) SmsAudit.message(sms, channel, "stored:${event::class.simpleName}")
            known += StampedEvent(event, atMillis)
            entities += event.toEntity(smsKey(sms), EventSource.of(channel, account?.let { "$it|${sms.sender}" } ?: sms.sender), atMillis)
        }
        if (BuildConfig.DEBUG && channel != EventSource.GMAIL) {
            SmsAudit.summary(channel, messages.size, entities.size, unparsed, notWorth, duplicates)
        }
        if (entities.isNotEmpty()) dao.insertEvents(entities)
        return entities.isNotEmpty()
    }

    /**
     * The stored rows behind [sub], paired with the event each one parses to. Read through the
     * same Gmail-based naming as [subscriptions], since a Google Play bank text that mail
     * named as Claude is a `claude` event there but still a `googleplay` row on disk.
     */
    private suspend fun rowsOf(sub: Subscription): List<Pair<EventEntity, PaymentEvent>> {
        val own = sub.events.toSet()
        if (own.isEmpty()) return emptyList()
        return namedEvents(dao.events().first()).filter { (_, event) -> event in own }
    }

    /** Where each charge of [sub] was spotted, keyed by [chargeKey]. */
    suspend fun chargeSources(sub: Subscription): Map<String, ChargeSource> =
        rowsOf(sub).mapNotNull { (row, event) ->
            val debit = event as? PaymentEvent.Debit ?: return@mapNotNull null
            chargeKey(debit.date, debit.amount) to ChargeSource(EventSource.label(row.source), row.receivedAtMillis)
        }.toMap()

    /**
     * The proof [sub] is real: exactly the texts and mails it was built from, newest first. Only
     * the extracted fields exist (the message text is never kept), but kind, amount, source and
     * the exact arrival time are enough to tell the user which bank text or mail it came from.
     * Another plan's texts used to appear here too — every Google Play mandate under any app.
     */
    suspend fun evidence(sub: Subscription): List<EvidenceItem> =
        rowsOf(sub).map { (row, event) ->
            val kind = when (event) {
                is PaymentEvent.MandateCreated -> "AutoPay mandate created"
                is PaymentEvent.MandateRevoked -> "AutoPay mandate cancelled"
                is PaymentEvent.PreDebitNotice -> "Upcoming debit notice for ${event.debitDate}"
                is PaymentEvent.Debit -> "Payment"
            }
            val amount = when (event) {
                is PaymentEvent.MandateCreated -> event.amount
                is PaymentEvent.PreDebitNotice -> event.amount
                is PaymentEvent.Debit -> event.amount
                is PaymentEvent.MandateRevoked -> event.amount
            }
            EvidenceItem(kind, amount, EventSource.label(row.source), row.receivedAtMillis, event.receivedOn)
        }.sortedWith(compareByDescending<EvidenceItem> { it.date }.thenByDescending { it.atMillis })

    /**
     * Stored rows as the events the engine works on, with Google Play bank texts filed under the
     * app that mail names (see [nameChannelEvents]). Pairs each event with its row, in order, so
     * the detail screen can still say which text or mail it came from.
     */
    private fun namedEvents(rows: List<EventEntity>): List<Pair<EventEntity, PaymentEvent>> {
        val parsed = rows.mapNotNull { row -> row.toEvent()?.let { row to it } }
        val named = nameChannelEvents(parsed.map { (row, event) -> SourcedEvent(event, row.source.startsWith("gmail:")) }, channelIds)
        return parsed.map { it.first }.zip(named)
    }

    private val channelIds = MerchantCatalog.all.filter { it.sharedBilling }.map { it.id }.toSet()

    /** All subscriptions, recomputed whenever events, manual entries, user marks or facts change. */
    fun subscriptions(): Flow<List<Subscription>> =
        combine(dao.events(), dao.manual(), dao.marks(), dao.userFacts()) { events, manual, marks, facts ->
            val today = LocalDate.now()
            val detected = engine.build(namedEvents(events).map { it.second }, today).withoutEndedChannelPlans()
            val added = manual.map {
                engine.manual(
                    key = manualKey(it.id), name = it.name, amount = Money(BigDecimal(it.amount)),
                    frequency = Frequency.valueOf(it.frequency), firstCharge = LocalDate.parse(it.firstCharge),
                    isTrial = it.isTrial, today = today,
                )
            }
            val markByKey = marks.associate { it.subscriptionKey to it.mark }
            val factByKey = facts.filterNot { it.dismissed }.associateBy { it.subscriptionKey }
            // Facts before marks: "I've cancelled this" needs the period the user stated to know
            // how long the paid time still runs.
            (detected + added)
                .filter { markByKey[it.key] != UserMarkEntity.HIDDEN && markByKey[it.key] != UserMarkEntity.REPLACED }
                .map { sub -> factByKey[sub.key]?.let { applyUserFact(sub, it, today) } ?: sub }
                .map { if (markByKey[it.key] == UserMarkEntity.CANCELLED) it.stoppedRenewing(today) else it }
                .sortedWith(compareBy<Subscription> { !it.isInUse }.thenBy { !it.isLive }.thenBy { it.nextRenewal ?: it.activeUntil ?: LocalDate.MAX })
        }

    /**
     * What the user typed on the "Needs your input" card beats every guess — it is the only place
     * a date like a NordVPN renewal, which lives inside that app and nowhere else on the phone,
     * can ever come from.
     */
    private fun applyUserFact(sub: Subscription, fact: UserFactEntity, today: LocalDate): Subscription {
        val stated = sub.copy(
            name = fact.name?.takeIf { it.isNotBlank() } ?: sub.name,
            amount = fact.amount?.let { Money(BigDecimal(it), fact.currency ?: sub.amount.currency) } ?: sub.amount,
            amountKnown = sub.amountKnown || fact.amount != null,
            frequency = fact.frequency?.let(Frequency::valueOf) ?: sub.frequency,
            frequencyKnown = sub.frequencyKnown || fact.frequency != null,
            nextRenewal = fact.nextRenewal?.let(LocalDate::parse) ?: sub.nextRenewal,
            renewalSource = if (fact.nextRenewal != null) RenewalSource.USER_SUPPLIED else sub.renewalSource,
            confidence = if (fact.nextRenewal != null) Confidence.HIGH else sub.confidence,
        )
        // A cancelled plan whose period nothing stated: the user's answer is what dates its end.
        val periodNowKnown = sub.status == SubscriptionStatus.ENDING && sub.activeUntil == null && fact.frequency != null
        if (periodNowKnown) return stated.stoppedRenewing(today)
        // "Still active" on a plan whose charge went unseen: the user's next date is the evidence.
        val confirmedLive = sub.status == SubscriptionStatus.LAPSED && sub.missedChargeOn != null &&
            fact.nextRenewal?.let { !LocalDate.parse(it).isBefore(today) } == true
        return if (confirmedLive) stated.copy(status = SubscriptionStatus.ACTIVE, missedChargeOn = null) else stated
    }

    /** Saves what the user typed for a subscription the app couldn't fully work out on its own. */
    suspend fun setUserFact(key: String, frequency: Frequency?, nextRenewal: LocalDate?, amount: BigDecimal?, currency: String?) =
        dao.setUserFact(UserFactEntity(key, frequency?.name, nextRenewal?.toString(), amount?.toPlainString(), currency, name = existingFact(key)?.name))

    /** "I don't know this either, and don't want to be asked again" — distinct from hiding it. */
    suspend fun dismissUserFact(key: String) =
        dao.setUserFact(UserFactEntity(key, null, null, null, null, dismissed = true, name = existingFact(key)?.name))

    /** What the user says a subscription is, e.g. which app a Google Play charge is for. Blank clears it. */
    suspend fun nameSubscription(key: String, name: String) {
        val current = existingFact(key) ?: UserFactEntity(key, null, null, null, null)
        dao.setUserFact(current.copy(name = name.trim().ifBlank { null }))
    }

    /** "How long was this plan?" for a cancelled plan: only the period, keeping anything else already saved. */
    suspend fun setPlanLength(key: String, frequency: Frequency) {
        val current = existingFact(key) ?: UserFactEntity(key, null, null, null, null)
        dao.setUserFact(current.copy(frequency = frequency.name, dismissed = false))
    }

    private suspend fun existingFact(key: String): UserFactEntity? = dao.userFacts().first().firstOrNull { it.subscriptionKey == key }

    suspend fun currentSubscriptions(): List<Subscription> = subscriptions().first()

    /**
     * The user says the AutoPay is cancelled: no more reminders. The day is kept so that a charge notice or
     * charge arriving after it can be told apart from one before, see [cancelClaims].
     */
    suspend fun markCancelled(key: String) {
        // Read before the mark: a cancelled subscription no longer predicts a renewal.
        val renewal = currentSubscriptions().firstOrNull { it.key == key }?.takeIf { it.isLive }?.nextRenewal
        prefs.edit(commit = true) {
            putString("$CANCEL_CLAIM$key", LocalDate.now().toString())
            putString("$AUTOPAY_ANSWER$key", AUTOPAY_CANCELLED)
            if (renewal != null) putString("$CANCEL_RENEWAL$key", renewal.toString()) else remove("$CANCEL_RENEWAL$key")
        }
        dao.setMark(UserMarkEntity(key, UserMarkEntity.CANCELLED))
    }

    /** Subscriptions the user called cancelled, with the day they said so. */
    fun cancelClaims(): Map<String, LocalDate> =
        prefs.all.mapNotNull { (k, v) ->
            if (!k.startsWith(CANCEL_CLAIM)) return@mapNotNull null
            val day = (v as? String)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            k.removePrefix(CANCEL_CLAIM) to day
        }.toMap()

    /** The renewal each "AutoPay cancelled" answer was given for, to verify the evening before it. */
    fun cancelRenewals(): Map<String, LocalDate> =
        prefs.all.mapNotNull { (k, v) ->
            if (!k.startsWith(CANCEL_RENEWAL)) return@mapNotNull null
            val day = (v as? String)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            k.removePrefix(CANCEL_RENEWAL) to day
        }.toMap()

    fun wasCancelVerified(key: String, renewal: LocalDate) = prefs.getBoolean("cancel_verified:$key:$renewal", false)
    fun setCancelVerified(key: String, renewal: LocalDate) = prefs.edit(commit = true) { putBoolean("cancel_verified:$key:$renewal", true) }

    /** Evidence says the AutoPay lives on after all: undo the user's "cancelled". */
    suspend fun reopenCancelClaim(key: String) {
        dao.clearMark(key)
        prefs.edit(commit = true) { remove("$CANCEL_CLAIM$key") }
        clearAutopayAnswers(key)
    }

    /** "No, still active" to "have you cancelled the AutoPay?": for this renewal only, it may be asked again at the next. */
    fun setAutopayStillActive(key: String, renewal: LocalDate) = prefs.edit(commit = true) {
        putBoolean("$AUTOPAY_STILL$key:$renewal", true)
    }

    /**
     * Whether the AutoPay question is settled for [sub]'s current renewal: the user said it was cancelled, or said
     * it is still active for this renewal. An answer given before answers were per renewal is carried over to the
     * renewal that is current now.
     */
    fun autopayAnswered(sub: Subscription): Boolean {
        if (prefs.getString("$AUTOPAY_ANSWER${sub.key}", null) == AUTOPAY_CANCELLED) return true
        val renewal = sub.nextRenewal ?: return false
        if (prefs.getString("$AUTOPAY_ANSWER${sub.key}", null) == AUTOPAY_ACTIVE_LEGACY) {
            prefs.edit(commit = true) { remove("$AUTOPAY_ANSWER${sub.key}"); putBoolean("$AUTOPAY_STILL${sub.key}:$renewal", true) }
            return true
        }
        return prefs.getBoolean("$AUTOPAY_STILL${sub.key}:$renewal", false)
    }

    /** Forgets every AutoPay answer for [key], when the user undoes cancelling it or a charge says the answer was wrong. */
    private fun clearAutopayAnswers(key: String) = prefs.edit(commit = true) {
        remove("$AUTOPAY_ANSWER$key")
        remove("$CANCEL_RENEWAL$key")
        prefs.all.keys.filter { it.startsWith("$AUTOPAY_STILL$key:") }.forEach { remove(it) }
    }

    /** "Replaces it": the older AutoPay was the same app on a plan the user has since changed. */
    suspend fun markReplaced(key: String) = dao.setMark(UserMarkEntity(key, UserMarkEntity.REPLACED))

    /** "Different app": do not ask about this pair again. */
    fun dismissReplacement(olderKey: String, newerKey: String) = prefs.edit(commit = true) {
        putStringSet(KEY_REPLACE_DISMISSED, replacementDismissals() + "$olderKey|$newerKey")
    }

    fun replacementDismissals(): Set<String> = prefs.getStringSet(KEY_REPLACE_DISMISSED, null).orEmpty()

    /** "Still active" for a cancellation text nothing could place: do not ask about that text again. */
    fun dismissRevocationQuestion(key: String, date: LocalDate) = prefs.edit(commit = true) {
        putStringSet(KEY_REVOKE_DISMISSED, revocationDismissals() + "$key|$date")
    }

    fun revocationDismissals(): Set<String> = prefs.getStringSet(KEY_REVOKE_DISMISSED, null).orEmpty()

    /** "Still active" on a plan whose stated billing date passed unseen: it renews next on [next]. */
    suspend fun confirmStillActive(key: String, next: LocalDate) {
        val current = existingFact(key) ?: UserFactEntity(key, null, null, null, null)
        dao.setUserFact(current.copy(nextRenewal = next.toString(), dismissed = false))
    }

    /** "Not a subscription": hide it for good. */
    suspend fun hide(key: String) = dao.setMark(UserMarkEntity(key, UserMarkEntity.HIDDEN))

    suspend fun clearMark(key: String) {
        dao.clearMark(key)
        prefs.edit(commit = true) { remove("$CANCEL_CLAIM$key") }
        clearAutopayAnswers(key)
    }

    suspend fun addManual(name: String, amount: BigDecimal, frequency: Frequency, firstCharge: LocalDate, isTrial: Boolean) =
        dao.insertManual(ManualSubscriptionEntity(name = name, amount = amount.toPlainString(), frequency = frequency.name, firstCharge = firstCharge.toString(), isTrial = isTrial))

    suspend fun deleteManual(key: String) {
        key.removePrefix(MANUAL_PREFIX).toLongOrNull()?.let { dao.deleteManual(it) }
    }

    fun isManual(key: String) = key.startsWith(MANUAL_PREFIX)

    /**
     * Notification-listener bookkeeping, kept as counts only (never any text) so a silent
     * failure — wrong package name, notices that never arrive — can be told apart from "nothing
     * happened yet". [recordNotificationSeen] runs for every allowlisted post, before any filter.
     */
    @Synchronized
    fun recordNotificationSeen(packageName: String) = prefs.edit {
        putInt("notif_seen:$packageName", prefs.getInt("notif_seen:$packageName", 0) + 1)
        putLong("notif_last:$packageName", System.currentTimeMillis())
    }

    /** A notification from [packageName] turned into a payment event that was new. */
    @Synchronized
    fun recordNotificationFound(packageName: String) =
        prefs.edit { putInt("notif_found:$packageName", prefs.getInt("notif_found:$packageName", 0) + 1) }

    fun notificationStats(): List<NotificationStat> =
        prefs.all.keys.filter { it.startsWith("notif_seen:") }.map { it.removePrefix("notif_seen:") }
            .map { pkg ->
                NotificationStat(
                    pkg, prefs.getInt("notif_seen:$pkg", 0), prefs.getInt("notif_found:$pkg", 0),
                    prefs.getLong("notif_last:$pkg", 0L),
                )
            }
            .sortedByDescending { it.lastSeenMillis }

    /**
     * What the user answered when a reminder asked "still using it?", per renewal. Kept in prefs
     * beside [reminderStage] rather than the database: an answer belongs to one renewal date and is
     * worthless afterwards. Written with commit() because the notification buttons that call this
     * run in a receiver, and the process may be gone before an async write lands.
     */
    fun reminderState(key: String, date: LocalDate) = ReminderState(
        answer = prefs.getString("reminder_answer:$key:$date", null)?.let { runCatching { ReminderAnswer.valueOf(it) }.getOrNull() },
        snoozedUntil = prefs.getString("reminder_snooze:$key:$date", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        lastStage = reminderStage(key, date),
        followedUp = prefs.getBoolean("reminder_followup:$key:$date", false),
    )

    fun answerReminder(key: String, date: LocalDate, answer: ReminderAnswer) =
        prefs.edit(commit = true) { putString("reminder_answer:$key:$date", answer.name) }

    /** Quiet until [until], when the reminder may be shown again (so the stage already shown is forgotten). */
    fun snoozeReminder(key: String, date: LocalDate, until: LocalDate) = prefs.edit(commit = true) {
        putString("reminder_snooze:$key:$date", until.toString())
        remove("reminder_stage:$key:$date")
    }

    /** Renewals the user said they would cancel before, as (subscription key, renewal date). */
    fun plannedCancellations(): List<Pair<String, LocalDate>> =
        prefs.all.filter { (k, v) -> k.startsWith("reminder_answer:") && v == ReminderAnswer.CANCEL_PLANNED.name }
            .mapNotNull { (k, _) ->
                val rest = k.removePrefix("reminder_answer:")
                val date = runCatching { LocalDate.parse(rest.substringAfterLast(':')) }.getOrNull() ?: return@mapNotNull null
                rest.substringBeforeLast(':') to date
            }

    /**
     * Calls [onChange] whenever a reminder answer, snooze or shown stage changes, from any
     * writer in this process. Returns the call that stops it. The listener is held by the returned
     * lambda: SharedPreferences keeps listeners only weakly, so an unreferenced one stops firing.
     */
    fun watchReminderAnswers(onChange: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith("reminder_")) onChange()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun wasChargeAlerted(key: String, date: LocalDate) = prefs.getBoolean("charge_alerted:$key:$date", false)
    fun setChargeAlerted(key: String, date: LocalDate) = prefs.edit(commit = true) { putBoolean("charge_alerted:$key:$date", true) }

    /** Remembers which reminders were sent so each renewal is announced once. */
    /**
     * The latest stage a reminder was shown for, per renewal. Older builds stored a plain
     * "notified" flag instead; it is not read, so a renewal already reminded about once may get
     * one more reminder after an update, which is harmless.
     */
    fun reminderStage(key: String, date: LocalDate): Int? =
        prefs.getInt("reminder_stage:$key:$date", -1).takeIf { it >= 0 }

    fun setFollowUpSent(key: String, date: LocalDate) = prefs.edit(commit = true) { putBoolean("reminder_followup:$key:$date", true) }

    fun setReminderStage(key: String, date: LocalDate, stage: Int) = prefs.edit(commit = true) { putInt("reminder_stage:$key:$date", stage) }

    private fun smsKey(sms: Sms) = "${sms.sender}|${sms.receivedAt.toLocalDate()}|${sms.body.hashCode()}"
    private fun manualKey(id: Long) = "$MANUAL_PREFIX$id"

    private fun LocalDateTime.toMillis() =
        atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    companion object {
        private const val KEY_GMAIL_SYNC = "last_gmail_sync_millis"
        private const val KEY_GMAIL_BACKFILL = "gmail_backfill_before_millis"
        private const val KEY_GMAIL_SLICES = "gmail_backfill_slices"
        private const val KEY_GMAIL_CATCHUP_BEFORE = "gmail_catchup_before_millis"
        private const val KEY_GMAIL_CATCHUP_TARGET = "gmail_catchup_target_millis"
        private const val KEY_GMAIL_WELCOME = "gmail_welcome_sync_millis"
        private const val KEY_GMAIL_ENABLED = "gmail_import_enabled"
        private const val KEY_GMAIL_ACCOUNT = "gmail_account_email"
        private const val KEY_GMAIL_ACCOUNTS = "gmail_accounts"
        private const val KEY_GMAIL_CONSENT = "gmail_consent_accounts"
        private const val KEY_LAST_SCAN = "last_scan_millis"
        private const val KEY_CLASSIFIER_VERSION = "classifier_version"
        private const val KEY_SMS_PURGED = "sms_events_purged"
        private const val KEY_GMAIL_CLASSIFIER_VERSION = "gmail_classifier_version"
        private const val KEY_CHANNEL_REKEYED = "channel_keys_split"
        private const val KEY_REPLACE_DISMISSED = "replace_question_dismissed"
        private const val KEY_REVOKE_DISMISSED = "revoke_question_dismissed"

        /**
         * 1: Play trial receipts (promo filter, "unless cancelled", merchant, trial dates).
         * 2: per-account cursors, and mail events tagged with the account that received them.
         * 3: the query leaves out the Promotions tab (results should not change; re-reads to time the faster fetch).
         * 4: revocations keep their amount and stated end of access.
         * 5: mail keeps the mandate number it states, which ties it to a bank text.
         * 6: Play receipts with an introductory price, Play cancellation mails, and the store order
         *    number (as a hash) that ties a receipt to its cancellation.
         * 11-12: re-read to run the debug mail audit (no parsing change).
         * 15: a store order receipt is a purchase (app, amount, period), not a renewal notice.
         * 14: a declined payment that warns "will be cancelled", and legal notices, are no longer read as cancellations.
         * 13: renewal notices, cancellations and expiries from a service's own mail; the query also searches subjects.
         * 7-10: the signing domain is read from `header.i` when `header.d` is absent (Google's own mail).
         */
        private const val CANCEL_CLAIM = "cancel_claim:"
        private const val CANCEL_RENEWAL = "cancel_renewal:"
        private const val AUTOPAY_ANSWER = "autopay_answer:"
        private const val AUTOPAY_CANCELLED = "CANCELLED"
        private const val AUTOPAY_ACTIVE_LEGACY = "ACTIVE"
        private const val AUTOPAY_STILL = "autopay_active:"
        private const val GMAIL_CLASSIFIER_VERSION = 18
        private const val AUDIT_PROBE_VERSION = 1

        /**
         * Bump whenever a change to [com.aasif.watchdog.core.parser.SmsParser] or
         * [com.aasif.watchdog.core.merchant.MerchantCatalog] would classify already-scanned SMS
         * differently — see [reclassifyIfNeeded]. 2: SMS now keeps only what a subscription can be
         * built on ([worthKeepingFromSms]). 3: a mandate re-created after a revocation is kept instead
         * of dropped as a duplicate of the first. 4: revocations keep their amount (which of several
         * mandates to one payee ended) and any stated end of access. 5: every mandate text keeps
         * a hash of the mandate number it carries, which ties a revocation to its mandate.
         */
        private const val CLASSIFIER_VERSION = 5

        /** Frontier value meaning the whole [FIRST_SCAN_DAYS] window has been covered. */
        private const val BACKFILL_DONE = 0L

        /** 120 slices of [BACKFILL_SLICE] is far more payment mail than a year can hold. */
        private const val MAX_BACKFILL_SLICES = 120
        private const val KEY_LEAD_DAYS = "reminder_lead_days"
        private const val FIRST_SCAN_DAYS = 400L // just over a year of mail, enough to see yearly plans renew once
        private const val MANUAL_PREFIX = "manual:"

        /** A capped run is safe to repeat: the duplicate check absorbs whatever it re-reads. */
        private const val MAX_GMAIL_MESSAGES = 500

        /** Plan-started mail is a handful a year per person; this caps the first look-back. */
        private const val MAX_WELCOME_MESSAGES = 100

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
