package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.SubscriptionEngine
import com.aasif.watchdog.core.engine.dueForReminder
import com.aasif.watchdog.core.engine.monthlyTotal
import com.aasif.watchdog.core.engine.yearlySavings
import com.aasif.watchdog.core.model.Confidence
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.PaymentEvent.Debit
import com.aasif.watchdog.core.model.RenewalSource
import com.aasif.watchdog.core.model.SubscriptionStatus
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionEngineTest {
    private val engine = SubscriptionEngine()

    private fun d(s: String) = LocalDate.parse(s)
    private fun rs(v: Int) = BigDecimal(v)
    private fun money(v: Int) = Money(BigDecimal(v))

    private fun debit(id: String, amount: Int, date: String, viaMandate: Boolean = false, name: String = id) =
        Debit(id, name, d(date), money(amount), d(date), viaMandate)

    private fun mandate(id: String, amount: Int?, received: String, start: String?, f: Frequency? = Frequency.MONTHLY) =
        PaymentEvent.MandateCreated(id, id, d(received), amount?.let(::money), f, start?.let(::d), null)

    @Test
    fun `predicts from payment history`() {
        val subs = engine.build(
            listOf(debit("netflix", 649, "2026-06-05"), debit("netflix", 649, "2026-07-05"), debit("netflix", 649, "2026-08-05")),
            today = d("2026-08-20"),
        )
        val s = subs.single()
        assertEquals(Frequency.MONTHLY, s.frequency)
        assertEquals(d("2026-09-05"), s.nextRenewal)
        assertEquals(RenewalSource.PAYMENT_HISTORY, s.renewalSource)
        assertEquals(Confidence.HIGH, s.confidence)
    }

    @Test
    fun `keeps the 31st billing day after a short month`() {
        val s = engine.build(
            listOf(debit("spotify", 199, "2026-01-31", true), debit("spotify", 199, "2026-02-28", true)),
            today = d("2026-03-01"),
        ).single()
        assertEquals(d("2026-03-31"), s.nextRenewal)
    }

    @Test
    fun `mandate schedule is followed, from the debit when it landed a day off`() {
        val s = engine.build(
            listOf(
                mandate("netflix", 649, "2026-01-10", "2026-01-10"),
                debit("netflix", 649, "2026-07-11"), debit("netflix", 649, "2026-08-11"),
            ),
            today = d("2026-08-15"),
        ).single()
        // Debited on the 11th, a day after the mandate's 10th: the next one follows the real debit.
        assertEquals(d("2026-09-11"), s.nextRenewal)
        assertEquals(RenewalSource.MANDATE, s.renewalSource)
    }

    @Test
    fun `a charge far from the mandate schedule does not move it`() {
        val s = engine.build(
            listOf(mandate("netflix", 649, "2026-01-10", "2026-01-10"), debit("netflix", 649, "2026-08-30")),
            today = d("2026-09-01"),
        ).single()
        assertEquals(d("2026-09-10"), s.nextRenewal)
    }

    @Test
    fun `a new amount on the newest charge is a price change, an extra one-off charge is not`() {
        val raised = engine.build(
            listOf(debit("netflix", 499, "2026-07-11", true), debit("netflix", 499, "2026-08-11", true), debit("netflix", 649, "2026-09-11", true)),
            today = d("2026-09-12"),
        ).single()
        val change = raised.priceChange!!
        assertEquals(d("2026-09-11"), change.on)
        assertEquals(BigDecimal("499"), change.from.amount.setScale(0))
        assertEquals(BigDecimal("649"), change.to.amount.setScale(0))
        val steady = engine.build(
            listOf(debit("netflix", 649, "2026-07-11", true), debit("netflix", 649, "2026-08-11", true), debit("netflix", 649, "2026-09-11", true)),
            today = d("2026-09-12"),
        ).single()
        assertNull(steady.priceChange)
        // A second, different charge two days after the last one is not a new price.
        val extra = engine.build(
            listOf(debit("netflix", 649, "2026-07-11", true), debit("netflix", 649, "2026-08-11", true), debit("netflix", 649, "2026-09-11", true), debit("netflix", 99, "2026-09-13", true)),
            today = d("2026-09-14"),
        ).firstOrNull()
        assertNull(extra?.priceChange)
    }

    @Test
    fun `pre-debit notice beats everything`() {
        val s = engine.build(
            listOf(
                mandate("netflix", 649, "2026-01-10", "2026-01-10"),
                debit("netflix", 649, "2026-08-10"),
                PaymentEvent.PreDebitNotice("netflix", "Netflix", d("2026-09-10"), money(649), d("2026-09-11")),
            ),
            today = d("2026-09-10"),
        ).single()
        assertEquals(d("2026-09-11"), s.nextRenewal)
        assertEquals(RenewalSource.PRE_DEBIT_NOTICE, s.renewalSource)
    }

    @Test
    fun `mandate with a far start date is a trial`() {
        val s = engine.build(listOf(mandate("youtube", 149, "2026-09-20", "2026-10-20")), today = d("2026-09-25")).single()
        assertEquals(SubscriptionStatus.TRIAL, s.status)
        assertEquals(d("2026-10-20"), s.nextRenewal)
    }

    @Test
    fun `trial length from the catalog when the SMS has no date`() {
        val s = engine.build(listOf(mandate("spotify", 1, "2026-09-01", null)), today = d("2026-09-10")).single()
        assertEquals(SubscriptionStatus.TRIAL, s.status)
        assertEquals(d("2026-10-01"), s.nextRenewal)
        assertEquals(RenewalSource.TRIAL_ESTIMATE, s.renewalSource)
    }

    /**
     * A plan change sends "old mandate revoked" and "new mandate created" on the same day, and the
     * live plan is the new one — this used to report the user's active Claude Pro as cancelled.
     */
    @Test
    fun `a revocation on the day a new mandate is created does not cancel it`() {
        val subs = engine.build(
            listOf(
                mandate("claude", 1999, "2026-09-18", "2026-09-18"),
                PaymentEvent.MandateRevoked("claude", "Claude", d("2026-09-18")),
            ),
            today = d("2026-10-04"),
        )
        assertTrue(subs.single().isLive, "same-day revoke + new mandate is a replacement, not a cancellation")
    }

    @Test
    fun `a revocation on the day of a charge stops the AutoPay, and the paid month runs out`() {
        val subs = engine.build(
            listOf(
                mandate("netflix", 649, "2026-01-10", "2026-01-10"),
                debit("netflix", 649, "2026-08-10"),
                PaymentEvent.MandateRevoked("netflix", "Netflix", d("2026-08-10")),
            ),
            today = d("2026-08-25"),
        )
        assertEquals(SubscriptionStatus.ENDING, subs.single().status)
        assertEquals(d("2026-09-10"), subs.single().activeUntil)
        assertNull(subs.single().nextRenewal)
    }

    @Test
    fun `revoked mandate stops renewing, stays usable until the paid month ends, and counts as savings`() {
        val subs = engine.build(
            listOf(
                mandate("netflix", 649, "2026-01-10", "2026-01-10"),
                debit("netflix", 649, "2026-08-10"),
                PaymentEvent.MandateRevoked("netflix", "Netflix", d("2026-08-20")),
            ),
            today = d("2026-08-25"),
        )
        assertEquals(SubscriptionStatus.ENDING, subs.single().status)
        assertEquals(d("2026-09-10"), subs.single().activeUntil)
        assertNull(subs.single().nextRenewal)
        assertEquals(BigDecimal("7788.00"), subs.yearlySavings())
        // Once the paid month is over it is simply cancelled.
        assertEquals(SubscriptionStatus.CANCELLED, engine.build(
            listOf(
                mandate("netflix", 649, "2026-01-10", "2026-01-10"),
                debit("netflix", 649, "2026-08-10"),
                PaymentEvent.MandateRevoked("netflix", "Netflix", d("2026-08-20")),
            ),
            today = d("2026-09-11"),
        ).single().status)
    }

    @Test
    fun `no charge for two periods means lapsed`() {
        val s = engine.build(
            listOf(debit("netflix", 649, "2026-02-05"), debit("netflix", 649, "2026-03-05"), debit("netflix", 649, "2026-04-05")),
            today = d("2026-09-01"),
        ).single()
        assertEquals(SubscriptionStatus.LAPSED, s.status)
    }

    @Test
    fun `unknown payee needs three regular monthly charges`() {
        val two = listOf(debit("u:gym", 999, "2026-07-01"), debit("u:gym", 999, "2026-08-01"))
        assertTrue(engine.build(two, d("2026-08-10")).isEmpty())

        val three = two + debit("u:gym", 999, "2026-09-01")
        val s = engine.build(three, d("2026-09-10")).single()
        assertEquals(Confidence.MEDIUM, s.confidence)
        assertEquals(d("2026-10-01"), s.nextRenewal)
    }

    @Test
    fun `weekly charges to an unknown shop are a habit, not a subscription`() {
        val weekly = (0..5).map { debit("u:vegshop", 400, d("2026-08-01").plusWeeks(it.toLong()).toString()) }
        assertTrue(engine.build(weekly, d("2026-09-10")).isEmpty())
    }

    @Test
    fun `two plans with the same merchant stay separate`() {
        val events = listOf(99, 649).flatMap { amt ->
            listOf("2026-06-03", "2026-07-03", "2026-08-03").map { debit("googleplay", amt, it) }
        }
        val subs = engine.build(events, d("2026-08-10"))
        assertEquals(setOf("googleplay#99", "googleplay#649"), subs.map { it.key }.toSet())
    }

    @Test
    fun `price hike stays one plan`() {
        val s = engine.build(
            listOf(debit("netflix", 499, "2026-05-05"), debit("netflix", 499, "2026-06-05"), debit("netflix", 649, "2026-07-05"), debit("netflix", 649, "2026-08-05")),
            today = d("2026-08-10"),
        ).single()
        assertEquals(money(649), s.amount)
        assertEquals(4, s.charges.size)
    }

    @Test
    fun `yearly plan, monthly total and reminders`() {
        val subs = engine.build(
            listOf(
                debit("netflix", 649, "2026-07-05"), debit("netflix", 649, "2026-08-05"),
                debit("prime", 1499, "2025-09-12", true), debit("prime", 1499, "2024-09-12", true),
            ),
            today = d("2026-09-02"),
        )
        val prime = subs.first { it.merchantId == "prime" }
        assertEquals(Frequency.YEARLY, prime.frequency)
        assertEquals(d("2026-09-12"), prime.nextRenewal)
        assertEquals(BigDecimal("773.92"), subs.monthlyTotal()) // 649 + 1499/12

        assertEquals(listOf("netflix"), subs.dueForReminder(d("2026-09-02"), leadDays = 3).map { it.merchantId })
    }

    @Test
    fun `manual entry`() {
        val s = engine.manual("m:gym", "Gym", money(1500), Frequency.MONTHLY, d("2026-01-15"), isTrial = false, today = d("2026-09-20"))
        assertEquals(d("2026-10-15"), s.nextRenewal)
    }

    @Test
    fun `an amount or frequency that was never stated is marked unknown, not zero`() {
        val s = engine.build(listOf(mandate("hotstar", null, "2026-09-01", null, f = null)), today = d("2026-09-10")).single()
        assertFalse(s.amountKnown, "no amount was ever seen")
        assertFalse(s.frequencyKnown, "no frequency was ever seen")
        assertEquals(BigDecimal.ZERO, s.amount.amount, "the placeholder value, not counted anywhere")
    }

    @Test
    fun `a subscription with no known amount is excluded from the monthly total`() {
        val subs = engine.build(
            listOf(
                debit("netflix", 649, "2026-07-05"), debit("netflix", 649, "2026-08-05"),
                mandate("hotstar", null, "2026-09-01", null, f = null),
            ),
            today = d("2026-09-10"),
        )
        assertEquals(setOf("netflix", "hotstar"), subs.map { it.merchantId }.toSet())
        assertEquals(BigDecimal("649.00"), subs.monthlyTotal(), "hotstar's unknown amount must not count as ₹0")
    }
}
