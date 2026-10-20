package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.SubscriptionEngine
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.RenewalSource
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.core.parser.MailAnalyzer
import com.aasif.watchdog.core.parser.MailKind
import com.aasif.watchdog.core.parser.SmsParser
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Subscription mail that states no payment: renewal notices, cancellations, expiries. Wording marked
 * "real" is a sanitised copy of a mail received on a real phone; "synthetic" is written from the
 * lifecycle a service is documented to send and has never been seen, so it tests the rule, not the sender.
 */
class MailAnalyzerTest {
    private val parser = SmsParser(fromEmail = true)
    private val engine = SubscriptionEngine()
    private fun money(v: String) = Money(BigDecimal(v))
    private fun at(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 2, 18)

    private fun mail(sender: String, subject: String, text: String, on: LocalDateTime, domain: String? = "google.com") =
        parser.parse(Sms(sender, "$subject $text", on, domain, subject))

    // real: Google Play reminder that an introductory price ends (Spotify)
    private val reminderSubject = "Your introductory price for Spotify: Music and Podcasts will end on 15 Aug 2026"
    private val reminderText =
        "Hi there, This is a reminder that your introductory price for Spotify: Music and Podcasts (Premium Standard) on Google Play " +
            "will end on 15 Aug 2026. You'll be automatically charged as described below to the payment method provided. " +
            "You can choose to stop your subscription from renewing at any time. You can see upcoming charges in Subscriptions on Google Play. " +
            "By continuing your subscription, you can keep enjoying all the benefits that it provides. Summary Next payment ₹139.00/month " +
            "Payment date 15 Aug 2026 Keep this for your records. See your Google Play Order History. " +
            "View the Google Play Refund Policy and the Terms of Service. You're receiving this reminder email because you started a subscription that includes an introductory price."

    private fun reminder() = mail("googleplay-noreply@google.com", reminderSubject, reminderText, at(2026, 8, 9))

    @Test
    fun `a renewal reminder is a pre-debit notice with the date and the price`() {
        val e = assertIs<PaymentEvent.PreDebitNotice>(reminder())
        assertEquals("spotify", e.merchantId)
        assertEquals(money("139.00"), e.amount)
        assertEquals(LocalDate.of(2026, 8, 15), e.debitDate)
    }

    @Test
    fun `the reminder gives the plan its renewal date`() {
        val receipt = PaymentEvent.MandateCreated("spotify", "Spotify", LocalDate.of(2026, 6, 16), money("139.00"), Frequency.MONTHLY, LocalDate.of(2026, 8, 15), null, autoRenews = true, fromMail = true)
        val sub = engine.build(listOfNotNull(receipt, reminder()), LocalDate.of(2026, 8, 10)).single()
        assertEquals(LocalDate.of(2026, 8, 15), sub.nextRenewal)
        assertEquals(RenewalSource.PRE_DEBIT_NOTICE, sub.renewalSource)
    }

    // real: Google One cancellation, American spelling, an SOP order number
    private val googleOneSubject = "Your Google One subscription has been canceled"
    private val googleOneText =
        "Your Google One subscription from Google Ireland Limited on Google Play has been canceled. Order number: SOP.1111-2222-3333-44444 " +
            "Order date: Aug 4, 2025 10:39:26 AM GMT+5:30 See your Google Play Order History. View the Google Play Refund Policy and the Terms of Service."

    @Test
    fun `a cancellation with an SOP order number names Google One and carries the order reference`() {
        val e = assertIs<PaymentEvent.MandateRevoked>(mail("googleplay-noreply@google.com", googleOneSubject, googleOneText, at(2026, 9, 6)))
        assertEquals("googleone", e.merchantId)
        assertNotNull(e.mandateRef)
        assertNull(e.amount)
    }

    @Test
    fun `an unsigned cancellation is ignored`() {
        assertNull(mail("googleplay-noreply@google.com", googleOneSubject, googleOneText, at(2026, 9, 6), domain = null))
    }

    // synthetic: a catalogued service cancelling its own membership
    @Test
    fun `a signed cancellation from a known service ends the plan, with the access date it states`() {
        val e = assertIs<PaymentEvent.MandateRevoked>(
            mail("info@mailer.netflix.com", "Your Netflix membership has been cancelled", "Sorry to see you go. Your access continues until 12 Nov 2026.", at(2026, 10, 14), "netflix.com"),
        )
        assertEquals("netflix", e.merchantId)
        assertEquals(LocalDate.of(2026, 11, 12), e.activeUntil)
    }

    @Test
    fun `turning auto-renew off is a cancellation`() {
        val e = mail("no-reply@spotify.com", "Auto-renew turned off", "Your Spotify Premium subscription will not renew. You can still listen until 2 Nov 2026.", at(2026, 10, 14), "spotify.com")
        assertIs<PaymentEvent.MandateRevoked>(e)
    }

    @Test
    fun `an expiry ends the plan`() {
        val e = assertIs<PaymentEvent.MandateRevoked>(
            mail("no-reply@spotify.com", "Your Spotify Premium subscription has expired", "We could not renew it.", at(2026, 10, 14), "spotify.com"),
        )
        assertEquals("spotify", e.merchantId)
    }

    @Test
    fun `a win-back advert that says the membership ended is not an event`() {
        assertNull(mail("info@mailer.netflix.com", "Come back to Netflix", "Your membership has ended. Rejoin now for 50% off your first month.", at(2026, 10, 14), "netflix.com"))
    }

    @Test
    fun `a failed payment is no cancellation and no charge`() {
        assertNull(mail("no-reply@spotify.com", "Your payment failed", "Your subscription payment was declined. Update your card.", at(2026, 10, 14), "spotify.com"))
    }

    @Test
    fun `a service the catalog does not know creates nothing from words alone`() {
        assertNull(mail("hello@randomshop.example", "Your subscription has been cancelled", "Your subscription has been cancelled.", at(2026, 10, 14), "randomshop.example"))
    }

    @Test
    fun `a billing channel that names no app creates nothing`() {
        assertNull(mail("googleplay-noreply@google.com", "Your subscription has been cancelled", "Your subscription on Google Play has been cancelled.", at(2026, 10, 14)))
    }

    @Test
    fun `kinds are read from the words the senders repeat`() {
        val d = LocalDate.of(2026, 10, 14)
        assertEquals(MailKind.RENEWAL_NOTICE, MailAnalyzer.kind("Upcoming renewal", "Your plan renews on 20 Oct 2026 for ₹649.", d))
        assertEquals(MailKind.CANCELLED, MailAnalyzer.kind(null, "Your membership has been cancelled.", d))
        assertEquals(MailKind.EXPIRED, MailAnalyzer.kind(null, "Your subscription has expired.", d))
        assertEquals(MailKind.PAYMENT_ISSUE, MailAnalyzer.kind(null, "We couldn't process your payment.", d))
        assertEquals(MailKind.MARKETING, MailAnalyzer.kind("We miss you", "Your membership has ended.", d))
        assertEquals(MailKind.OTHER, MailAnalyzer.kind("Your weekly digest", "Here is what you missed.", d))
    }
}
