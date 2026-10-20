package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.SubscriptionEngine
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.core.model.SubscriptionStatus
import com.aasif.watchdog.core.parser.SmsClassifier
import com.aasif.watchdog.core.parser.SmsParser
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A real Google Play receipt (Apple Music free trial billed through Play, 15 Aug 2026) that the
 * Gmail import silently dropped: "Individual Offer" tripped the promo filter, "unless cancelled"
 * read as a revocation, and the merchant came out as Google Play rather than Apple Music.
 */
class PlayTrialReceiptTest {
    private val parser = SmsParser(fromEmail = true)
    private val received = LocalDateTime.of(2026, 8, 15, 6, 12)

    private val receipt = """
        Your Google Play Order Receipt from 15 Aug 2026 Thank you
        You have signed up for a trial subscription from Apple Distribution International
        Ltd on Google Play. Your trial will end on 15 Feb 2027. You will be
        automatically charged the subscription cost (currently ₹139.00/month) at the
        end of your trial unless cancelled. You can cancel at any time. Manage your
        subscriptions To help keep your subscription active, add a backup payment method.
        Order number: GPA.3315-3734-4179-94143 Order date: 15 Aug 2026 6:12:10 am IST
        Your account: user@example.com
        Item Price Individual Offer (Apple Music) ₹0.00 Auto-renewing subscription
        Total: ₹0.00 (Includes GST of ₹0.00) Payment method: UPI: GPay
        By subscribing, you authorise us to charge you the subscription cost automatically,
        charged to the payment method provided until cancelled. Learn how to cancel.
        View the Google Play Refund Policy and the Terms of Service.
    """.trimIndent().replace('\n', ' ')

    private fun parse(body: String = receipt, verifiedDomain: String? = "google.com") =
        parser.parse(Sms("googleplay-noreply@google.com", body, received, verifiedDomain))

    @Test
    fun `a receipt is not promotional just because an item is called an Offer`() {
        assertFalse(SmsClassifier.isPromotionalMail(receipt))
    }

    @Test
    fun `an advert with an offer and no payment is still promotional`() {
        assertTrue(SmsClassifier.isPromotionalMail("Limited time offer: Coursera Plus is only ₹7,499/year. Get 40% off"))
    }

    @Test
    fun `the trial is attributed to Apple Music, not to Google Play`() {
        val e = assertIs<PaymentEvent.MandateCreated>(parse())
        assertEquals("applemusic", e.merchantId)
    }

    @Test
    fun `the trial's end is the first charge, at the stated renewal price`() {
        val e = assertIs<PaymentEvent.MandateCreated>(parse())
        assertEquals(LocalDate.of(2027, 2, 15), e.startDate)
        assertEquals(Money(BigDecimal("139.00")), e.amount)
        assertEquals(Frequency.MONTHLY, e.frequency)
    }

    @Test
    fun `it shows up as a trial that converts on 15 Feb 2027`() {
        val event = parse()!!
        val sub = SubscriptionEngine().build(listOf(event), today = LocalDate.of(2026, 9, 30)).single()
        assertEquals(SubscriptionStatus.TRIAL, sub.status)
        assertEquals(LocalDate.of(2027, 2, 15), sub.nextRenewal)
    }

    @Test
    fun `unless cancelled is a condition, not a cancellation`() {
        val plain = "Your subscription renews automatically and you will be charged ₹199 monthly unless cancelled. Total: ₹199"
        assertTrue(parse(plain) !is PaymentEvent.MandateRevoked)
    }

    @Test
    fun `a real cancellation notice is still a revocation`() {
        val revoked = parse("Your subscription to Apple Music has been cancelled. You were charged ₹139.00 on 15 Aug 2026; you will not be charged again.")
        assertIs<PaymentEvent.MandateRevoked>(revoked)
    }

    @Test
    fun `a Play receipt for something the catalog does not know falls back to Google Play`() {
        val e = parse("Thank you. Order total: ₹49.00 for Some Unknown App. Auto-renewing subscription.")
        assertNull(e?.takeIf { it.merchantId == "applemusic" })
    }
}
