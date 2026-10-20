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
import com.aasif.watchdog.data.toLocalDateTime
import com.aasif.watchdog.work.Workers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Reads payment alerts that UPI and bank apps show as notifications. Some banks send the
 * AutoPay pre-debit notice only inside the UPI app, and this is the one on-device source of them.
 * Notifications from any other app are ignored without being read.
 */
class PaymentNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Notification key plus a hash of its words, for the last [COUNTED_LIMIT] posts counted. */
    private val countedOnce: MutableSet<String> = java.util.Collections.newSetFromMap(
        object : LinkedHashMap<String, Boolean>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > COUNTED_LIMIT
        },
    )

    override fun onListenerConnected() {
        // Alerts already in the shade when access was granted. Not counted as "seen": the same
        // ones would be counted again on every reconnect.
        // Android can refuse this call ("Disallowed call from unknown notification listener") when it reconnects the
        // listener while the previous connection's token is being replaced, e.g. right after the process was killed and
        // restarted. That used to crash the app. Skipping the look-back once is harmless: alerts posted from now on still arrive.
        val existing = try {
            activeNotifications?.mapNotNull { toMessage(it, count = false) }.orEmpty()
        } catch (_: SecurityException) {
            return
        }
        if (existing.isNotEmpty()) ingest(existing)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        toMessage(sbn, count = true)?.let { ingest(listOf(it)) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ingest(messages: List<Sms>) {
        val app = applicationContext as WatchdogApp
        scope.launch {
            // One message at a time, so a new event can be credited to the app that posted it.
            var any = false
            for (message in messages) {
                if (app.repository.ingest(listOf(message), EventSource.APP)) {
                    any = true
                    app.repository.recordNotificationFound(message.sender)
                }
            }
            if (any) {
                Workers.remindNow(app)
                Workers.gmailSyncSoonIfChannel(app, messages.map { it.body })
            }
        }
    }

    private fun toMessage(sbn: StatusBarNotification, count: Boolean): Sms? {
        if (sbn.packageName !in allowedPackages) return null
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        // Apps re-post one notification as it changes ("processing" → "successful"), and a re-post
        // with the same words is not a new alert. Only a hash is kept, never the text.
        if (count && countedOnce.add("${sbn.key}|${(title + text).hashCode()}")) {
            (applicationContext as WatchdogApp).repository.recordNotificationSeen(sbn.packageName)
        }
        val body = listOf(title, text).filter { it.isNotBlank() }.joinToString(" ")
        if (text.isBlank() || SmsClassifier.isPromotional(body)) return null
        return Sms(sender = sbn.packageName, body = body, receivedAt = sbn.postTime.toLocalDateTime())
    }

    companion object {
        private const val COUNTED_LIMIT = 200

        // Debug builds also accept `adb shell cmd notification post`, which posts as the shell.
        private val allowedPackages = EventSource.paymentApps.keys.let {
            if (BuildConfig.DEBUG) it + "com.android.shell" else it
        }

        fun isEnabled(context: Context) =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        fun component(context: Context) = ComponentName(context, PaymentNotificationListener::class.java)
    }
}
