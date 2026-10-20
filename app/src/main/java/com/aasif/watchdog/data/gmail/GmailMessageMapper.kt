package com.aasif.watchdog.data.gmail

import android.util.Base64
import com.aasif.watchdog.BuildConfig
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.core.parser.HtmlText
import com.aasif.watchdog.core.parser.SmsClassifier
import com.aasif.watchdog.data.toLocalDateTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns one Gmail message into the same [Sms] message shape the notification path produces, so
 * all three share one parser. The e-mail analogue of `PaymentNotificationListener.toMessage`.
 *
 * This is also where a message earns the right to be read as a payment at all. Gmail's own
 * category label and a handful of bulk-mail headers are the cheapest, most reliable way to tell a
 * newsletter from a receipt — cheaper than any regex on the body — and DKIM is what lets
 * [com.aasif.watchdog.core.parser.SmsParser] trust the *sending* domain over a `From` header that
 * anyone can put anything in.
 */
object GmailMessageMapper {
    /** Receipts state the amount near the top; the rest is footers that only slow the regexes. */
    private const val MAX_BODY_CHARS = 4_000

    private val angleAddress = Regex("""<([^>]+)>""")

    /** Gmail's own classification of the message; present in `format=full` at no extra cost. */
    private const val CATEGORY_PROMOTIONS = "CATEGORY_PROMOTIONS"

    private val bulkMailHeader = Regex("""^(bulk|list)$""", RegexOption.IGNORE_CASE)

    /** `Authentication-Results: mx.google.com; dkim=pass header.i=@netflix.com header.d=netflix.com`. */
    private val dkimPass = Regex("""dkim=pass""", RegexOption.IGNORE_CASE)
    private val dkimDomain = Regex("""header\.d=([A-Za-z0-9.\-]+)""", RegexOption.IGNORE_CASE)

    /**
     * Google's own mail is reported as `dkim=pass header.i=@google.com` with no `header.d`, so the
     * signing identity is the fallback; its domain part is what names the sender.
     */
    private val dkimIdentity = Regex("""header\.i=(?:[^@\s;]*@)?([A-Za-z0-9.\-]+)""", RegexOption.IGNORE_CASE)

    fun toSms(message: JSONObject): Sms? {
        val payload = message.optJSONObject("payload") ?: return null
        val headers = payload.optJSONArray("headers")
        val from = header(headers, "From") ?: return null
        val receivedAt = message.optString("internalDate").toLongOrNull() ?: return null

        val text = bodyText(payload) ?: return dropped(from, "no-body", null)
        val body = listOf(header(headers, "Subject").orEmpty(), text)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .take(MAX_BODY_CHARS)
        if (body.isBlank()) return dropped(from, "blank", null)
        // "Welcome to Premium" mail is sent as bulk mail, often lands in Promotions and reads like
        // marketing, yet it is what names the app behind a Google Play charge. It is let through
        // here and judged by the parser, which keeps it only from a signed, catalogued sender and
        // never lets it create a subscription on its own.
        val started = SmsClassifier.isSubscriptionStarted(body)
        if (!started && SmsClassifier.isPromotionalMail(body)) return dropped(from, "promo-words", body, header(headers, "Subject"))

        val labels = message.optJSONArray("labelIds")
        val isBulkMail = bulkMailHeader.matches(header(headers, "Precedence").orEmpty()) ||
            header(headers, "List-Unsubscribe") != null
        // Marketing from a genuine merchant is still marketing: Coursera's own promotional mail
        // is signed by coursera.org and would pass a sender check, which is why this is a
        // separate rule from the DKIM one below rather than folded into it.
        if (!started && labelHas(labels, CATEGORY_PROMOTIONS)) return dropped(from, "promotions-label", body, header(headers, "Subject"))
        if (!started && isBulkMail && !SmsClassifier.hasPaymentEvidence(body)) return dropped(from, "bulk-no-payment", body, header(headers, "Subject"))

        val verifiedDomain = header(headers, "Authentication-Results")
            ?.takeIf { dkimPass.containsMatchIn(it) }
            ?.let { (dkimDomain.find(it) ?: dkimIdentity.find(it))?.groupValues?.get(1)?.lowercase() }

        return Sms(sender = address(from), body = body, receivedAt = receivedAt.toLocalDateTime(), verifiedDomain = verifiedDomain, subject = header(headers, "Subject"))
    }

    /** Debug builds say why a message was thrown away, see [MailAudit]. */
    private fun dropped(from: String, reason: String, body: String?, subject: String? = null): Sms? {
        if (BuildConfig.DEBUG) MailAudit.dropped(from, reason, body, subject)
        return null
    }

    private fun labelHas(labels: JSONArray?, target: String): Boolean {
        if (labels == null) return false
        for (i in 0 until labels.length()) if (labels.optString(i) == target) return true
        return false
    }

    /** Plain text if the sender provided it, otherwise the HTML part flattened. */
    private fun bodyText(payload: JSONObject): String? =
        partData(payload, "text/plain")?.let(::decode)
            ?: partData(payload, "text/html")?.let { HtmlText.toPlainText(decode(it)) }

    private fun partData(part: JSONObject, mimeType: String): String? {
        if (part.optString("mimeType").startsWith(mimeType)) {
            part.optJSONObject("body")?.optString("data").orEmpty().ifEmpty { null }?.let { return it }
        }
        val parts = part.optJSONArray("parts") ?: return null
        for (i in 0 until parts.length()) {
            parts.optJSONObject(i)?.let { partData(it, mimeType) }?.let { return it }
        }
        return null
    }

    private fun decode(data: String): String =
        runCatching { String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8) }
            .getOrDefault("")

    private fun header(headers: JSONArray?, name: String): String? {
        if (headers == null) return null
        for (i in 0 until headers.length()) {
            val h = headers.optJSONObject(i) ?: continue
            if (h.optString("name").equals(name, ignoreCase = true)) {
                return h.optString("value").ifBlank { null }
            }
        }
        return null
    }

    /** "Netflix <info@netflix.com>" -> "info@netflix.com". */
    private fun address(from: String): String =
        angleAddress.find(from)?.groupValues?.get(1)?.trim() ?: from.trim()
}
