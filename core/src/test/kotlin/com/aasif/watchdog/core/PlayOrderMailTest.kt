package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.SourcedEvent
import com.aasif.watchdog.core.engine.SubscriptionEngine
import com.aasif.watchdog.core.engine.nameChannelEvents
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
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spotify billed through Google Play: the order receipt and the cancellation mail, in the wording of
 * two real mails (order number, address and dates replaced). Both carry the same order number, the
 * only thing that says which purchase a cancellation is about.
 */
class PlayOrderMailTest {
    private val parser = SmsParser(fromEmail = true)
    private val engine = SubscriptionEngine()
    private val sender = "googleplay-noreply@google.com"
    private fun money(v: String) = Money(BigDecimal(v))

    private fun receipt(order: String = "GPA.1111-2222-3333-44444") =
        """
        Your Google Play Order Receipt from 16 Jun 2026 Thank you
        You've made a subscription purchase from Spotify India LLP on Google Play.
        You will be automatically charged ₹99.00 for 2 months, then ₹139.00/month
        starting on 15 Aug 2026, unless cancelled. You can cancel at any time.
        Manage your subscriptions
        To help keep your subscription active, add a backup payment method.
        Add backup
        Order number: $order
        Order date: 16 Jun 2026 2:18:40 am IST
        Your account: someone@example.com
        Item Price
        Premium Standard (Spotify: Music and Podcasts) ₹99.00
        Auto-renewing subscription
        Total: ₹99.00
        (Includes GST of ₹15.10)
        Payment method: UPI: GPay
        By subscribing, you authorise us to charge you the subscription cost (as described above) automatically,
        charged to the payment method provided until cancelled. Learn how to cancel. Keep this for your records.
        Questions? Visit Spotify India LLP.
        See your Google Play order history.
        View the Google Play Refund Policy and the Terms of Service.
        """.trimIndent().replace('\n', ' ')

    private fun cancellation(order: String = "GPA.1111-2222-3333-44444") =
        """
        Your Spotify: Music and Podcasts subscription has been cancelled
        Your Spotify: Music and Podcasts subscription from Spotify India LLP on
        Google Play has been cancelled.
        Order number: $order
        Order date: 16 Jun 2026 2:18:40 am IST
        See your Google Play Order History.
        View the Google Play Refund Policy and the Terms of Service.
        Need help? Visit the Google Play Help Centre.
        """.trimIndent().replace('\n', ' ')

    private val receivedReceipt = LocalDateTime.of(2026, 6, 16, 2, 18)
    private val receivedCancel = LocalDateTime.of(2026, 9, 12, 2, 18)

    private fun parseReceipt(order: String = "GPA.1111-2222-3333-44444") =
        parser.parse(Sms(sender, receipt(order), receivedReceipt, "google.com"))

    private fun parseCancel(order: String = "GPA.1111-2222-3333-44444", domain: String? = "google.com") =
        parser.parse(Sms(sender, cancellation(order), receivedCancel, domain))

    @Test
    fun `a Play receipt with an introductory price is a mandate at the renewal price`() {
        val e = assertIs<PaymentEvent.MandateCreated>(parseReceipt())
        assertEquals("spotify", e.merchantId)
        assertEquals(money("139.00"), e.amount)
        assertEquals(Frequency.MONTHLY, e.frequency)
        assertEquals(LocalDate.of(2026, 8, 15), e.startDate)
        assertTrue(e.autoRenews)
        assertTrue(e.fromMail)
    }

    @Test
    fun `the receipt and the cancellation carry the same order reference`() {
        val created = assertIs<PaymentEvent.MandateCreated>(parseReceipt())
        val revoked = assertIs<PaymentEvent.MandateRevoked>(parseCancel())
        assertNotNull(created.mandateRef)
        assertEquals(created.mandateRef, revoked.mandateRef)
    }

    @Test
    fun `a cancellation names the app and states no amount`() {
        val revoked = assertIs<PaymentEvent.MandateRevoked>(parseCancel())
        assertEquals("spotify", revoked.merchantId)
        assertNull(revoked.amount)
        assertEquals(LocalDate.of(2026, 9, 12), revoked.receivedOn)
    }

    @Test
    fun `another purchase has another reference`() {
        val a = assertIs<PaymentEvent.MandateRevoked>(parseCancel())
        val b = assertIs<PaymentEvent.MandateRevoked>(parseCancel("GPA.9999-8888-7777-66666"))
        assertNotEquals(a.mandateRef, b.mandateRef)
    }

    @Test
    fun `a renewal suffix on the order number still names the same purchase`() {
        val plain = assertIs<PaymentEvent.MandateRevoked>(parseCancel())
        val renewal = assertIs<PaymentEvent.MandateRevoked>(parseCancel("GPA.1111-2222-3333-44444..0"))
        assertEquals(plain.mandateRef, renewal.mandateRef)
    }

    @Test
    fun `a cancellation that is not signed by Google is ignored`() {
        assertNull(parseCancel(domain = null))
        assertNull(parseCancel(domain = "example.org"))
    }

    @Test
    fun `a cancellation with no order number still ends the app it names, but carries no reference`() {
        val noOrder = cancellation().replace(Regex("""Order number: \S+"""), "")
        val e = assertIs<PaymentEvent.MandateRevoked>(parser.parse(Sms(sender, noOrder, receivedCancel, "google.com")))
        assertEquals("spotify", e.merchantId)
        assertNull(e.mandateRef)
    }

    @Test
    fun `the Refund Policy link in a footer is not a refund`() {
        assertEquals(com.aasif.watchdog.core.parser.SmsKind.MANDATE_REVOKED, SmsClassifier.classify(cancellation()))
        assertEquals(com.aasif.watchdog.core.parser.SmsKind.IRRELEVANT, SmsClassifier.classify("Your refund of ₹139 for the subscription has been processed"))
    }

    @Test
    fun `receipt then cancellation ends the plan with no bank text at all`() {
        val events = listOfNotNull(parseReceipt(), parseCancel())
        val sub = engine.build(events, LocalDate.of(2026, 10, 14)).single()
        assertEquals("spotify", sub.merchantId)
        assertTrue(!sub.isLive, sub.toString())
        assertTrue(sub.status == SubscriptionStatus.CANCELLED || sub.status == SubscriptionStatus.ENDING, sub.toString())
    }

    @Test
    fun `the cancellation lands on the purchase it names, not on another app on the same channel`() {
        val other = PaymentEvent.MandateCreated("googleplay", "Google Play", LocalDate.of(2026, 7, 1), money("139.00"), null, null, null)
        val others = PaymentEvent.MandateCreated("googleplay", "Google Play", LocalDate.of(2026, 7, 2), money("499.00"), null, null, null)
        val subs = engine.build(listOfNotNull(parseReceipt(), parseCancel()) + other + others, LocalDate.of(2026, 10, 14))
        assertEquals(2, subs.count { it.isLive }, subs.toString())
        assertTrue(subs.single { it.merchantId == "spotify" }.status != SubscriptionStatus.ACTIVE)
    }

    @Test
    fun `the bank's own mandate for the purchase is named Spotify by its receipt and ends with the cancellation`() {
        val bank = PaymentEvent.MandateCreated("googleplay", "Google Play", LocalDate.of(2026, 6, 16), money("139.00"), Frequency.AS_PRESENTED, LocalDate.of(2026, 6, 16), null, receivedAt = LocalDateTime.of(2026, 6, 16, 2, 18), mandateRef = "bankumn")
        val named = nameChannelEvents(
            listOf(SourcedEvent(bank, false), SourcedEvent(parseReceipt()!!, true), SourcedEvent(parseCancel()!!, true)),
            setOf("googleplay"),
        )
        assertTrue(named.none { it.merchantId == "googleplay" }, named.toString())
        val subs = engine.build(named, LocalDate.of(2026, 10, 14))
        assertTrue(subs.none { it.isLive }, subs.toString())
    }
}
