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
        if (fromEmail && !namesKnownService(sms.sender) && !SmsClassifier.isRecurring(body)) return null
        val m = merchant(sms.sender, body)

        return when (SmsClassifier.classify(body)) {
            SmsKind.MANDATE_CREATED -> mandateCreated(m, body, received)
            SmsKind.PRE_DEBIT -> preDebit(m, body, received)
            SmsKind.DEBIT -> debit(m, body, received)
            SmsKind.MANDATE_REVOKED -> PaymentEvent.MandateRevoked(m.id, m.name, received)
            SmsKind.IRRELEVANT -> null
        }
    }

    private fun mandateCreated(m: ResolvedMerchant, body: String, received: LocalDate): PaymentEvent.MandateCreated {
        val dates = Extractors.dates(body, received)
        val validTill = dates.firstOrNull { endContext.containsMatchIn(it.before) }?.date
        val start = dates.firstOrNull { startContext.containsMatchIn(it.before) && it.date != validTill }?.date
            ?: dates.firstOrNull { it.date != validTill && !endContext.containsMatchIn(it.before) }?.date
        return PaymentEvent.MandateCreated(
            merchantId = m.id,
            merchantName = m.name,
            receivedOn = received,
            // "Rs 1 verification ... Rs 149 monthly from 20-10-26": the plan price is the real amount.
            amount = Extractors.amounts(body).let { all -> all.firstOrNull { it > TOKEN_AMOUNT } ?: all.firstOrNull() },
            frequency = Extractors.frequency(body),
            startDate = start,
            validTill = validTill,
        )
    }

    private fun preDebit(m: ResolvedMerchant, body: String, received: LocalDate): PaymentEvent.PreDebitNotice? {
        val debitDate = Extractors.dates(body, received)
            .filterNot { endContext.containsMatchIn(it.before) }
            .map { it.date }
            .firstOrNull { !it.isBefore(received) }
            ?: if (body.contains("tomorrow", ignoreCase = true)) received.plusDays(1) else return null
        return PaymentEvent.PreDebitNotice(m.id, m.name, received, Extractors.amount(body), debitDate)
    }

    private fun debit(m: ResolvedMerchant, body: String, received: LocalDate): PaymentEvent.Debit? {
        val amount = Extractors.amount(body) ?: return null
        // A debit can't happen after the SMS reporting it; a later date is a misread (e.g. card expiry).
        val date = Extractors.dates(body, received).map { it.date }.firstOrNull { !it.isAfter(received) } ?: received
        return PaymentEvent.Debit(m.id, m.name, received, amount, date, SmsClassifier.isRecurring(body))
    }

    private fun namesKnownService(sender: String) = normalizer.findKnownIn(sender) != null

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

    private fun merchant(sender: String, body: String): ResolvedMerchant {
        // Mail is read from its sender alone: the body cues are the very thing that misfires on a
        // long message, so falling back to them would put the noise straight back in.
        if (fromEmail) {
            normalizer.findKnownIn(sender)?.let { return normalizer.resolve(it.aliases.first()) }
            senderDomainName(sender)?.let { return normalizer.resolve(it) }
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
        val TOKEN_AMOUNT = java.math.BigDecimal(2)

        /** Enough of a public-suffix list to see past "example.co.in" and "example.com". */
        val publicSuffixes = setOf("com", "co", "net", "org", "edu", "gov", "ac", "in", "uk", "au")
    }
}
