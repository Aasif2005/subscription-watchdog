package com.aasif.watchdog.core.parser

import com.aasif.watchdog.core.model.Money
import java.time.LocalDate

/** What a subscription-related mail is about. */
enum class MailKind {
    /** "Your plan renews on…", "Next payment ₹139/month, payment date 15 Aug", "your trial will end": the next charge, ahead of time. */
    RENEWAL_NOTICE,

    /**
     * A store's order receipt ("You've made a subscription purchase … Total: ₹1,999.00/month"): the plan
     * was just bought. It often states the next renewal date too, which does not make it a notice.
     */
    PURCHASE,

    /** "Your subscription has been cancelled", "auto-renew is turned off": the AutoPay is off. */
    CANCELLED,

    /** "Your subscription has expired / has ended": paid access is over. */
    EXPIRED,

    /**
     * "Your payment was declined", "an issue processing your payment", "will be cancelled": the plan is
     * at risk, which is not the same as cancelled. Nothing was charged and nothing has ended yet.
     */
    PAYMENT_ISSUE,

    /** A terms-of-service or privacy notice: sent to every user, says nothing about their plan. */
    ACCOUNT_NOTICE,

    /** "Come back for 50% off": a win-back or advert that uses the same words. */
    MARKETING,
    OTHER,
}

/** What the analyzer decided to do with a mail. */
enum class MailVerdict {
    /** Becomes a stored event. */
    EVENT,

    /** A real subscription mail from a trusted sender that states something worth knowing, with no event to store. */
    SIGNAL,

    /** Not about the user's plan, or from a sender that cannot be trusted. */
    IGNORE,
}

/** A fact the mail states, which is what makes it useful. */
enum class MailFact { APP, AMOUNT, NEXT_DATE, END_DATE, PERIOD, ORDER_REF, TRIAL }

data class MailAssessment(val kind: MailKind, val verdict: MailVerdict, val facts: Set<MailFact>, val reason: String) {
    /** Names the app and states at least one more thing about the plan. */
    val useful: Boolean get() = verdict != MailVerdict.IGNORE && MailFact.APP in facts && facts.size > 1
}

/** The next charge a notice announces. */
data class RenewalNotice(val amount: Money?, val date: LocalDate)

/**
 * Reads what kind of subscription mail a message is, and the labelled facts in it, from wording
 * every sender repeats: a notice says when and how much, a cancellation says it was cancelled, an
 * expiry says it ended, a payment problem says the payment was declined. Pure rules, on the device;
 * the subject is read first because a service keeps its subjects stable while it rewrites its bodies.
 *
 * Tense matters: "has been cancelled" is news, "will be cancelled" is a warning that comes with a
 * declined payment, and mistaking one for the other ended plans that were only failing to pay.
 */
internal object MailAnalyzer {
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val subscriptionNoun = """(subscription|membership|plan|premium|auto[\s\-]?renew\w*|auto[\s\-]?pay|trial)"""

    /** A win-back or advert: it may say "your membership has ended" and still sell something. */
    private val marketing = rx(
        """\b(come\s+back|we\s+miss\s+you|miss\s+you|re-?join|re-?subscribe|re-?activate|restart\s+your|special\s+offer|limited[\s\-]time|"""
            + """\d+\s*%\s*off|save\s+\d|exclusive\s+offer|get\s+\d+\s+months?\s+(free|for))\b""",
    )

    /**
     * Sent to every user about the service itself. Decided from the subject, or the body's own
     * announcement: receipts and cancellations also link "the Terms of Service" in their footer.
     */
    private val accountNoticeSubject = rx("""\b(updates?|changes?|revisions?)\s+(to|in)\s+(our\s+|the\s+)?[\w\s]{0,30}(terms|privacy|polic(y|ies))\b|\bterms\s+of\s+service\s+(update|change)|\bprivacy\s+(policy|notice)\b""")
    private val accountNoticeBody = rx("""\b(making|we('re|\s+are)\s+making)\s+(some\s+)?changes\s+to\s+(our\s+)?[\w\s]{0,30}(terms|privacy)""")

    private val paymentIssue = rx(
        """\b(payment|charge|renewal)\b[^.]{0,40}\b(failed|declined|unsuccessful|was\s+not\s+successful)\b""" +
            """|\bissue\s+(processing|with)\s+your\s+(recent\s+)?payment\b|\bunable\s+to\s+(charge|process)\b|\bsuspended\s+due\s+to\s+(a\s+)?payment\b""" +
            """|\bcould\s*n[o']t\s+(process|charge)\b|\bcard\s+(was\s+)?declined\b""",
    )

    /** Past tense only: "has been", never "will be". */
    private val cancelled = rx(
        """\b$subscriptionNoun\b[^.]{0,90}?\b(has\s+been|have\s+been|was|is\s+now|is)\s+(successfully\s+)?(cancell?ed|terminated)\b""" +
            """|\byou('ve|\s+have)\s+(successfully\s+)?cancell?ed\s+your\b|\bwill\s+not\s+(renew|be\s+renewed)\b|\bwon'?t\s+(renew|be\s+renewed)\b""" +
            """|\bauto[\s\-]?renew\w*\s+(is\s+|has\s+been\s+)?(now\s+)?(off|turned\s+off|disabled|stopped)\b""",
    )

    private val expired = rx("""\b$subscriptionNoun\b[^.]{0,60}?\b(has|have)\s+(now\s+)?(expired|ended)\b|\b$subscriptionNoun\b[^.]{0,30}\bexpired\b""")

    private val purchase = rx(
        """\border\s+receipt\b|\bsubscription\s+purchase\b|\byour\s+receipt\s+from\b|\bthank\s+you\s+for\s+your\s+(purchase|order|subscription)\b""" +
            """|\byou('ve|\s+have)\s+(made\s+a\s+)?(subscription\s+)?purchased?\b""",
    )
    private val purchaseTotal = rx("""\b(total|amount\s+paid|amount\s+charged|price)\b""")

    // Labels that say what a number or date is. The label, not the position, is the evidence.
    private val nextAmount = rx("""\b(next\s+(payment|charge|billing)(\s+amount)?|renewal\s+(price|amount)|amount\s+due|you('ll|\s+will)\s+be\s+(automatically\s+)?charged)""")
    private val amountDue = rx("""\bamount\s+due""")
    private val nextDate = rx(
        """\b((payment|billing|renewal|charge)\s+date|next\s+(payment|billing|charge)\s+date|renews?\s+on|will\s+(automatically\s+)?renew\s+on|will\s+be\s+(automatically\s+)?charged\s+on|charged\s+on)""",
    )
    private val endDate = rx("""\b(access\s+(ends?|until|till)|(will\s+)?(end|expire)s?\s+on|valid\s+(till|until)|until)""")
    private val trialWord = rx("""\btrial\b""")
    private val period = rx("""(/|\bper\s+|\bbilled\s+)(month|year|week|quarter|annual|monthly|yearly)""")

    fun kind(subject: String?, body: String, received: LocalDate): MailKind {
        val head = subject.orEmpty()
        val text = if (head.isBlank()) body else "$head. $body"
        return when {
            accountNoticeSubject.containsMatchIn(head) || accountNoticeBody.containsMatchIn(body) -> MailKind.ACCOUNT_NOTICE
            marketing.containsMatchIn(head) -> MailKind.MARKETING
            cancelled.containsMatchIn(text) -> if (marketing.containsMatchIn(body)) MailKind.MARKETING else MailKind.CANCELLED
            expired.containsMatchIn(text) -> if (marketing.containsMatchIn(body)) MailKind.MARKETING else MailKind.EXPIRED
            paymentIssue.containsMatchIn(text) -> MailKind.PAYMENT_ISSUE
            purchase.containsMatchIn(text) && (Extractors.orderRef(body) != null || purchaseAmount(body) != null) -> MailKind.PURCHASE
            renewal(body, received) != null -> MailKind.RENEWAL_NOTICE
            else -> MailKind.OTHER
        }
    }

    /**
     * What a mail is worth. [appNamed] is whether it names a catalogued service (not a billing channel's
     * own name) and [trusted] whether its signature is that service's or the store's; without both, a
     * mail is believed about nothing.
     */
    fun assess(subject: String?, body: String, received: LocalDate, appNamed: Boolean, trusted: Boolean): MailAssessment {
        val kind = kind(subject, body, received)
        val facts = mutableSetOf<MailFact>()
        if (appNamed) facts += MailFact.APP
        if (Extractors.orderRef(body) != null) facts += MailFact.ORDER_REF
        if (trialWord.containsMatchIn(body) || trialWord.containsMatchIn(subject.orEmpty())) facts += MailFact.TRIAL
        when (kind) {
            MailKind.RENEWAL_NOTICE -> renewal(body, received)?.let {
                if (it.amount != null) facts += MailFact.AMOUNT
                facts += MailFact.NEXT_DATE
                if (period.containsMatchIn(body)) facts += MailFact.PERIOD
            }
            MailKind.PURCHASE -> {
                if (purchaseAmount(body) != null) facts += MailFact.AMOUNT
                if (period.containsMatchIn(body)) facts += MailFact.PERIOD
            }
            MailKind.PAYMENT_ISSUE -> if (Extractors.amountAfter(body, amountDue) != null) facts += MailFact.AMOUNT
            MailKind.CANCELLED, MailKind.EXPIRED -> accessUntil(body, received)?.let { facts += MailFact.END_DATE }
            else -> Unit
        }
        val verdict = when {
            kind == MailKind.OTHER || kind == MailKind.MARKETING || kind == MailKind.ACCOUNT_NOTICE -> MailVerdict.IGNORE
            !appNamed -> MailVerdict.IGNORE
            !trusted -> MailVerdict.IGNORE
            kind == MailKind.PAYMENT_ISSUE -> MailVerdict.SIGNAL
            else -> MailVerdict.EVENT
        }
        val reason = when {
            kind == MailKind.OTHER -> "says nothing about a plan"
            kind == MailKind.MARKETING -> "an advert"
            kind == MailKind.ACCOUNT_NOTICE -> "a notice sent to every user"
            !appNamed -> "names no service the catalog knows"
            !trusted -> "not signed by the service or its store"
            else -> kind.name.lowercase()
        }
        return MailAssessment(kind, verdict, facts, reason)
    }

    /**
     * The next charge a notice states: its date, and its amount when it names one. A notice with
     * no date is not a notice — there is nothing to warn about.
     */
    fun renewal(body: String, received: LocalDate): RenewalNotice? {
        val date = Extractors.dateAfter(body, nextDate, received) ?: return null
        return RenewalNotice(Extractors.amountAfter(body, nextAmount), date)
    }

    /**
     * What a receipt says was paid: the amount labelled Total (or Price, Amount paid). Labelled, so
     * "₹1,999.00/month" counts here although its price-tag shape is no proof of payment elsewhere.
     */
    fun purchaseAmount(body: String): Money? = Extractors.amountAfter(body, purchaseTotal)

    /** The day paid access stops, when a cancellation or expiry mail states one in the future. */
    fun accessUntil(body: String, received: LocalDate): LocalDate? =
        Extractors.dateAfter(body, endDate, received)?.takeIf { it.isAfter(received) }
}
