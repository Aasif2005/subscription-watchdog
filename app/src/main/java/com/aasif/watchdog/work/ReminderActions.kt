package com.aasif.watchdog.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.aasif.watchdog.WatchdogApp
import com.aasif.watchdog.core.engine.ReminderAnswer
import com.aasif.watchdog.core.engine.followUpDelay
import com.aasif.watchdog.core.engine.shouldFollowUp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/** The buttons on a renewal reminder, and the extras they carry. */
object ReminderActions {
    const val KEEP = "com.aasif.watchdog.action.REMINDER_KEEP"
    const val SNOOZE = "com.aasif.watchdog.action.REMINDER_SNOOZE"

    /** "Already cancelled": the user says the AutoPay is off, so the reminders stop. */
    const val ALREADY_CANCELLED = "com.aasif.watchdog.action.REMINDER_ALREADY_CANCELLED"

    /** The final-day reminder was swiped away (or cleared) without an answer. */
    const val DISMISSED = "com.aasif.watchdog.action.REMINDER_DISMISSED"

    const val EXTRA_KEY = "reminder_key"
    const val EXTRA_DATE = "reminder_date"
    const val EXTRA_NOTIFICATION_ID = "reminder_notification_id"

    /** "Cancel it" opens the app instead of a receiver: Android 12+ blocks starting a screen from one. */
    const val EXTRA_CANCEL_KEY = "reminder_cancel_key"

    /** A notification that is only a pointer to one subscription opens that subscription's Detail screen. */
    const val EXTRA_OPEN_KEY = "reminder_open_key"
}

/** Handles "Keep", "Snooze" and "Already cancelled" without opening the app. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(ReminderActions.EXTRA_KEY) ?: return
        val date = intent.getStringExtra(ReminderActions.EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        val repo = (context.applicationContext as WatchdogApp).repository
        when (intent.action) {
            ReminderActions.KEEP -> repo.answerReminder(key, date, ReminderAnswer.KEEP)
            ReminderActions.SNOOZE -> repo.snoozeReminder(key, date, LocalDate.now().plusDays(1))
            ReminderActions.ALREADY_CANCELLED -> {
                // Writes the database, so the receiver is kept alive until it is done.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try { repo.markCancelled(key) } finally { pending.finish() }
                }
            }
            ReminderActions.DISMISSED -> {
                // Not an answer, and nothing to cancel: queue the one same-day follow-up, if due.
                if (shouldFollowUp(LocalDate.now(), date, repo.reminderState(key, date))) {
                    followUpDelay(LocalDateTime.now())?.let { Workers.scheduleFollowUp(context, key, date, it) }
                }
                return
            }
            else -> return
        }
        // A button press does not dismiss the notification by itself.
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(ReminderActions.EXTRA_NOTIFICATION_ID, key.hashCode()))
    }
}
