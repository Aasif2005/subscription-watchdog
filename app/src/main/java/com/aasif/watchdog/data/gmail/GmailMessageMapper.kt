package com.aasif.watchdog.data.gmail

import android.util.Base64
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.core.parser.HtmlText
import com.aasif.watchdog.core.parser.SmsClassifier
import com.aasif.watchdog.data.sms.toLocalDateTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns one Gmail message into the same [Sms] shape the SMS and notification paths produce, so
 * all three share one parser. The e-mail analogue of `PaymentNotificationListener.toMessage`.
 */
object GmailMessageMapper {
    /** Receipts state the amount near the top; the rest is footers that only slow the regexes. */
    private const val MAX_BODY_CHARS = 4_000

    private val angleAddress = Regex("""<([^>]+)>""")

    fun toSms(message: JSONObject): Sms? {
        val payload = message.optJSONObject("payload") ?: return null
        val headers = payload.optJSONArray("headers")
        val from = header(headers, "From") ?: return null
        val receivedAt = message.optString("internalDate").toLongOrNull() ?: return null

        val text = bodyText(payload) ?: return null
        val body = listOf(header(headers, "Subject").orEmpty(), text)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .take(MAX_BODY_CHARS)
        if (body.isBlank() || SmsClassifier.isPromotional(body)) return null

        return Sms(sender = address(from), body = body, receivedAt = receivedAt.toLocalDateTime())
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
