package com.aasif.watchdog.data.gmail

import android.util.Log
import com.aasif.watchdog.core.gmail.GmailQueryBuilder
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.core.parser.MailAssessment
import com.aasif.watchdog.core.parser.SmsClassifier
import java.time.LocalDate
import java.time.ZoneId

/**
 * Debug-build audit of what the Gmail import does with each message, to find valid subscription
 * mail it misses. Everything goes to logcat under `MailAudit` and is only ever a sender's domain,
 * a date, a stage and a few yes/no flags: never a subject, a body, an address or a number. Callers
 * guard every use with `BuildConfig.DEBUG`.
 */
internal object MailAudit {
    private const val TAG = "MailAudit"

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val words = listOf(
        "cancel" to rx("""\b(cancell?ed|cancell?ation|terminated|has\s+ended)\b"""),
        "expire" to rx("""\b(expired?|expiring|expires)\b"""),
        "renew" to rx("""\b(renew\w*|next\s+billing)\b"""),
        "trial" to rx("""\btrial\b"""),
        "order" to rx("""\b(order|purchase)\b"""),
        "refund" to rx("""\brefund"""),
        "failed" to rx("""\b(failed|declined|unsuccessful)\b"""),
    )

    /** The promo word that made the classifier call a mail an advert, if any. */
    private val promo = rx("""\b(cashback|offers?|rewards?|coupons?|discount|scratch\s+card|win|refer|invite|hurry|limited\s+time)\b|%\s*off|save\s+up\s+to|\bget\s+(rs\.?|inr|₹)""")
    private val anyAmount = rx("""(₹|rs\.?|inr|\$|usd|€|£)\s*[0-9]""")

    /** Subject words from a fixed list, so a subject's shape is visible without its text. */
    private val subjectVocab = listOf(
        "receipt", "invoice", "welcome", "premium", "plan", "subscription", "subscribed", "cancel", "confirm",
        "payment", "order", "renew", "trial", "expire", "offer", "discount", "deal", "save", "free", "upgrade",
        "thank", "charged", "billing", "price", "reminder", "ending", "ended", "paused", "failed",
    )

    private fun subjectShape(subject: String?): String =
        subject?.lowercase()?.let { s -> subjectVocab.filter { it in s } }?.joinToString(",").orEmpty().ifEmpty { "-" }

    private fun flags(text: String): String =
        words.filter { it.second.containsMatchIn(text) }.joinToString(",") { it.first }.ifEmpty { "-" }

    private fun domainOf(from: String) = from.substringAfterLast('@').trim('>', ' ').lowercase()

    /** The mapper threw the message away before the parser saw it. */
    fun dropped(from: String, reason: String, body: String?, subject: String? = null) {
        val text = body.orEmpty()
        Log.i(
            TAG,
            "sender=${domainOf(from)} stage=mapper:$reason flags=${flags(text)} amt=${anyAmount.containsMatchIn(text)} " +
                "promoHit=${promo.find(text)?.value?.lowercase()?.replace(' ', '_') ?: "-"} subj=${subjectShape(subject)}",
        )
    }

    /** What the parser and the store did with a message the mapper kept. */
    fun parsed(sms: Sms, stage: String, assessment: MailAssessment) {
        val b = sms.body
        Log.i(
            TAG,
            "sender=${domainOf(sms.sender)} on=${sms.receivedAt.toLocalDate()} stage=$stage mail=${assessment.kind} verdict=${assessment.verdict} " +
                "useful=${assessment.useful} facts=${assessment.facts.joinToString(",").ifEmpty { "-" }} why=${assessment.reason.replace(' ', '_')} kind=${SmsClassifier.classify(b)} " +
                "pay=${SmsClassifier.hasPaymentEvidence(b)} recurring=${SmsClassifier.isRecurring(b)} " +
                "signed=${sms.verifiedDomain != null} flags=${flags(b)}",
        )
    }

    private val probes = listOf(
        "cancel" to """cancelled OR canceled OR cancellation OR terminated OR "has ended" OR expired OR expiring OR "will expire"""",
        "renewal-trial" to """"will renew" OR "renews on" OR renewal OR trial OR "will be charged" OR "next billing" OR "auto-renew"""",
        "order-plan" to """order OR purchase OR premium OR plan OR upgrade OR downgrade OR "payment failed" OR "payment declined" OR refund""",
    )

    /**
     * Mail that wider searches find and the import's own query does not. Lists ids only, then reads
     * the headers of a few of the extra ones. Returns false if Gmail stopped answering partway.
     */
    suspend fun probe(api: GmailApiClient, token: String): Boolean {
        val after = LocalDate.now().minusDays(400).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        val standard = api.listIds(token, GmailQueryBuilder.build(after), 2_000)?.toSet() ?: return false
        Log.i(TAG, "probe standard listed=${standard.size}")
        var complete = true
        for ((name, terms) in probes) {
            val ids = api.listIds(token, "($terms) after:$after -in:spam -in:trash", 400)
            if (ids == null) { complete = false; continue }
            val extra = ids.filter { it !in standard }
            Log.i(TAG, "probe $name listed=${ids.size} notInStandard=${extra.size}")
            for (id in extra.take(60)) {
                val meta = api.metadata(token, id) ?: run { complete = false; null } ?: break
                val headers = meta.optJSONObject("payload")?.optJSONArray("headers")
                var from = ""
                var subject = ""
                for (i in 0 until (headers?.length() ?: 0)) {
                    val h = headers?.optJSONObject(i) ?: continue
                    when (h.optString("name").lowercase()) {
                        "from" -> from = h.optString("value")
                        "subject" -> subject = h.optString("value")
                    }
                }
                val labels = meta.optJSONArray("labelIds")
                val category = (0 until (labels?.length() ?: 0)).map { labels!!.optString(it) }.firstOrNull { it.startsWith("CATEGORY_") } ?: "PRIMARY"
                Log.i(TAG, "probe $name extra sender=${domainOf(from)} cat=$category subjectFlags=${flags(subject)}")
            }
        }
        return complete
    }
}
