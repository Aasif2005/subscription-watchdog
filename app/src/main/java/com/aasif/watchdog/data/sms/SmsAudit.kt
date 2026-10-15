package com.aasif.watchdog.data.sms

import android.util.Log
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.core.parser.SmsClassifier

/**
 * Debug-build audit of what the SMS and payment-notification paths do with each message, to find
 * subscription texts they miss. Everything goes to logcat under `SmsAudit` and is only a sender's
 * suffix, a date, a stage and a few yes/no flags: never a body, a number or an amount. Callers guard
 * every use with `BuildConfig.DEBUG`.
 */
internal object SmsAudit {
    private const val TAG = "SmsAudit"

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val words = listOf(
        "mandate" to rx("""\bmandate\b"""),
        "autopay" to rx("""\bauto\s*-?pay\b"""),
        "revoke" to rx("""\b(revoked?|cancell?ed)\b"""),
        "debit" to rx("""\b(debited|debit)\b"""),
        "upcoming" to rx("""\b(upcoming|will\s+be\s+debited|pre-?debit)\b"""),
        "failed" to rx("""\b(failed|declined|unsuccessful)\b"""),
        "otp" to rx("""\botp\b"""),
    )
    private val promo = rx("""\b(cashback|offers?|rewards?|coupons?|discount|win|refer|hurry)\b|%\s*off""")

    private fun flags(text: String): String =
        words.filter { it.second.containsMatchIn(text) }.joinToString(",") { it.first }.ifEmpty { "-" }

    /** Sender ids look like "VM-IOBCHN-S": the longest letters-only part names the sender, the rest is routing. */
    private fun senderShape(sender: String): String =
        if (sender.none { it.isLetter() }) "number"
        else sender.split('-').filter { p -> p.isNotEmpty() && p.all { it.isLetter() } }.maxByOrNull { it.length }?.take(10) ?: "other"

    /** What the parser and the store did with one message. */
    fun message(sms: Sms, source: String, stage: String) {
        val b = sms.body
        Log.i(
            TAG,
            "src=$source sender=${senderShape(sms.sender)} on=${sms.receivedAt.toLocalDate()} stage=$stage " +
                "kind=${SmsClassifier.classify(b)} pay=${SmsClassifier.hasPaymentEvidence(b)} " +
                "recurring=${SmsClassifier.isRecurring(b)} promo=${promo.containsMatchIn(b)} flags=${flags(b)}",
        )
    }

    /** One line per ingest call, so a scan can be judged at a glance. */
    fun summary(source: String, read: Int, stored: Int, unparsed: Int, notWorth: Int, duplicate: Int) {
        Log.i(TAG, "summary src=$source read=$read stored=$stored parserNull=$unparsed notWorthKeeping=$notWorth duplicate=$duplicate")
    }
}
