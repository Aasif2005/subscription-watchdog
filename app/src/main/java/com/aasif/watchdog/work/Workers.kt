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
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aasif.watchdog.BuildConfig
import com.aasif.watchdog.R
import com.aasif.watchdog.WatchdogApp
import com.aasif.watchdog.core.engine.dueForReminder
import com.aasif.watchdog.data.GmailSyncOutcome
import com.aasif.watchdog.core.model.SubscriptionStatus
import com.aasif.watchdog.ui.MainActivity
import com.aasif.watchdog.ui.formatRupees
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

object Workers {
    private const val SCAN_DAILY = "scan-daily"
    private const val SCAN_NOW = "scan-now"
    private const val GMAIL_DAILY = "gmail-daily"
    private const val GMAIL_NOW = "gmail-now"

    /** The backfill's own continuation, kept off [GMAIL_NOW] so it cannot cancel its own run. */
    private const val GMAIL_MORE = "gmail-more"
    private const val REMINDERS = "reminders-daily"
    private const val REMINDER_HOUR = 9

    /** Gmail's quota is per minute, so the next slice waits one out rather than racing it. */
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
        // Catches SMS the receiver missed (app killed, permission granted later).
        wm.enqueueUniquePeriodicWork(
            SCAN_DAILY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ScanWorker>(1, TimeUnit.DAYS).build(),
        )
        // Always enqueued; the worker itself returns early unless Gmail import is switched on.
        wm.enqueueUniquePeriodicWork(
            GMAIL_DAILY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<GmailSyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build(),
        )
        // Daily reminder pass, aligned to the morning.
        val now = LocalDateTime.now()
        var nextRun = now.toLocalDate().atTime(REMINDER_HOUR, 0)
        if (!nextRun.isAfter(now)) nextRun = nextRun.plusDays(1)
        wm.enqueueUniquePeriodicWork(
            REMINDERS, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(now, nextRun))
                .build(),
        )
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

    /** Next slice of the backfill, spaced so the quota window has reset by the time it runs. */
    fun gmailSyncNextSlice(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(GMAIL_MORE, ExistingWorkPolicy.REPLACE, gmailSync(slicePause))
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
            GmailSyncOutcome.MORE -> {
                Workers.gmailSyncNextSlice(applicationContext)
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
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return Result.success()
        }
        (applicationContext as WatchdogApp).repository.scanInbox()
        return Result.success()
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as WatchdogApp).repository
        val today = LocalDate.now()
        val due = repo.currentSubscriptions().dueForReminder(today, repo.reminderLeadDays)
        if (due.isEmpty() || !canNotify()) return Result.success()

        ensureChannel()
        for (sub in due) {
            val date = sub.nextRenewal ?: continue
            if (repo.wasNotified(sub.key, date)) continue
            val days = ChronoUnit.DAYS.between(today, date)
            val whenText = when (days) {
                0L -> "today"
                1L -> "tomorrow"
                else -> "in $days days"
            }
            val price = formatRupees(sub.amount)
            val (title, text) = if (sub.status == SubscriptionStatus.TRIAL) {
                "${sub.name} free trial ends $whenText" to "$price will be charged unless you cancel before $date."
            } else {
                "${sub.name} renews $whenText" to "$price (${sub.frequency.label.lowercase()}) will be charged on $date."
            }
            notify(sub.key.hashCode(), title, text)
            repo.setNotified(sub.key, date)
        }
        return Result.success()
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.channel_renewals), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = applicationContext.getString(R.string.channel_renewals_desc) }
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @Suppress("MissingPermission") // checked in canNotify()
    private fun notify(id: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(id, notification)
    }

    companion object {
        private const val CHANNEL_ID = "renewals"
    }
}
