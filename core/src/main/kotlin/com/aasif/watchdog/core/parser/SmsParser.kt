package com.aasif.watchdog.core.parser

import com.aasif.watchdog.core.merchant.MerchantNormalizer
import com.aasif.watchdog.core.merchant.ResolvedMerchant
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.Sms
import java.time.LocalDate

/**
 * Turns one SMS into a [PaymentEvent], or null if it is not a payment message we understand.
 * Runs entirely on-device; nothing here does I/O.
 */
class SmsParser(
    private val normalizer: MerchantNormalizer = MerchantNormalizer(),
    /**
     * Banks send from DLT headers like "VM-HDFCBK". Messages from a normal phone number are
     * ignored by default so a forwarded or spoofed SMS can't invent a subscription.
     */
    private val allowPersonalSenders: Boolean = false,
    /**
     * E-mail is a far noisier channel than a DLT-header bank SMS: bodies run to thousands of
     * characters and carry marketing footers, so the weak merchant cues that read a terse alert
     * correctly ("to", "for", "at") match ordinary prose instead — a bank's transaction alert
     * turned into a subscription called "Idfcfirst Bank Instead".
     *
     * Mail therefore has to name a service the catalog knows or say outright that the payment
     * recurs, and its merchant is read from the sender, which a receipt states far more reliably
     * than its body does.
     */
    private val fromEmail: Boolean = false,
) {
    private val phoneNumber = Regex("""^\+?\d[\d\s\-]{6,}$""")

    // Keyword directly before a date, optionally followed by "date": "Start date: 05/09/26".
    private val startContext = Regex("""(start|starting|from|first\s+debit|commenc\w*|effective|begin\w*|w\.?e\.?f\.?|next\s+debit|debit|on)(\s+(date|on|from))?\W*$""")
    private val endContext = Regex("""(till|until|upto|up\s+to|valid|validity|expir\w*|end)(\s+(date|on|till|upto))?\W*$""")

    fun parse(sms: Sms): PaymentEvent? {
        val event = parseEvent(sms) ?: return null
        val text = sms.body.replace('\n', ' ')
        // A mail with no mandate number may carry the store's order number, which ties a receipt to
        // the cancellation of the same purchase in the same way.
        val ref = Extractors.mandateRef(text, allowBare = !fromEmail) ?: if (fromEmail) Extractors.orderRef(text) else null
        return if (ref == null) event else event.withMandateRef(ref)
    }

    private fun parseEvent(sms: Sms): PaymentEvent? {
        if (!allowPersonalSenders && phoneNumber.matches(sms.sender.trim())) return null
        val body = sms.body.replace('\n', ' ')
        val received = sms.receivedAt.toLocalDate()
        if (fromEmail) storeCancellation(sms, body, received)?.let { return it }
        if (fromEmail) {
            val assessment = assessMail(sms)
            // A payment problem or a legal notice must never reach the payment path below, where its
            // amount and the word "cancelled" would read as a cancellation.
            if (assessment.kind == MailKind.PAYMENT_ISSUE || assessment.kind == MailKind.ACCOUNT_NOTICE) return null
            serviceNotice(sms, body, received, assessment)?.let { return it }
            if (assessment.kind == MailKind.PURCHASE && assessment.verdict == MailVerdict.EVENT) {
                val m = merchant(sms, body)
                (trialReceipt(m, body, received) ?: introReceipt(sms, m, body, received) ?: paidReceipt(sms, m, body, received))?.let { return it }
            }
        }
        if (fromEmail && !readableAsPayment(sms, body)) return subscriptionStarted(sms, body, received)
        val m = merchant(sms, body)
        if (fromEmail) {
            trialReceipt(m, body, received)?.let { return it }
            introReceipt(sms, m, body, received)?.let { return it }
        }

        return when (SmsClassifier.classify(body)) {
            SmsKind.MANDATE_CREATED -> mandateCreated(m, body, received)
            SmsKind.PRE_DEBIT -> preDebit(m, body, received)
            SmsKind.DEBIT -> debit(m, body, received)
            SmsKind.MANDATE_REVOKED -> mandateRevoked(m, body, received)
            SmsKind.IRRELEVANT -> null
        }
    }

    private fun PaymentEvent.withMandateRef(ref: String): PaymentEvent = when (this) {
        is PaymentEvent.MandateCreated -> copy(mandateRef = ref)
        is PaymentEvent.PreDebitNotice -> copy(mandateRef = ref)
        is PaymentEvent.Debit -> copy(mandateRef = ref)
        is PaymentEvent.MandateRevoked -> copy(mandateRef = ref)
    }

    /**
     * "Welcome to Spotify Premium" with no charge in it: worth keeping only when a signed mail
     * from a service the catalog knows says a paid plan started. It carries the merchant, the day
     * and any plan length the mail states, and no amount, which is what marks it as an
     * announcement rather than a payment.
     */
    private fun subscriptionStarted(sms: Sms, body: String, received: LocalDate): PaymentEvent.MandateCreated? {
        if (sms.verifiedDomain == null || !SmsClassifier.isSubscriptionStarted(body)) return null
        val known = normalizer.findKnownIn(senderIdentity(sms)) ?: return null
        if (known.sharedBilling) return null
        val m = normalizer.resolve(known.aliases.first())
        val duration = Extractors.planDuration(body)
        return PaymentEvent.MandateCreated(
            merchantId = m.id,
            merchantName = m.name,
            receivedOn = received,
            amount = null,
            frequency = Extractors.frequency(body)?.takeIf { it.hasSchedule } ?: duration?.let(Extractors::frequencyOf),
            startDate = null,
            validTill = null,
            receivedAt = sms.receivedAt,
            fromMail = true,
        )
    }

    /**
     * "Your <app> subscription ... on Google Play has been cancelled": the store ending one
     * purchase. It states no amount, so what makes it usable is the order number it shares with the
     * purchase's receipt. Only a mail signed by Google is believed, and one with no order number says
     * nothing about which purchase it ended.
     */
    private fun storeCancellation(sms: Sms, body: String, received: LocalDate): PaymentEvent.MandateRevoked? {
        val domain = sms.verifiedDomain ?: return null
        if (domain != "google.com" && !domain.endsWith(".google.com")) return null
        if (!SmsClassifier.isStoreCancellation(body)) return null
        val app = normalizer.findKnownIn(body.replace(playName, " "))?.let { normalizer.resolve(it.aliases.first()) }
            ?: MerchantNormalizer.UNKNOWN
        return PaymentEvent.MandateRevoked(app.id, app.name, received, receivedAt = sms.receivedAt)
    }

    /** Whether this mail's signature is the named service's own, or Google's for a purchase made through Google Play. */
    private fun signedByTrustedDomain(domain: String, merchantId: String): Boolean =
        normalizer.findKnownIn(domain)?.id == merchantId || domain == "google.com" || domain.endsWith(".google.com")

    /**
     * What a mail is worth, for the parser and for the debug audit: its kind, whether it is believed,
     * and which facts it states. A mail is believed about a plan only if it names a service the
     * catalog knows by name (a billing channel's own name says nothing about which app) and was signed
     * by that service or its store: any domain can sign its own mail.
     */
    fun assessMail(sms: Sms): MailAssessment {
        val body = sms.body.replace('\n', ' ')
        val received = sms.receivedAt.toLocalDate()
        val m = merchant(sms, body)
        val appNamed = m.known != null && !m.known.sharedBilling
        val trusted = appNamed && sms.verifiedDomain?.let { signedByTrustedDomain(it, m.id) } == true
        return MailAnalyzer.assess(sms.subject, body, received, appNamed, trusted)
    }

    /**
     * Mail a service sends about a plan it already sold: "next payment ₹139, payment date 15 Aug",
     * "your subscription has been cancelled", "has expired". None of them states a payment, so none
     * would pass the payment gate; what stands in for that evidence is the sender (see [assessMail])
     * and the label on each fact. Adverts that reuse the words give nothing.
     */
    private fun serviceNotice(sms: Sms, body: String, received: LocalDate, assessment: MailAssessment): PaymentEvent? {
        if (assessment.verdict != MailVerdict.EVENT) return null
        val m = merchant(sms, body)
        return when (assessment.kind) {
            MailKind.RENEWAL_NOTICE -> MailAnalyzer.renewal(body, received)
                ?.let { PaymentEvent.PreDebitNotice(m.id, m.name, received, it.amount, it.date) }
            MailKind.CANCELLED -> PaymentEvent.MandateRevoked(m.id, m.name, received, sms.receivedAt, activeUntil = MailAnalyzer.accessUntil(body, received))
            MailKind.EXPIRED -> PaymentEvent.MandateRevoked(m.id, m.name, received, sms.receivedAt)
            else -> null
        }
    }

    /**
     * A store receipt for a plan paid for today: "Total: ₹1,999.00/month", auto-renewing. Its amount and
     * period are what let the bank's "mandate towards Google Play for Rs.1999.00", minutes earlier, be
     * named after the app (see `nameChannelEvents`). The renewal it states follows from today + period.
     */
    private fun paidReceipt(sms: Sms, m: ResolvedMerchant, body: String, received: LocalDate): PaymentEvent.MandateCreated? {
        val amount = MailAnalyzer.purchaseAmount(body) ?: return null
        return PaymentEvent.MandateCreated(
            merchantId = m.id,
            merchantName = m.name,
            receivedOn = received,
            amount = amount,
            frequency = Extractors.frequency(body)?.takeIf { it.hasSchedule },
            startDate = received,
            validTill = null,
            autoRenews = Extractors.autoRenews(body),
            receivedAt = sms.receivedAt,
            fromMail = true,
        )
    }

    /** A purchase whose first payment is an introductory price: the plan is the price that follows. */
    private fun introReceipt(sms: Sms, m: ResolvedMerchant, body: String, received: LocalDate): PaymentEvent.MandateCreated? {
        val intro = Extractors.introTerms(body, received) ?: return null
        if (!intro.firstCharge.isAfter(received)) return null
        return PaymentEvent.MandateCreated(
            merchantId = m.id,
            merchantName = m.name,
            receivedOn = received,
            amount = intro.price,
            frequency = intro.frequency,
            startDate = intro.firstCharge,
            validTill = null,
            autoRenews = true,
            receivedAt = sms.receivedAt,
            fromMail = true,
        )
    }

    /** A free-trial receipt: nothing was charged, but the mail states the day paying begins. */
    private fun trialReceipt(m: ResolvedMerchant, body: String, received: LocalDate): PaymentEvent.MandateCreated? {
        val trial = Extractors.trialTerms(body, received) ?: return null
        if (!trial.firstCharge.isAfter(received)) return null
        return PaymentEvent.MandateCreated(
            merchantId = m.id,
            merchantName = m.name,
            receivedOn = received,
            amount = trial.price,
            frequency = Extractors.frequency(body),
            startDate = trial.firstCharge,
            validTill = null,
            autoRenews = true,
            fromMail = true,
        )
    }

    private fun mandateCreated(m: ResolvedMerchant, body: String, received: LocalDate): PaymentEvent.MandateCreated {
        val dates = Extractors.dates(body, received)
        val stated = dates.firstOrNull { endContext.containsMatchIn(it.before) }?.date
        val start = dates.firstOrNull { startContext.containsMatchIn(it.before) && it.date != stated }?.date
            ?: dates.firstOrNull { it.date != stated && !endContext.containsMatchIn(it.before) }?.date

        // Nothing says when the plan ends, but "3 months plan" says how long it lasts, which is
        // the same thing counted from the day it was bought.
        val duration = Extractors.planDuration(body)
        val validTill = stated ?: duration?.let { (start ?: received).plus(it) }
        val amounts = if (fromEmail) Extractors.paidAmounts(body) else Extractors.amounts(body)

        return PaymentEvent.MandateCreated(
            merchantId = m.id,
            merchantName = m.name,
            receivedOn = received,
            // "Rs 1 verification ... Rs 149 monthly from 20-10-26": the plan price is the real amount.
            amount = amounts.firstOrNull { it > TOKEN_AMOUNT } ?: amounts.firstOrNull(),
            frequency = Extractors.frequency(body) ?: duration?.let(Extractors::frequencyOf),
            startDate = start,
            validTill = validTill,
            autoRenews = Extractors.autoRenews(body),
            fromMail = fromEmail,
        )
    }

    private fun mandateRevoked(m: ResolvedMerchant, body: String, received: LocalDate): PaymentEvent.MandateRevoked {
        val amounts = if (fromEmail) Extractors.paidAmounts(body) else Extractors.amounts(body)
        // Only a date that reads as an end ("until", "valid till", "will end on"), and only a
        // future one: the day the cancellation happened is not when access stops.
        val activeUntil = Extractors.dates(body, received)
            .firstOrNull { endContext.containsMatchIn(it.before) && it.date.isAfter(received) }?.date
        return PaymentEvent.MandateRevoked(
            m.id, m.name, received,
            amount = amounts.firstOrNull { it > TOKEN_AMOUNT } ?: amounts.firstOrNull(),
            activeUntil = activeUntil,
        )
    }

    private fun preDebit(m: ResolvedMerchant, body: String, received: LocalDate): PaymentEvent.PreDebitNotice? {
        val debitDate = Extractors.dates(body, received)
            .filterNot { endContext.containsMatchIn(it.before) }
            .map { it.date }
            .firstOrNull { !it.isBefore(received) }
            ?: if (body.contains("tomorrow", ignoreCase = true)) received.plusDays(1) else return null
        val amount = if (fromEmail) Extractors.paidAmount(body) else Extractors.amount(body)
        return PaymentEvent.PreDebitNotice(m.id, m.name, received, amount, debitDate)
    }

    private fun debit(m: ResolvedMerchant, body: String, received: LocalDate): PaymentEvent.Debit? {
        val amount = (if (fromEmail) Extractors.paidAmount(body) else Extractors.amount(body)) ?: return null
        // A debit can't happen after the SMS reporting it; a later date is a misread (e.g. card expiry).
        val date = Extractors.dates(body, received).map { it.date }.firstOrNull { !it.isAfter(received) } ?: received
        return PaymentEvent.Debit(
            merchantId = m.id,
            merchantName = m.name,
            receivedOn = received,
            amount = amount,
            date = date,
            // A UMN is present in AutoPay debits that never spell out "AutoPay" anywhere.
            viaMandate = SmsClassifier.isRecurring(body) || Extractors.hasUmn(body),
            peerToPeer = !fromEmail && Extractors.isPeerToPeer(body),
        )
    }

    /**
     * Whether an e-mail is worth reading as a payment at all.
     *
     * Two things have to hold. It must show that money actually moved — an advert names a price,
     * a receipt says you were charged — and it must be about a service we can name, either one
     * the catalog knows or one the message itself says you subscribe to. Coursera's marketing mail
     * satisfies the second on its own, which is how it became a subscription.
     */
    private fun readableAsPayment(sms: Sms, body: String): Boolean {
        if (!SmsClassifier.hasPaymentEvidence(body)) return false
        return namesKnownService(senderIdentity(sms)) || SmsClassifier.isRecurring(body)
    }

    private fun namesKnownService(sender: String) = normalizer.findKnownIn(sender) != null

    /** The signed domain when the message carried one, otherwise whatever `From` claimed. */
    private fun senderIdentity(sms: Sms) = sms.verifiedDomain ?: sms.sender

    /**
     * "no-reply@mail.nordvpn.com" -> "nordvpn": the label in front of the public suffix.
     *
     * A receipt's domain names the service that sent it. Its body names whichever feature the
     * footer happens to advertise, which is how a NordVPN receipt became "Monitor".
     */
    private fun senderDomainName(sender: String): String? {
        val host = sender.substringAfterLast('@', "").trim().lowercase().trim('.')
        val labels = host.split('.').filter { it.isNotBlank() }
        if (labels.size < 2) return null
        return labels.dropLast(1).lastOrNull { it !in publicSuffixes }
    }

    /**
     * A gateway or bank sends receipts on behalf of whoever it billed, so its own domain is
     * never the merchant. Its mail still deserves reading, unlike a stranger's, because it is a
     * genuine payment channel — just not the one whose subscription it names.
     */
    private fun isPaymentProcessor(sender: String): Boolean {
        val name = senderDomainName(sender) ?: return false
        return name in paymentProcessorDomains
    }

    private fun merchant(sms: Sms, body: String): ResolvedMerchant {
        // Mail is read from its sender alone: the body cues are the very thing that misfires on a
        // long message, so falling back to them would put the noise straight back in.
        if (fromEmail) {
            val identity = senderIdentity(sms)
            // Play bills for other companies' subscriptions; the receipt says whose.
            if (sms.sender.contains("googleplay", ignoreCase = true)) {
                normalizer.findKnownIn(body.replace(playName, " "))
                    ?.let { return normalizer.resolve(it.aliases.first()) }
            }
            normalizer.findKnownIn(identity)?.let { return normalizer.resolve(it.aliases.first()) }
            // A bank or payment processor mails receipts for everyone, so its own domain names
            // nothing useful; the merchant it is billing for is in the text.
            if (isPaymentProcessor(identity)) {
                normalizer.findKnownIn(body)?.let { return normalizer.resolve(it.aliases.first()) }
            }
            senderDomainName(identity)?.let { return normalizer.resolve(it) }
            return MerchantNormalizer.UNKNOWN
        }
        normalizer.findKnownIn(body)?.let { return normalizer.resolve(it.aliases.first()) }
        Extractors.merchantPhrase(body)?.let { phrase ->
            normalizer.resolve(phrase).takeIf { it != MerchantNormalizer.UNKNOWN }?.let { return it }
        }
        Extractors.vpa(body)?.let { return normalizer.resolve(it) }
        return MerchantNormalizer.UNKNOWN
    }

    private companion object {
        val playName = Regex("""google\s*play""", RegexOption.IGNORE_CASE)
        val TOKEN_AMOUNT = java.math.BigDecimal(2)

        /** Enough of a public-suffix list to see past "example.co.in" and "example.com". */
        val publicSuffixes = setOf("com", "co", "net", "org", "edu", "gov", "ac", "in", "uk", "au")

        /** Gateways and processors whose own domain never names the merchant they billed for. */
        val paymentProcessorDomains = setOf(
            "razorpay", "payu", "payubiz", "cashfree", "billdesk", "ccavenue", "instamojo",
            "stripe", "paypal", "cybersource", "atomtech", "citruspay", "juspay", "pinelabs",
        )
    }
}
