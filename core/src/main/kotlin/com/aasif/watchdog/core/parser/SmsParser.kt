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
        if (!allowPersonalSenders && phoneNumber.matches(sms.sender.trim())) return null
        val body = sms.body.replace('\n', ' ')
        val received = sms.receivedAt.toLocalDate()
        if (fromEmail && !readableAsPayment(sms, body)) return null
        val m = merchant(sms, body)
        if (fromEmail) trialReceipt(m, body, received)?.let { return it }

        return when (SmsClassifier.classify(body)) {
            SmsKind.MANDATE_CREATED -> mandateCreated(m, body, received)
            SmsKind.PRE_DEBIT -> preDebit(m, body, received)
            SmsKind.DEBIT -> debit(m, body, received)
            SmsKind.MANDATE_REVOKED -> PaymentEvent.MandateRevoked(m.id, m.name, received)
            SmsKind.IRRELEVANT -> null
        }
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
