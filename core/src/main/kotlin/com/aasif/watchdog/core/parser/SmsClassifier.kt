package com.aasif.watchdog.core.parser

enum class SmsKind { MANDATE_CREATED, PRE_DEBIT, DEBIT, MANDATE_REVOKED, IRRELEVANT }

/**
 * Decides what a bank / UPI SMS is about using keyword rules. Order matters: a revocation
 * message often mentions the original mandate, and a mandate confirmation may mention the first
 * debit, so the more specific kinds are checked first.
 */
object SmsClassifier {
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /**
     * "si" used to be here as a bare alternative and, matched case-insensitively, fired on
     * ordinary words; the real standing-instruction spellings are explicit.
     */
    private val recurringWord = rx(
        """\b(mandate|e-?mandate|auto\s?pay|auto[\s\-]?debit|auto[\s\-]?renew\w*|standing\s+instruction|recurring|subscription|e-?nach|nach|ach|ecs|umn)\b""" +
            """|\bSI[\s\-](?:ref|no|umn|debit)\b|\bmandate\s*ref\b""",
    )
    private val revokedWord = rx("""\b(revoked|revocation|cancell?ed|paused|deleted|deactivated|terminated|stopped)\b""")
    private val createdWord = rx("""\b(created|registered|registration|set\s?up|activated|approved)\b""")
    private val preDebitWord = rx("""will\s+be\s+(debited|deducted|charged|executed|processed|auto-?debited)|to\s+be\s+debited|scheduled\s+(for|on)|\bdue\s+(on|for|date)\b|\bupcoming\b|pre-?debit""")
    private val debitWord = rx("""\b(debited|deducted|spent|paid|sent|charged|withdrawn|txn\s+of|transaction\s+of|purchase|payment\s+of)\b""")
    private val failedWord = rx("""\b(failed|declined|unsuccessful|insufficient|reversed|refund(ed)?|could\s+not\s+be)\b""")
    private val otpWord = rx("""\b(otp|one\s+time\s+password|verification\s+code)\b""")
    private val creditWord = rx("""\bcredited\b""")
    private val promoWord = rx("""\b(cashback|offers?|rewards?|coupons?|discount|scratch\s+card|win|refer|invite|hurry|limited\s+time)\b|%\s*off|save\s+up\s+to|\bget\s+(rs\.?|inr|₹)""")

    fun classify(body: String): SmsKind {
        val recurring = recurringWord.containsMatchIn(body)
        val debit = debitWord.containsMatchIn(body)
        return when {
            otpWord.containsMatchIn(body) && !debit -> SmsKind.IRRELEVANT
            recurring && revokedWord.containsMatchIn(body) -> SmsKind.MANDATE_REVOKED
            recurring && createdWord.containsMatchIn(body) -> SmsKind.MANDATE_CREATED
            preDebitWord.containsMatchIn(body) && (recurring || debit) -> SmsKind.PRE_DEBIT
            failedWord.containsMatchIn(body) -> SmsKind.IRRELEVANT
            debit -> SmsKind.DEBIT
            creditWord.containsMatchIn(body) -> SmsKind.IRRELEVANT
            else -> SmsKind.IRRELEVANT
        }
    }

    fun isRecurring(body: String): Boolean = recurringWord.containsMatchIn(body)

    /**
     * Whether the text is about money that actually moved, or is about to.
     *
     * A bank SMS is only ever sent because something happened to an account, so this is not asked
     * of one. Mail is different: an advert for Coursera Plus contains the word "subscription", a
     * price and a billing period, and reached the dashboard as a subscription the user never had.
     * [Extractors.paidAmount] already requires a payment verb next to the figure and rejects the
     * shape of a price tag ("₹7,499/year"), which is exactly what tells a receipt from an advert.
     */
    fun hasPaymentEvidence(body: String): Boolean = Extractors.paidAmount(body) != null

    /**
     * UPI app notifications mix marketing ("Set up AutoPay for Netflix, get ₹50 cashback") with
     * real alerts, and a promo would otherwise read as a new mandate.
     */
    fun isPromotional(body: String): Boolean = promoWord.containsMatchIn(body)
}
