package com.aasif.watchdog.data.notification

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.aasif.watchdog.BuildConfig
import com.aasif.watchdog.WatchdogApp
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.core.parser.SmsClassifier
import com.aasif.watchdog.data.EventSource
import com.aasif.watchdog.data.sms.toLocalDateTime
import com.aasif.watchdog.work.Workers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Reads payment alerts that UPI and bank apps show as notifications. Some banks send the
 * AutoPay pre-debit notice only inside the UPI app, so the SMS inbox never sees it.
 * Notifications from any other app are ignored without being read.
 */
class PaymentNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        // Alerts already in the shade when access was granted.
        val existing = activeNotifications?.mapNotNull(::toMessage).orEmpty()
        if (existing.isNotEmpty()) ingest(existing)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        toMessage(sbn)?.let { ingest(listOf(it)) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ingest(messages: List<Sms>) {
        val app = applicationContext as WatchdogApp
        scope.launch {
            if (app.repository.ingest(messages, EventSource.APP)) Workers.remindNow(app)
        }
    }

    private fun toMessage(sbn: StatusBarNotification): Sms? {
        if (sbn.packageName !in allowedPackages) return null
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        val body = listOf(title, text).filter { it.isNotBlank() }.joinToString(" ")
        if (text.isBlank() || SmsClassifier.isPromotional(body)) return null
        return Sms(sender = sbn.packageName, body = body, receivedAt = sbn.postTime.toLocalDateTime())
    }

    companion object {
        // Debug builds also accept `adb shell cmd notification post`, which posts as the shell.
        private val allowedPackages = EventSource.paymentApps.keys.let {
            if (BuildConfig.DEBUG) it + "com.android.shell" else it
        }

        fun isEnabled(context: Context) =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        fun component(context: Context) = ComponentName(context, PaymentNotificationListener::class.java)
    }
}
