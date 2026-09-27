package com.aasif.watchdog.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aasif.watchdog.R
import com.aasif.watchdog.WatchdogApp
import com.aasif.watchdog.core.engine.dueForReminder
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
    private const val REMINDERS = "reminders-daily"
    private const val REMINDER_HOUR = 9

    fun schedulePeriodic(context: Context) {
        val wm = WorkManager.getInstance(context)
        // Catches SMS the receiver missed (app killed, permission granted later).
        wm.enqueueUniquePeriodicWork(
            SCAN_DAILY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ScanWorker>(1, TimeUnit.DAYS).build(),
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
