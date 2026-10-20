package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.SubscriptionEngine
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.RenewalSource
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.core.model.SubscriptionStatus
import com.aasif.watchdog.core.parser.SmsParser
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end: real SMS text through [SmsParser] and into [SubscriptionEngine], covering the five
 * cases from the real-device bug report (PVR INOX and Airtel wrongly shown as subscriptions).
 *
 * The fix is a merchant-category rule ([com.aasif.watchdog.core.merchant.NonSubscriptionCatalog],
 * [com.aasif.watchdog.core.merchant.KnownMerchant.autopayOnly]), not a blocklist of these two
 * names, so several cases here use a *different* merchant in the same category (BookMyShow instead
 * of PVR, Jio instead of Airtel) to prove the fix generalises rather than special-casing the report.
 */
class SubscriptionClassificationTest {
    private val parser = SmsParser()
    private val engine = SubscriptionEngine()

    private fun d(s: String) = LocalDate.parse(s)
    private fun parse(body: String, sender: String = "VM-HDFCBK-S", at: LocalDateTime) =
        parser.parse(Sms(sender, body, at))

    private fun debitSms(amount: String, date: String, payee: String, balance: String = "10,000.00") =
        "Rs.$amount debited from A/c XX1234 on $date to $payee. Avl Bal Rs $balance -HDFC Bank"

    private fun events(vararg smses: Pair<String, LocalDateTime>): List<PaymentEvent> =
        smses.mapNotNull { (body, at) -> parse(body, at = at) }

    // ---- 1. PVR INOX with irregular dates and amounts -> ignored --------------------------------

    @Test
    fun `PVR INOX with irregular dates and amounts is ignored`() {
        val subs = engine.build(
            events(
                debitSms("450.00", "05-01-2026", "PVR INOX") to LocalDateTime.of(2026, 1, 5, 20, 0),
                debitSms("680.00", "22-03-2026", "PVR INOX") to LocalDateTime.of(2026, 3, 22, 20, 0),
                debitSms("310.00", "14-08-2026", "PVR INOX") to LocalDateTime.of(2026, 8, 14, 20, 0),
            ),
            today = d("2026-08-20"),
        )
        assertTrue(subs.isEmpty(), "irregular movie bookings must never become a subscription")
    }

    /**
     * The real bug: three PVR visits that happen to land ~90 days apart at a similar price used to
     * pattern-match a "quarterly subscription" exactly as well as a real one would, because nothing
     * distinguished "looks regular" from "is a plan". This is the actual regression guard.
     */
    @Test
    fun `PVR INOX is never a subscription even when the pattern coincidentally looks regular`() {
        val subs = engine.build(
            events(
                debitSms("350.00", "05-01-2026", "PVR INOX") to LocalDateTime.of(2026, 1, 5, 20, 0),
                debitSms("360.00", "05-04-2026", "PVR INOX") to LocalDateTime.of(2026, 4, 5, 20, 0),
                debitSms("340.00", "05-07-2026", "PVR INOX") to LocalDateTime.of(2026, 7, 5, 20, 0),
            ),
            today = d("2026-08-20"),
        )
        assertTrue(subs.isEmpty(), "a coincidentally regular booking pattern is still not evidence of a plan")
    }

    @Test
    fun `BookMyShow bookings are also never a subscription, proving this isn't a PVR-only fix`() {
        val subs = engine.build(
            events(
                debitSms("300.00", "05-01-2026", "BOOKMYSHOW") to LocalDateTime.of(2026, 1, 5, 20, 0),
                debitSms("310.00", "05-04-2026", "BOOKMYSHOW") to LocalDateTime.of(2026, 4, 5, 20, 0),
                debitSms("295.00", "05-07-2026", "BOOKMYSHOW") to LocalDateTime.of(2026, 7, 5, 20, 0),
            ),
            today = d("2026-08-20"),
        )
        assertTrue(subs.isEmpty())
    }

    // ---- 2. Airtel one-time recharge -> ignored --------------------------------------------------

    @Test
    fun `Airtel one-time recharge is ignored`() {
        val subs = engine.build(
            events(
                debitSms("199.00", "10-06-2026", "Airtel Prepaid Recharge") to LocalDateTime.of(2026, 6, 10, 12, 0),
            ),
            today = d("2026-06-15"),
        )
        assertTrue(subs.isEmpty(), "a single recharge is not a subscription")
    }

    /**
     * The real bug: Airtel is a known catalog merchant, so any 2+ similar-amount, similar-gap
     * recharges used to be trusted the same way a real OTT plan is, with no autopay evidence at
     * all. Monthly prepaid recharges are exactly this shape and must still be ignored.
     */
    @Test
    fun `repeated Airtel recharges without an autopay mandate are still ignored`() {
        val subs = engine.build(
            events(
                debitSms("319.00", "10-01-2026", "Airtel Prepaid Recharge") to LocalDateTime.of(2026, 1, 10, 12, 0),
                debitSms("319.00", "08-02-2026", "Airtel Prepaid Recharge") to LocalDateTime.of(2026, 2, 8, 12, 0),
                debitSms("319.00", "08-03-2026", "Airtel Prepaid Recharge") to LocalDateTime.of(2026, 3, 8, 12, 0),
            ),
            today = d("2026-03-15"),
        )
        assertTrue(subs.isEmpty(), "a recharge habit is not proof of AutoPay")
    }

    @Test
    fun `repeated Jio recharges without an autopay mandate are also ignored`() {
        val subs = engine.build(
            events(
                debitSms("239.00", "10-01-2026", "Jio Prepaid Recharge") to LocalDateTime.of(2026, 1, 10, 12, 0),
                debitSms("239.00", "08-02-2026", "Jio Prepaid Recharge") to LocalDateTime.of(2026, 2, 8, 12, 0),
                debitSms("239.00", "08-03-2026", "Jio Prepaid Recharge") to LocalDateTime.of(2026, 3, 8, 12, 0),
            ),
            today = d("2026-03-15"),
        )
        assertTrue(subs.isEmpty())
    }

    @Test
    fun `an explicit AutoPay mandate on a prepaid recharge is still honoured`() {
        val subs = engine.build(
            events(
                debitSms("319.00", "10-01-2026", "Airtel AutoPay mandate ref SI1234") to LocalDateTime.of(2026, 1, 10, 12, 0),
                debitSms("319.00", "10-02-2026", "Airtel AutoPay mandate ref SI1234") to LocalDateTime.of(2026, 2, 10, 12, 0),
            ),
            today = d("2026-02-15"),
        )
        assertTrue(subs.isNotEmpty(), "an actual AutoPay signal is real evidence, not a coincidence")
    }

    // ---- 3. Netflix monthly -> active -------------------------------------------------------------

    @Test
    fun `Netflix monthly is active`() {
        val subs = engine.build(
            events(
                debitSms("649.00", "05-06-2026", "NETFLIX") to LocalDateTime.of(2026, 6, 5, 9, 0),
                debitSms("649.00", "05-07-2026", "NETFLIX") to LocalDateTime.of(2026, 7, 5, 9, 0),
                debitSms("649.00", "05-08-2026", "NETFLIX") to LocalDateTime.of(2026, 8, 5, 9, 0),
            ),
            today = d("2026-08-20"),
        )
        val s = subs.single()
        assertEquals(SubscriptionStatus.ACTIVE, s.status)
        assertEquals(d("2026-09-05"), s.nextRenewal)
    }

    // ---- 4. UPI mandate SMS -> autopay -------------------------------------------------------------

    @Test
    fun `a UPI AutoPay mandate SMS is detected as autopay`() {
        val received = LocalDateTime.of(2026, 9, 10, 10, 0)
        val e = parse(
            "Dear Customer, AutoPay mandate of Rs 199 has been registered for SPOTIFY INDIA with " +
                "frequency MONTHLY. First debit on 12-Sep-2026. UMN: a1b2c3@sbi. -SBI",
            sender = "AX-SBIINB",
            at = received,
        )
        val subs = engine.build(listOfNotNull(e), today = d("2026-09-10"))
        val s = subs.single()
        assertEquals(RenewalSource.MANDATE, s.renewalSource)
        assertEquals(d("2026-09-12"), s.nextRenewal)
    }

    // ---- 5. Swiggy -> ignored ----------------------------------------------------------------------

    @Test
    fun `Swiggy food orders are ignored`() {
        val subs = engine.build(
            events(
                debitSms("245.00", "03-06-2026", "SWIGGY") to LocalDateTime.of(2026, 6, 3, 20, 0),
                debitSms("238.00", "03-07-2026", "SWIGGY") to LocalDateTime.of(2026, 7, 3, 20, 0),
                debitSms("250.00", "03-08-2026", "SWIGGY") to LocalDateTime.of(2026, 8, 3, 20, 0),
            ),
            today = d("2026-08-20"),
        )
        assertTrue(subs.isEmpty(), "a food delivery habit is not a subscription")
    }

    @Test
    fun `Swiggy One text is unaffected by the Swiggy order denylist, but still needs real evidence`() {
        // The real bug report: two Instamart grocery orders, a day apart, billed under the same
        // "Swiggy One" merchant identity as the actual membership. A bare amount+timing pattern is
        // exactly as good evidence for "two orders" as it is for "a renewal" — it must not resolve
        // that ambiguity by guessing Active; it has to ask.
        val subs = engine.build(
            events(
                debitSms("1286.00", "23-08-2026", "SWIGGY ONE") to LocalDateTime.of(2026, 8, 23, 22, 0),
                debitSms("1281.00", "24-08-2026", "SWIGGY ONE") to LocalDateTime.of(2026, 8, 24, 12, 50),
            ),
            today = d("2026-08-25"),
        )
        val s = subs.single()
        assertEquals("swiggyone", s.merchantId, "still correctly named, not dropped like a denylisted merchant")
        assertTrue(s.needsInput, "a bare pattern is not enough evidence to call this a subscription")
    }

    @Test
    fun `a genuine Swiggy One AutoPay mandate is still a confident subscription`() {
        val received = LocalDateTime.of(2026, 6, 1, 10, 0)
        val e = parse(
            "Dear Customer, AutoPay mandate of Rs 149 has been registered for SWIGGY ONE with " +
                "frequency MONTHLY. First debit on 03-Jun-2026. UMN: a1b2c3@sbi. -SBI",
            sender = "AX-SBIINB",
            at = received,
        )
        val subs = engine.build(listOfNotNull(e), today = d("2026-06-01"))
        val s = subs.single()
        assertEquals("swiggyone", s.merchantId)
        assertTrue(!s.needsInput, "an explicit mandate is real evidence, not a coincidence")
        assertEquals(SubscriptionStatus.ACTIVE, s.status)
    }

    @Test
    fun `a one-off Kindle purchase does not become an active Kindle Unlimited subscription`() {
        val subs = engine.build(
            events(
                debitSms("1499.00", "05-06-2026", "Kindle Paperwhite") to LocalDateTime.of(2026, 6, 5, 20, 0),
                debitSms("1450.00", "05-07-2026", "Kindle Oasis Cover") to LocalDateTime.of(2026, 7, 5, 20, 0),
            ),
            today = d("2026-07-20"),
        )
        val s = subs.single()
        assertEquals("kindle", s.merchantId)
        assertTrue(s.needsInput, "two unrelated Kindle purchases are not proof of a membership")
    }
}
