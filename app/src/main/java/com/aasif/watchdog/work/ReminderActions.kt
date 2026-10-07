package com.aasif.watchdog.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.aasif.watchdog.WatchdogApp
import com.aasif.watchdog.core.engine.ReminderAnswer
import com.aasif.watchdog.core.engine.followUpDelay
import com.aasif.watchdog.core.engine.shouldFollowUp
import java.time.LocalDate
import java.time.LocalDateTime

/** The buttons on a renewal reminder, and the extras they carry. */
object ReminderActions {
    const val KEEP = "com.aasif.watchdog.action.REMINDER_KEEP"
    const val SNOOZE = "com.aasif.watchdog.action.REMINDER_SNOOZE"

    /** The final-day reminder was swiped away (or cleared) without an answer. */
    const val DISMISSED = "com.aasif.watchdog.action.REMINDER_DISMISSED"

    const val EXTRA_KEY = "reminder_key"
    const val EXTRA_DATE = "reminder_date"
    const val EXTRA_NOTIFICATION_ID = "reminder_notification_id"

    /** "Cancel it" opens the app instead of a receiver: Android 12+ blocks starting a screen from one. */
    const val EXTRA_CANCEL_KEY = "reminder_cancel_key"
}

/** Handles "Keep" and "Snooze" without opening the app. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(ReminderActions.EXTRA_KEY) ?: return
        val date = intent.getStringExtra(ReminderActions.EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        val repo = (context.applicationContext as WatchdogApp).repository
        when (intent.action) {
            ReminderActions.KEEP -> repo.answerReminder(key, date, ReminderAnswer.KEEP)
            ReminderActions.SNOOZE -> repo.snoozeReminder(key, date, LocalDate.now().plusDays(1))
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
