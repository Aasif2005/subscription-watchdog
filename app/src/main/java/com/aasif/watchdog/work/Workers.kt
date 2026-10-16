package com.aasif.watchdog.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aasif.watchdog.BuildConfig
import com.aasif.watchdog.R
import com.aasif.watchdog.WatchdogApp
import com.aasif.watchdog.core.engine.EVENING_HOUR
import com.aasif.watchdog.core.engine.FINAL_STAGE
import com.aasif.watchdog.core.engine.chargedDespiteCancelPlan
import com.aasif.watchdog.core.engine.dueForReminder
import com.aasif.watchdog.core.engine.evidenceAfterCancelClaim
import com.aasif.watchdog.core.engine.reminderStage
import com.aasif.watchdog.core.engine.shouldFollowUp
import com.aasif.watchdog.core.engine.shouldRemind
import com.aasif.watchdog.core.engine.shouldVerifyCancel
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.Subscription
import com.aasif.watchdog.data.GmailSyncOutcome
import com.aasif.watchdog.data.WatchdogRepository
import com.aasif.watchdog.ui.MainActivity
import com.aasif.watchdog.ui.formatDate
import com.aasif.watchdog.ui.formatMoney
import com.aasif.watchdog.ui.reminderText
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object Workers {
    private const val SCAN_DAILY = "scan-daily"
    private const val SCAN_NOW = "scan-now"
    private const val GMAIL_DAILY = "gmail-daily"
    private const val GMAIL_NOW = "gmail-now"

    /** The backfill's own continuation, kept off [GMAIL_NOW] so it cannot cancel its own run. */
    private const val GMAIL_MORE = "gmail-more"
    private const val REMINDERS = "reminders-daily"
    private const val REMINDERS_EVENING = "reminders-evening"
    private const val REMINDER_HOUR = 9

    /** Gmail's quota is per minute, so after a throttled slice the next one waits a window out. */
    private val slicePause: Duration = Duration.ofSeconds(70)

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** The backfill asks to be re-run per slice; exponential backoff would stretch it over days. */
    private fun gmailSync(delay: Duration = Duration.ZERO) = OneTimeWorkRequestBuilder<GmailSyncWorker>()
        .setConstraints(online)
        .setInitialDelay(delay)
        .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
        .build()

    fun schedulePeriodic(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (BuildConfig.SMS_ENABLED) {
            // Catches SMS the receiver missed (app killed, permission granted later).
            wm.enqueueUniquePeriodicWork(
                SCAN_DAILY, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ScanWorker>(1, TimeUnit.DAYS).build(),
            )
        } else {
            wm.cancelUniqueWork(SCAN_DAILY)
        }
        // Always enqueued; the worker itself returns early unless Gmail import is switched on.
        wm.enqueueUniquePeriodicWork(
            GMAIL_DAILY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<GmailSyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build(),
        )
        // Two daily reminder passes. The morning one covers "a few days ahead" and the renewal day;
        // the evening one is when the day-before reminder is allowed out (see EVENING_HOUR), so an
        // early-morning AutoPay debit still has a whole night's warning. Periodic work is not
        // exact: either can run late, especially under battery saving.
        wm.enqueueUniquePeriodicWork(REMINDERS, ExistingPeriodicWorkPolicy.KEEP, reminderPass(REMINDER_HOUR))
        wm.enqueueUniquePeriodicWork(REMINDERS_EVENING, ExistingPeriodicWorkPolicy.KEEP, reminderPass(EVENING_HOUR))
    }

    /** A daily [ReminderWorker] whose first run is at the next [hour]:00. */
    private fun reminderPass(hour: Int): PeriodicWorkRequest {
        val now = LocalDateTime.now()
        var nextRun = now.toLocalDate().atTime(hour, 0)
        if (!nextRun.isAfter(now)) nextRun = nextRun.plusDays(1)
        return PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, nextRun))
            .build()
    }

    /** Re-check reminders now, e.g. when a pre-debit notice just arrived with only ~24h warning. */
    fun remindNow(context: Context) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<ReminderWorker>().build())
    }

    /** Full scan right after the user grants SMS access, followed by a reminder check. */
    fun scanNow(context: Context) {
        WorkManager.getInstance(context)
            .beginUniqueWork(SCAN_NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ScanWorker>().build())
            .then(OneTimeWorkRequestBuilder<ReminderWorker>().build())
            .enqueue()
    }

    /** Backfill right after the user turns Gmail import on. */
    fun gmailSyncNow(context: Context) {
        val wm = WorkManager.getInstance(context)
        // A slice already queued would otherwise run alongside this one and share the quota.
        wm.cancelUniqueWork(GMAIL_MORE)
        wm.beginUniqueWork(GMAIL_NOW, ExistingWorkPolicy.REPLACE, gmailSync())
            .then(OneTimeWorkRequestBuilder<ReminderWorker>().build())
            .enqueue()
    }

    /**
     * Next slice of the backfill. It follows straight on unless Gmail throttled the last one, in
     * which case it waits so the quota window has reset by the time it runs.
     */
    fun gmailSyncNextSlice(context: Context, afterThrottle: Boolean) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(GMAIL_MORE, ExistingWorkPolicy.REPLACE, gmailSync(if (afterThrottle) slicePause else Duration.ZERO))
    }

    /**
     * The one repeat of a final-day reminder that was swiped away. Unique per renewal, so a
     * second swipe cannot queue a second one.
     */
    fun scheduleFollowUp(context: Context, key: String, date: LocalDate, delay: Duration) {
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay)
            .setInputData(workDataOf(ReminderWorker.FOLLOW_UP_KEY to key, ReminderWorker.FOLLOW_UP_DATE to date.toString()))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("reminder-followup:$key:$date", ExistingWorkPolicy.KEEP, request)
    }

    /** Stops every Gmail work item, e.g. right after the user disconnects from the profile page. */
    fun cancelGmailWork(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(GMAIL_NOW)
        wm.cancelUniqueWork(GMAIL_MORE)
        wm.cancelUniqueWork(GMAIL_DAILY)
    }
}

/**
 * Reads subscription e-mail. Silent authorization is all a background worker can do — when the
 * user has to approve the scope again, it stops and the dashboard card asks on next launch.
 */
class GmailSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as WatchdogApp).repository
        if (!repo.gmailImportEnabled) return Result.success()
        val outcome = try {
            repo.syncGmail()
        } catch (t: Throwable) {
            // Logged as Throwable on purpose: an OutOfMemoryError here would otherwise vanish.
            if (BuildConfig.DEBUG) Log.w("GmailSync", "sync threw, attempt=$runAttemptCount", t)
            throw t
        }
        return when (outcome) {
            GmailSyncOutcome.DONE -> Result.success()
            // This slice landed, so the run succeeded; the next one is paced rather than retried,
            // because retry backoff grows with every attempt and a backfill is many slices long.
            GmailSyncOutcome.MORE, GmailSyncOutcome.MORE_AFTER_THROTTLE -> {
                Workers.gmailSyncNextSlice(applicationContext, afterThrottle = outcome == GmailSyncOutcome.MORE_AFTER_THROTTLE)
                Result.success()
            }
            // Nothing was read at all, so let WorkManager's backoff decide when to try again.
            GmailSyncOutcome.RETRY -> Result.retry()
            // Retrying cannot help: only the user can grant the scope again.
            GmailSyncOutcome.NEEDS_CONSENT -> Result.success()
        }
    }
}

class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!BuildConfig.SMS_ENABLED ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }
        (applicationContext as WatchdogApp).repository.scanInbox()
        return Result.success()
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as WatchdogApp).repository
        repo.purgeSmsIfUnsupported()
        if (!canNotify()) return Result.success()
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val subscriptions = repo.currentSubscriptions()
        ReminderChannel.ensure(applicationContext)

        // The user said they would cancel, and a charge landed anyway. Worth saying so once.
        // The bank's own texts say the AutoPay was cancelled, and money was still taken. Only
        // recent charges: an old one surfacing after an update is history, not news.
        for (sub in subscriptions) {
            val charged = sub.chargedAfterCancel ?: continue
            if (charged.isBefore(today.minusDays(CHARGE_ALERT_DAYS)) || repo.wasChargeAlerted(sub.key, charged)) continue
            val price = if (sub.amountKnown) formatMoney(sub.amount) else "A payment"
            notify(
                sub.key.hashCode() + 2, "${sub.name} charged after the AutoPay was cancelled",
                "$price was taken on ${formatDate(charged)} although its AutoPay had been cancelled. Check the mandate in your UPI app and ask ${sub.name} for a refund.",
                openApp(sub.key.hashCode() + 2),
            )
            repo.setChargeAlerted(sub.key, charged)
        }

        for ((key, planned) in repo.plannedCancellations()) {
            val sub = subscriptions.firstOrNull { it.key == key } ?: continue
            // Already reported above, from the bank's own revocation.
            if (sub.chargedAfterCancel != null) continue
            if (!chargedDespiteCancelPlan(sub, planned) || repo.wasChargeAlerted(key, planned)) continue
            val price = if (sub.amountKnown) formatMoney(sub.amount) else "A payment"
            notify(
                key.hashCode() + 1, "${sub.name} charged you anyway",
                "$price was charged although you planned to cancel before ${formatDate(planned)}. Open the app to cancel it or check the mandate.",
                openApp(key.hashCode() + 1),
            )
            repo.setChargeAlerted(key, planned)
        }

        // The user said a plan was cancelled, yet a charge notice or a charge came after that day: the
        // AutoPay may still be live. Undo the answer so reminders resume, and say so once.
        for ((key, claimed) in repo.cancelClaims()) {
            val sub = subscriptions.firstOrNull { it.key == key } ?: continue
            val evidence = sub.evidenceAfterCancelClaim(claimed) ?: continue
            if (repo.wasChargeAlerted(key, evidence)) continue
            repo.reopenCancelClaim(key)
            val price = if (sub.amountKnown) formatMoney(sub.amount) else "A payment"
            notify(
                key.hashCode() + 3, "${sub.name} may still be active",
                "You said it was cancelled, but a charge notice or charge for $price came on ${formatDate(evidence)}. Check the mandate in your UPI app.",
                openApp(key.hashCode() + 3),
            )
            repo.setChargeAlerted(key, evidence)
        }

        postCancelVerifications(repo, subscriptions, now)

        // A reminder still in the tray for something that has since been cancelled would only mislead.
        for (sub in subscriptions) {
            if (!sub.isInUse) NotificationManagerCompat.from(applicationContext).cancel(sub.key.hashCode())
        }

        postFollowUp(repo, subscriptions, today)

        val due = subscriptions.dueForReminder(today, repo.reminderLeadDays)
        for (sub in due) {
            val date = sub.nextRenewal ?: continue
            if (!shouldRemind(now, date, repo.reminderState(sub.key, date))) continue
            val (title, text) = sub.reminderText(date, now)
            // The app cannot know whether a subscription is being used, so it asks.
            val stage = reminderStage(date, today)
            notify(
                sub.key.hashCode(), title, "$text Still using it?", openApp(sub.key.hashCode()), actions(sub.key, date, today),
                // Only the last one is watched: it is the one a swipe leaves no time to recover from.
                onDismiss = if (stage == FINAL_STAGE) dismissIntent(sub.key, date) else null,
            )
            repo.setReminderStage(sub.key, date, stage)
        }
        return Result.success()
    }

    /**
     * The user said the AutoPay is cancelled: the evening before its renewal, nudge them to check the mandate
     * is really off. Nothing to answer; a bank text that already says it was revoked makes it pointless.
     */
    private fun postCancelVerifications(repo: WatchdogRepository, subscriptions: List<Subscription>, now: LocalDateTime) {
        for ((key, renewal) in repo.cancelRenewals()) {
            if (!shouldVerifyCancel(now, renewal, repo.wasCancelVerified(key, renewal))) continue
            val sub = subscriptions.firstOrNull { it.key == key } ?: continue
            val latestMandate = sub.events.filterIsInstance<PaymentEvent.MandateCreated>().maxOfOrNull { it.receivedOn }
            val revoked = sub.events.filterIsInstance<PaymentEvent.MandateRevoked>()
                .any { latestMandate == null || !it.receivedOn.isBefore(latestMandate) }
            repo.setCancelVerified(key, renewal)
            if (revoked) continue
            notify(
                key.hashCode() + 4, "Verify that you have cancelled ${sub.name}'s AutoPay",
                "It renews tomorrow. Check that the mandate is switched off in your UPI app.",
                openDetail(key, key.hashCode() + 4),
            )
        }
    }

    /** Runs only for the work item [Workers.scheduleFollowUp] queued, and only if it is still due. */
    private fun postFollowUp(repo: WatchdogRepository, subscriptions: List<Subscription>, today: LocalDate) {
        val key = inputData.getString(FOLLOW_UP_KEY) ?: return
        val date = inputData.getString(FOLLOW_UP_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        if (!shouldFollowUp(today, date, repo.reminderState(key, date))) return
        val sub = subscriptions.firstOrNull { it.key == key } ?: return
        // Cancelled since the first reminder: nothing left to warn about.
        if (!sub.remindable || sub.nextRenewal != date) return
        val price = if (sub.amountKnown) formatMoney(sub.amount) else "This renewal"
        // Marked first: whatever happens next, this is the only repeat.
        repo.setFollowUpSent(key, date)
        notify(
            key.hashCode(), "${sub.name} is due today",
            "You dismissed the reminder. $price is due today unless you cancel it. Still using it?",
            openApp(key.hashCode()), actions(key, date, today),
        )
    }

    private fun dismissIntent(key: String, date: LocalDate) = PendingIntent.getBroadcast(
        applicationContext, key.hashCode() * 8,
        Intent(applicationContext, ReminderActionReceiver::class.java).setAction(ReminderActions.DISMISSED)
            .putExtra(ReminderActions.EXTRA_KEY, key).putExtra(ReminderActions.EXTRA_DATE, date.toString()),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(requestCode: Int) = PendingIntent.getActivity(
        applicationContext, requestCode, Intent(applicationContext, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Opens the app on [key]'s Detail screen, for a notification that is about that one subscription. */
    private fun openDetail(key: String, requestCode: Int) = PendingIntent.getActivity(
        applicationContext, requestCode,
        Intent(applicationContext, MainActivity::class.java).putExtra(ReminderActions.EXTRA_OPEN_KEY, key),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Keep and Snooze are handled in the background; Cancel opens the screen that has the cancel link. */
    private fun actions(key: String, date: LocalDate, today: LocalDate): List<NotificationCompat.Action> {
        val id = key.hashCode()
        fun broadcast(action: String, offset: Int) = PendingIntent.getBroadcast(
            applicationContext, id * 8 + offset,
            Intent(applicationContext, ReminderActionReceiver::class.java).setAction(action)
                .putExtra(ReminderActions.EXTRA_KEY, key).putExtra(ReminderActions.EXTRA_DATE, date.toString())
                .putExtra(ReminderActions.EXTRA_NOTIFICATION_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getActivity(
            applicationContext, id * 8 + 3,
            Intent(applicationContext, MainActivity::class.java)
                .putExtra(ReminderActions.EXTRA_CANCEL_KEY, key).putExtra(ReminderActions.EXTRA_DATE, date.toString())
                .putExtra(ReminderActions.EXTRA_NOTIFICATION_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return listOfNotNull(
            NotificationCompat.Action(0, "Keep", broadcast(ReminderActions.KEEP, 1)),
            NotificationCompat.Action(0, "Cancel", cancel),
            // Android shows three actions at most, so "Tomorrow" lives on the dashboard card only.
            NotificationCompat.Action(0, "AutoPay off", broadcast(ReminderActions.ALREADY_CANCELLED, 4)),
        )
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission") // checked in canNotify()
    private fun notify(
        id: Int, title: String, text: String, open: PendingIntent,
        actions: List<NotificationCompat.Action> = emptyList(), onDismiss: PendingIntent? = null,
    ) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setDeleteIntent(onDismiss)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            // On the lock screen the amount stays hidden; the full text shows once unlocked.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Subscription reminder")
                    .setContentText("Unlock to see which renewal is coming up.")
                    .build(),
            )
            .apply { actions.forEach(::addAction) }
            .build()
        NotificationManagerCompat.from(applicationContext).notify(id, notification)
    }

    companion object {
        const val FOLLOW_UP_KEY = "follow_up_key"
        private const val CHARGE_ALERT_DAYS = 7L
        const val FOLLOW_UP_DATE = "follow_up_date"
        private const val CHANNEL_ID = ReminderChannel.ID
    }
}

/** The one notification channel the app posts reminders on. */
object ReminderChannel {
    const val ID = "renewals_urgent"

    /** Default importance, so it never showed as a banner. Replaced by [ID]. */
    private const val OLD_ID = "renewals"

    /**
     * `IMPORTANCE_HIGH` is what lets a reminder drop over the home screen as a banner. A channel's
     * importance cannot be changed once created, which is why this is a new channel; the old one
     * is deleted so system settings do not show a dead entry next to it. Created at app start, not
     * only before the first reminder, so the settings button always has a page to open.
     */
    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ID, context.getString(R.string.channel_renewals), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.channel_renewals_desc) },
        )
        manager.deleteNotificationChannel(OLD_ID)
    }
}
