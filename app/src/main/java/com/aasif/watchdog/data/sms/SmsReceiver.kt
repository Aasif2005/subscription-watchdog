package com.aasif.watchdog.data.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.aasif.watchdog.BuildConfig
import com.aasif.watchdog.WatchdogApp
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.data.EventSource
import com.aasif.watchdog.data.toLocalDateTime
import com.aasif.watchdog.work.Workers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Parses payment SMS the moment they arrive, so a pre-debit notice is seen within seconds. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.SMS_ENABLED || intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // Long SMS arrive split into parts; join them per sender.
        val messages = parts.groupBy { it.originatingAddress.orEmpty() }.map { (sender, pieces) ->
            Sms(sender, pieces.joinToString("") { it.messageBody.orEmpty() }, pieces.first().timestampMillis.toLocalDateTime())
        }

        val pending = goAsync()
        val repository = (context.applicationContext as WatchdogApp).repository
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (repository.ingest(messages, EventSource.SMS)) Workers.remindNow(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
