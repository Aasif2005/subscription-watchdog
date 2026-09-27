package com.aasif.watchdog.data.sms

import android.content.Context
import android.provider.Telephony
import com.aasif.watchdog.core.model.Sms
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Reads SMS from the system inbox. Requires READ_SMS. */
class SmsReader(private val context: Context) {

    /** Inbox messages received after [sinceMillis], oldest first, plus the newest timestamp seen. */
    fun readSince(sinceMillis: Long): Pair<List<Sms>, Long> {
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val messages = mutableListOf<Sms>()
        var newest = sinceMillis
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            "${Telephony.Sms.DATE} > ?",
            arrayOf(sinceMillis.toString()),
            "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            val address = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val body = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val date = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) {
                val millis = c.getLong(date)
                messages += Sms(c.getString(address).orEmpty(), c.getString(body).orEmpty(), millis.toLocalDateTime())
                newest = maxOf(newest, millis)
            }
        }
        return messages to newest
    }
}

fun Long.toLocalDateTime(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())
