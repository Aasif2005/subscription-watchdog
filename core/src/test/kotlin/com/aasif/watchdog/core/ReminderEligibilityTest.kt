package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.SubscriptionEngine
import com.aasif.watchdog.core.engine.autopayQuestions
import com.aasif.watchdog.core.engine.dueForReminder
import com.aasif.watchdog.core.engine.evidenceAfterCancelClaim
import com.aasif.watchdog.core.engine.reminderKeysToWithdraw
import com.aasif.watchdog.core.engine.shouldVerifyCancel
import com.aasif.watchdog.core.engine.stoppedRenewing
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.PaymentEvent.MandateCreated
import com.aasif.watchdog.core.model.PaymentEvent.MandateRevoked
import com.aasif.watchdog.core.model.SubscriptionStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Renewal reminders are for AutoPay that is not cancelled: live plans and free trials, including ones the
 * app is unsure about. A cancelled plan or trial, or one the user called cancelled, gets none.
 */
class ReminderEligibilityTest {
    private val engine = SubscriptionEngine()
    private fun money(v: String) = Money(BigDecimal(v))
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    /** ChatGPT Go as it sits on the phone: a mail-read mandate, ₹399 monthly, first charge three weeks after the receipt. */
    private val goMandate = MandateCreated(
        "chatgpt", "ChatGPT", d(2025, 11, 5), money("399.00"), Frequency.MONTHLY, d(2026, 11, 5), null,
        autoRenews = true, fromMail = true, planName = "ChatGPT Go",
    )

    @Test
    fun `a trial the app is unsure about is still reminded`() {
        val subs = engine.build(listOf(goMandate), d(2026, 11, 3))
        assertEquals(SubscriptionStatus.TRIAL, subs.single().status)
        assertEquals(1, subs.dueForReminder(d(2026, 11, 3), 3).size)
    }

    @Test
    fun `a live plan is reminded inside the window and not before it`() {
        val subs = engine.build(listOf(goMandate), d(2026, 10, 1))
        assertTrue(subs.dueForReminder(d(2026, 10, 1), 3).isEmpty())
    }

    @Test
    fun `a free trial whose AutoPay was revoked gets no reminder`() {
        val revoked = MandateRevoked("chatgpt", "ChatGPT", d(2026, 10, 20), null, amount = money("399.00"), activeUntil = null)
        val subs = engine.build(listOf(goMandate, revoked), d(2026, 11, 3))
        assertFalse(subs.single().isLive)
        assertTrue(subs.dueForReminder(d(2026, 11, 3), 3).isEmpty())
    }

    @Test
    fun `a plan with no renewal date is not reminded, it is asked about`() {
        val undated = MandateCreated("u:somenewapp", "Some New App", d(2026, 10, 1), null, null, null, null)
        val sub = engine.build(listOf(undated), d(2026, 10, 5)).single()
        assertNull(sub.nextRenewal)
        assertFalse(sub.remindable)
        assertTrue(sub.needsInput)
    }

    @Test
    fun `a plan the user called cancelled stops being reminded`() {
        val sub = engine.build(listOf(goMandate), d(2026, 11, 3)).single()
        val cancelled = sub.stoppedRenewing(d(2026, 11, 3))
        assertFalse(cancelled.remindable)
        assertTrue(listOf(cancelled).dueForReminder(d(2026, 11, 3), 3).isEmpty())
    }

    @Test
    fun `a charge notice after the user said cancelled contradicts it`() {
        val notice = PaymentEvent.PreDebitNotice("chatgpt", "ChatGPT", d(2026, 11, 3), money("399.00"), d(2026, 11, 5))
        val sub = engine.build(listOf(goMandate, notice), d(2026, 11, 3)).single()
        assertEquals(d(2026, 11, 3), sub.evidenceAfterCancelClaim(d(2026, 11, 1)))
        // Said on the same day, or after the notice: nothing contradicts it.
        assertNull(sub.evidenceAfterCancelClaim(d(2026, 11, 3)))
        assertNull(sub.evidenceAfterCancelClaim(d(2026, 11, 4)))
    }

    @Test
    fun `a charge after the user said cancelled contradicts it, one before does not`() {
        val charge = PaymentEvent.Debit("chatgpt", "ChatGPT", d(2026, 11, 5), money("399.00"), d(2026, 11, 5), true, false)
        val sub = engine.build(listOf(goMandate, charge), d(2026, 11, 6)).single()
        assertEquals(d(2026, 11, 5), sub.evidenceAfterCancelClaim(d(2026, 11, 4)))
        assertNull(sub.evidenceAfterCancelClaim(d(2026, 11, 5)))
    }

    // ---- Cancelling the AutoPay: the plan keeps running, so it stays under Active until its period ends ----

    @Test
    fun `a trial the user cancelled the AutoPay for stays active until its first charge would have come`() {
        val sub = engine.build(listOf(goMandate), d(2026, 10, 15)).single()
        val stopped = sub.stoppedRenewing(d(2026, 10, 15))
        assertEquals(SubscriptionStatus.ENDING, stopped.status)
        assertEquals(d(2026, 11, 5), stopped.activeUntil)
        assertTrue(stopped.isInUse)
        assertFalse(stopped.remindable)
        // Once that day has passed it ends on its own.
        assertEquals(SubscriptionStatus.CANCELLED, sub.stoppedRenewing(d(2026, 11, 6)).status)
    }

    @Test
    fun `a plan paid every month stays active until its paid month ends after the AutoPay is cancelled`() {
        val charges = listOf(d(2026, 8, 10), d(2026, 9, 10), d(2026, 10, 10)).map {
            PaymentEvent.Debit("netflix", "Netflix", it, money("199.00"), it, true, false)
        }
        val stopped = engine.build(charges, d(2026, 10, 15)).single().stoppedRenewing(d(2026, 10, 15))
        assertEquals(SubscriptionStatus.ENDING, stopped.status)
        assertEquals(d(2026, 11, 10), stopped.activeUntil)
        // Long after the last paid month it is over.
        assertEquals(SubscriptionStatus.CANCELLED, engine.build(charges, d(2027, 1, 15)).single().stoppedRenewing(d(2027, 1, 15)).status)
    }

    @Test
    fun `every live subscription is asked about its AutoPay until answered, known renewal date or not`() {
        val undated = MandateCreated("u:somenewapp", "Some New App", d(2026, 10, 1), null, null, null, null)
        val subs = engine.build(listOf(goMandate, undated), d(2026, 10, 15))
        val asked = subs.autopayQuestions(d(2026, 10, 15)) { false }
        assertEquals(setOf("chatgpt", "u:somenewapp"), asked.map { it.key }.toSet())
        assertTrue(subs.autopayQuestions(d(2026, 10, 15)) { it.key == "chatgpt" }.none { it.key == "chatgpt" })
    }

    @Test
    fun `a manual entry is asked too`() {
        val gym = engine.manual("manual:2", "gym", money("5222"), Frequency.MONTHLY, d(2026, 10, 17), false, d(2026, 10, 15))
        assertEquals(listOf("manual:2"), listOf(gym).autopayQuestions(d(2026, 10, 15)) { false }.map { it.key })
    }

    @Test
    fun `an answer holds for one renewal and the question returns for the next`() {
        val gym = { today: LocalDate -> engine.manual("manual:2", "gym", money("5222"), Frequency.MONTHLY, d(2026, 10, 17), false, today) }
        val answeredFor = d(2026, 10, 17)
        val isAnswered = { s: com.aasif.watchdog.core.model.Subscription -> s.nextRenewal == answeredFor }
        assertTrue(listOf(gym(d(2026, 10, 15))).autopayQuestions(d(2026, 10, 15), isAnswered).isEmpty())
        // After the 17th the next renewal is 17 Nov: a new renewal, a new question.
        assertEquals(1, listOf(gym(d(2026, 11, 14))).autopayQuestions(d(2026, 11, 14), isAnswered).size)
    }

    @Test
    fun `a mandate set up today is not yet asked about, and a revoked one is never`() {
        val fresh = MandateCreated("u:somenewapp", "Some New App", d(2026, 10, 15), money("99.00"), Frequency.MONTHLY, d(2026, 11, 15), null)
        assertTrue(engine.build(listOf(fresh), d(2026, 10, 15)).autopayQuestions(d(2026, 10, 15)) { false }.isEmpty())
        val revoked = MandateRevoked("chatgpt", "ChatGPT", d(2026, 10, 20), null, amount = money("399.00"), activeUntil = null)
        assertTrue(engine.build(listOf(goMandate, revoked), d(2026, 11, 3)).autopayQuestions(d(2026, 11, 3)) { false }.isEmpty())
    }

    @Test
    fun `verify-cancel nudge comes the evening before the renewal, once`() {
        val renewal = d(2026, 10, 29)
        fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0)
        assertFalse(shouldVerifyCancel(at(28, 9), renewal, false))
        assertTrue(shouldVerifyCancel(at(28, 19), renewal, false))
        assertTrue(shouldVerifyCancel(at(28, 22), renewal, false))
        assertFalse(shouldVerifyCancel(at(28, 19), renewal, true))
        assertFalse(shouldVerifyCancel(at(27, 20), renewal, false))
        assertFalse(shouldVerifyCancel(at(29, 9), renewal, false))
    }

    @Test
    fun `a reminder is withdrawn once the renewal has moved on or the AutoPay ended`() {
        val gym = MandateCreated("u:gym", "Gym", d(2026, 9, 1), money("500.00"), Frequency.MONTHLY, d(2026, 9, 17), null)
        val sep = PaymentEvent.Debit("u:gym", "Gym", d(2026, 9, 17), money("500.00"), d(2026, 9, 17), true)
        val before = engine.build(listOf(gym, sep), d(2026, 10, 15)).single()
        assertEquals(d(2026, 10, 17), before.nextRenewal)
        // Due within the window: its reminder stands.
        assertTrue(listOf(before).reminderKeysToWithdraw(d(2026, 10, 15), 3).isEmpty())
        // The debit happened: the renewal moved a cycle on, so the old reminder goes.
        val debited = engine.build(listOf(gym, sep, PaymentEvent.Debit("u:gym", "Gym", d(2026, 10, 17), money("500.00"), d(2026, 10, 17), true)), d(2026, 10, 17)).single()
        assertEquals(listOf(debited.key), listOf(debited).reminderKeysToWithdraw(d(2026, 10, 17), 3))
        // The AutoPay was revoked (paid up to a date): no longer due either.
        val revoked = MandateRevoked("u:gym", "Gym", d(2026, 10, 15), null, amount = money("500.00"), activeUntil = d(2026, 10, 20))
        val ending = engine.build(listOf(gym, sep, revoked), d(2026, 10, 16)).single()
        assertEquals(listOf(ending.key), listOf(ending).reminderKeysToWithdraw(d(2026, 10, 16), 3))
    }
}
