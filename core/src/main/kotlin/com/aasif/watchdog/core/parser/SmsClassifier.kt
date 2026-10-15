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

    /** A store receipt's footer, "View the Google Play Refund Policy": a link, not a refund. */
    private val refundPolicy = rx("""\brefund\s+(policy|policies)\b""")

    /** "unless cancelled", "until cancelled": terms of a live plan, not news that it ended. */
    private val conditionalCancel = rx("""\b(unless|until|before|if)\s+(\w+\s+){0,2}(cancell?ed|paused|stopped|terminated|deactivated|deleted)\b""")

    fun classify(body: String): SmsKind {
        val recurring = recurringWord.containsMatchIn(body)
        val debit = debitWord.containsMatchIn(body)
        val revoked = revokedWord.containsMatchIn(conditionalCancel.replace(body, " "))
        return when {
            otpWord.containsMatchIn(body) && !debit -> SmsKind.IRRELEVANT
            recurring && revoked -> SmsKind.MANDATE_REVOKED
            recurring && createdWord.containsMatchIn(body) -> SmsKind.MANDATE_CREATED
            preDebitWord.containsMatchIn(body) && (recurring || debit) -> SmsKind.PRE_DEBIT
            failedWord.containsMatchIn(refundPolicy.replace(body, " ")) -> SmsKind.IRRELEVANT
            debit -> SmsKind.DEBIT
            creditWord.containsMatchIn(body) -> SmsKind.IRRELEVANT
            else -> SmsKind.IRRELEVANT
        }
    }

    fun isRecurring(body: String): Boolean = recurringWord.containsMatchIn(body)

    private val startedWord = rx(
        """\bwelcome\s+to\b|\byou('re|\s+are)\s+(now\s+)?subscribed\b|\bthanks?\s+(you\s+)?for\s+(subscribing|joining|upgrading)\b""" +
            """|\b(subscription|membership|plan|premium)\s+(is\s+)?(now\s+)?(active|started|has\s+started|has\s+begun)\b|\byour\s+\w+\s+(subscription|membership|plan)\s+has\s+started\b""",
    )
    private val paidTierWord = rx("""\b(premium|subscription|membership|plan|pro|plus|super|vip)\b""")

    /**
     * "Welcome to Spotify Premium", "Your membership has started": a service saying a paid plan
     * just began. It names the service and often the plan length, but it is not a payment, so it
     * only ever names or dates a plan that a bank text or receipt already shows (see
     * `SubscriptionEngine` and `nameChannelEvents`), never creates one by itself.
     */
    fun isSubscriptionStarted(body: String): Boolean =
        startedWord.containsMatchIn(body) && paidTierWord.containsMatchIn(body) && !revokedWord.containsMatchIn(conditionalCancel.replace(body, " "))

    private val playCancelled = rx("""\bsubscription\b[^.]{0,80}?\bon\s+google\s+play\s+has\s+been\s+cancell?ed\b""")

    /**
     * "Your <app> subscription ... on Google Play has been cancelled", with the order number that
     * says which purchase. It states no amount; the parser believes it only when Google signed it.
     */
    fun isStoreCancellation(body: String): Boolean =
        playCancelled.containsMatchIn(body) && Extractors.orderRef(body) != null

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

    /**
     * The mail version of [isPromotional]. An advert only names a price; a receipt says money was
     * charged, and receipts use the same words ("Individual Offer (Apple Music)"), so proof of
     * payment outranks the keyword.
     */
    fun isPromotionalMail(body: String): Boolean = isPromotional(body) && !hasPaymentEvidence(body)
}
