package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.SourcedEvent
import com.aasif.watchdog.core.engine.SubscriptionEngine
import com.aasif.watchdog.core.engine.nameChannelEvents
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.PaymentEvent.MandateCreated
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.core.model.SubscriptionStatus
import com.aasif.watchdog.core.parser.MailKind
import com.aasif.watchdog.core.parser.SmsParser
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Claude Pro bought through Google Play on 18 Sep 2026 (real receipt wording, names and order number
 * replaced; bank texts at the times they arrived on the phone). The bank says only "Google Play
 * ₹1,999"; the receipt, 14 seconds after the final mandate, says which app. It used to be read as a
 * renewal notice with no amount, so nothing linked them and Claude appeared twice.
 */
class PlayPurchaseLinkTest {
    private val parser = SmsParser(fromEmail = true)
    private val engine = SubscriptionEngine()
    private val play = setOf("googleplay")
    private fun money(v: String) = Money(BigDecimal(v))
    private fun at(h: Int, m: Int, s: Int = 0) = LocalDateTime.of(2026, 9, 18, h, m, s)

    private val subject = "Your Google Play Order Receipt from 18 Sept 2026"
    private val receiptText =
        "Thank you You've made a subscription purchase from Anthropic, PBC on Google Play. Your subscription will automatically renew on 18 Oct 2026 " +
            "unless cancelled before then. You can cancel at any time. Manage your subscriptions To help keep your subscription active, add a backup payment method. " +
            "Add backup Order number: GPA.1111-2222-3333-55555 Order date: 18 Sept 2026 11:30:49 am IST Your account: someone@example.com " +
            "Item Price Claude Pro Subscription (Claude by Anthropic) ₹1,999.00/month Auto-renewing subscription Total: ₹1,999.00/month (Includes GST of ₹304.93) " +
            "Payment method: UPI: GPay By subscribing, you authorise us to charge you the subscription cost (as described above) automatically, " +
            "charged to the payment method provided until cancelled. Learn how to cancel. Keep this for your records. Questions? Visit Anthropic, PBC. " +
            "See your Google Play order history. View the Google Play Refund Policy and the Terms of Service."

    private val receiptSms = Sms("googleplay-noreply@google.com", "$subject $receiptText", at(11, 30, 51), "google.com", subject)

    private fun bankCreated(time: LocalDateTime, ref: String) =
        MandateCreated("googleplay", "Google Play", time.toLocalDate(), money("1999.00"), Frequency.AS_PRESENTED, time.toLocalDate(), null, receivedAt = time, mandateRef = ref)

    @Test
    fun `a Play order receipt is a purchase, not a renewal notice, even though it states the next renewal`() {
        assertEquals(MailKind.PURCHASE, parser.assessMail(receiptSms).kind)
        val e = assertIs<MandateCreated>(parser.parse(receiptSms))
        assertEquals("claude", e.merchantId)
        assertEquals(money("1999.00"), e.amount)
        assertEquals(Frequency.MONTHLY, e.frequency)
        assertEquals(LocalDate.of(2026, 9, 18), e.startDate)
        assertNotNull(e.mandateRef)
    }

    @Test
    fun `the bank's Google Play mandate and the receipt become one Claude plan that renews on 18 Oct`() {
        val bank = listOf(
            SourcedEvent(bankCreated(at(11, 27, 19), "first"), false),
            SourcedEvent(PaymentEvent.MandateRevoked("googleplay", "Google Play", LocalDate.of(2026, 9, 18), at(11, 29, 57), amount = money("1999.00"), mandateRef = "first"), false),
            SourcedEvent(bankCreated(at(11, 30, 37), "second"), false),
        )
        val named = nameChannelEvents(bank + SourcedEvent(parser.parse(receiptSms)!!, true), play)
        assertTrue(named.none { it.merchantId == "googleplay" }, named.toString())
        val sub = engine.build(named, LocalDate.of(2026, 10, 15)).single()
        assertEquals("claude", sub.merchantId)
        assertEquals(SubscriptionStatus.ACTIVE, sub.status)
        assertEquals(money("1999.00"), sub.amount)
        assertEquals(Frequency.MONTHLY, sub.frequency)
        assertEquals(LocalDate.of(2026, 10, 18), sub.nextRenewal)
    }

    private fun receiptFor(app: String, alias: String, amount: String, time: LocalDateTime) =
        MandateCreated(app, app, time.toLocalDate(), money(amount), Frequency.MONTHLY, time.toLocalDate(), null, autoRenews = true, receivedAt = time, fromMail = true, mandateRef = alias)

    @Test
    fun `two apps bought the same day at different prices are each named by their price`() {
        val events = listOf(
            SourcedEvent(bankCreated(at(10, 0), "a").copy(amount = money("139.00")), false),
            SourcedEvent(bankCreated(at(18, 0), "b"), false),
            SourcedEvent(receiptFor("spotify", "o1", "139.00", at(10, 1)), true),
            SourcedEvent(receiptFor("claude", "o2", "1999.00", at(18, 1)), true),
        )
        val named = nameChannelEvents(events, play).filter { it is MandateCreated && !it.fromMail }
        assertEquals(listOf("spotify", "claude"), named.map { it.merchantId })
    }

    @Test
    fun `two apps at the same price on the same day are told apart by the minutes between mandate and receipt`() {
        val events = listOf(
            SourcedEvent(bankCreated(at(10, 0), "a"), false),
            SourcedEvent(bankCreated(at(12, 5), "b"), false),
            SourcedEvent(receiptFor("claude", "o1", "1999.00", at(10, 1)), true),
            SourcedEvent(receiptFor("chatgpt", "o2", "1999.00", at(12, 6)), true),
        )
        val named = nameChannelEvents(events, play).filter { it is MandateCreated && !it.fromMail }
        assertEquals(listOf("claude", "chatgpt"), named.map { it.merchantId })
    }

    @Test
    fun `with no times to go on, two apps at the same price leave the bank text unnamed rather than guessed`() {
        val events = listOf(
            SourcedEvent(bankCreated(at(10, 0), "a").copy(receivedAt = null), false),
            SourcedEvent(receiptFor("claude", "o1", "1999.00", at(10, 1)), true),
            SourcedEvent(receiptFor("chatgpt", "o2", "1999.00", at(12, 6)), true),
        )
        assertEquals("googleplay", nameChannelEvents(events, play).first().merchantId)
    }

    @Test
    fun `a renewal reminder with a next payment is still a notice`() {
        val reminder = Sms(
            "googleplay-noreply@google.com",
            "Your trial for Google One will end on Aug 4, 2026 This is a reminder that your trial for Google One on Google Play will end on Aug 4, 2026. Summary Next payment ₹1,950.00/month Payment date Aug 4, 2026",
            LocalDateTime.of(2026, 8, 2, 10, 38), "google.com", "Your trial for Google One will end on Aug 4, 2026",
        )
        assertEquals(MailKind.RENEWAL_NOTICE, parser.assessMail(reminder).kind)
    }
}
