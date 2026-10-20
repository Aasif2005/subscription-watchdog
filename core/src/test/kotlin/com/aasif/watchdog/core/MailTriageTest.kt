package com.aasif.watchdog.core

import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.core.parser.MailFact
import com.aasif.watchdog.core.parser.MailKind
import com.aasif.watchdog.core.parser.MailVerdict
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
 * Four real mails (names replaced) that say very different things with the same words: a trial
 * about to convert, a payment that was declined, a terms-of-service notice and a YouTube payment
 * problem. The declined-payment mail says "Your subscription will be cancelled" and states an amount,
 * and used to be stored as a cancellation.
 */
class MailTriageTest {
    private val parser = SmsParser(fromEmail = true)
    private fun sms(sender: String, subject: String, text: String, on: LocalDateTime, domain: String) =
        Sms(sender, "$subject $text", on, domain, subject)

    private val play = "googleplay-noreply@google.com"

    private val trial = sms(
        play, "Your trial for Google One will end on Aug 4, 2026",
        "Hi there, This is a reminder that your trial for Google One (Google AI Pro (5 TB)) on Google Play will end on Aug 4, 2026. " +
            "You'll be automatically charged as described below to the payment method provided unless you cancel before then. " +
            "You can cancel anytime and see upcoming charges in Subscriptions on Google Play. Learn how to cancel. " +
            "By continuing your subscription, you can keep enjoying all the benefits it provides. Summary Next payment ₹1,950.00/month Payment date Aug 4, 2026 " +
            "Keep this for your records. See your Google Play Order History. View the Google Play Refund Policy and the Terms of Service. " +
            "You're receiving this reminder email because you signed up for a subscription that starts with a trial.",
        LocalDateTime.of(2026, 8, 2, 10, 38), "google.com",
    )

    private val declined = sms(
        play, "Google One subscription suspended due to payment issue",
        "Your subscription will be cancelled UPDATE PAYMENT Hi there, Your payment from UPI: QR code was declined. " +
            "Update your payment method to get all of the benefits provided by Google AI Pro (5 TB) (Google One) back. " +
            "To keep these benefits, you'll need to keep your subscription active. Update your payment method or use a different one. " +
            "In the meantime, we'll try to process the payment again so you can regain access to your subscription. Manage your subscriptions " +
            "SUMMARY Amount Due ₹1,950.00 UPDATE PAYMENT Learn more Thanks, The Google Play team " +
            "See your Google Play Order History. View the Google Play Refund Policy and the Terms of Service.",
        LocalDateTime.of(2026, 8, 7, 10, 38), "google.com",
    )

    private val terms = sms(
        play, "Updates to Google Play Terms of Service",
        "On October 14, 2026, we're making some changes to our Google Play Terms of Service. At a glance, here's what this update means for you: " +
            "Subscription Payment Issues: We added language explaining that you remain responsible for balances in your Play account if we are unable to charge your designated payment method. " +
            "By continuing to use Google Play after these changes, you agree to the new terms. If you do not agree, you may terminate your use of Google Play. " +
            "You have received this mandatory email service announcement to update you about important changes to your Play product or account.",
        LocalDateTime.of(2026, 9, 4, 7, 7), "google.com",
    )

    private val youtube = sms(
        "noreply-purchases@youtube.com", "Don't lose YouTube Premium. Update your payment method now",
        "UPDATE PAYMENT METHOD NOW Hi there, We're writing to let you know that we encountered an issue processing your recent payment for your YouTube Premium subscription. " +
            "This can be due to a number of reasons, such as an insufficient balance, the card expiring, or the card not being an accepted form of payment in your location. " +
            "To ensure your YouTube Premium benefits continue without interruption, please update your payment method now. Thanks, The YouTube team " +
            "Your transaction was processed by Google Digital Inc, which can be reached at: Google Digital Inc., Attn: YouTube Paid Services",
        LocalDateTime.of(2026, 10, 8, 15, 55), "youtube.com",
    )

    @Test
    fun `a trial reminder is a renewal notice carrying the app, price, date and period`() {
        val e = assertIs<PaymentEvent.PreDebitNotice>(parser.parse(trial))
        assertEquals("googleone", e.merchantId)
        assertEquals(Money(BigDecimal("1950.00")), e.amount)
        assertEquals(LocalDate.of(2026, 8, 4), e.debitDate)
        val a = parser.assessMail(trial)
        assertEquals(MailKind.RENEWAL_NOTICE, a.kind)
        assertEquals(MailVerdict.EVENT, a.verdict)
        assertTrue(a.facts.containsAll(setOf(MailFact.APP, MailFact.AMOUNT, MailFact.NEXT_DATE, MailFact.PERIOD, MailFact.TRIAL)), a.toString())
        assertTrue(a.useful)
    }

    @Test
    fun `a declined payment that warns the subscription will be cancelled is not a cancellation`() {
        assertNull(parser.parse(declined))
        val a = parser.assessMail(declined)
        assertEquals(MailKind.PAYMENT_ISSUE, a.kind)
        assertEquals(MailVerdict.SIGNAL, a.verdict)
        assertTrue(a.facts.containsAll(setOf(MailFact.APP, MailFact.AMOUNT)), a.toString())
        assertTrue(a.useful)
    }

    @Test
    fun `a payment trouble mail from YouTube is a signal with no amount, and no event`() {
        assertNull(parser.parse(youtube))
        val a = parser.assessMail(youtube)
        assertEquals(MailKind.PAYMENT_ISSUE, a.kind)
        assertEquals(MailVerdict.SIGNAL, a.verdict)
        assertFalse(MailFact.AMOUNT in a.facts)
    }

    @Test
    fun `a terms of service notice is not about a subscription`() {
        assertNull(parser.parse(terms))
        val a = parser.assessMail(terms)
        assertEquals(MailKind.ACCOUNT_NOTICE, a.kind)
        assertEquals(MailVerdict.IGNORE, a.verdict)
        assertFalse(a.useful)
    }

    @Test
    fun `the Terms of Service link in a real receipt or cancellation does not make it a legal notice`() {
        val cancelled = sms(
            play, "Your Google One subscription has been canceled",
            "Your Google One subscription from Google Ireland Limited on Google Play has been canceled. Order number: SOP.1111-2222-3333-44444 " +
                "View the Google Play Refund Policy and the Terms of Service.",
            LocalDateTime.of(2026, 9, 6, 10, 38), "google.com",
        )
        assertEquals(MailKind.CANCELLED, parser.assessMail(cancelled).kind)
        assertIs<PaymentEvent.MandateRevoked>(parser.parse(cancelled))
    }

    @Test
    fun `a payment trouble mail from a service we cannot trust is ignored outright`() {
        val a = parser.assessMail(sms("hello@randomshop.example", "Payment failed", "Your payment was declined. Amount Due ₹499.00", LocalDateTime.of(2026, 10, 1, 9, 0), "randomshop.example"))
        assertEquals(MailVerdict.IGNORE, a.verdict)
    }

    @Test
    fun `a genuine cancellation that mentions the failed payment behind it still ends the plan`() {
        val e = parser.parse(
            sms(play, "Your Spotify: Music and Podcasts subscription has been cancelled",
                "Your Spotify: Music and Podcasts subscription from Spotify India LLP on Google Play has been cancelled because the payment was declined. Order number: GPA.1111-2222-3333-44444",
                LocalDateTime.of(2026, 9, 12, 2, 18), "google.com"),
        )
        assertIs<PaymentEvent.MandateRevoked>(e)
    }
}
