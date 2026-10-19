package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.ReminderAnswer
import com.aasif.watchdog.core.engine.ReminderState
import com.aasif.watchdog.core.engine.RenewalDayKind
import com.aasif.watchdog.core.engine.canSnoozeReminder
import com.aasif.watchdog.core.engine.chargeAlertResolved
import com.aasif.watchdog.core.engine.chargedDespiteCancelPlan
import com.aasif.watchdog.core.engine.verifyCancelResolved
import com.aasif.watchdog.core.engine.isAwaitingAnswer
import com.aasif.watchdog.core.engine.pendingQuestions
import com.aasif.watchdog.core.engine.renewalDayKind
import com.aasif.watchdog.core.engine.followUpDelay
import com.aasif.watchdog.core.engine.reminderStage
import com.aasif.watchdog.core.engine.shouldFollowUp
import com.aasif.watchdog.core.engine.shouldRemind
import com.aasif.watchdog.core.model.Charge
import com.aasif.watchdog.core.model.Confidence
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.RenewalSource
import com.aasif.watchdog.core.model.Subscription
import com.aasif.watchdog.core.model.SubscriptionStatus
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReminderDecisionTest {
    private fun d(s: String) = LocalDate.parse(s)
    private val today = d("2026-10-10")
    private fun at9(date: LocalDate) = date.atTime(9, 0)
    private val renewal = d("2026-10-12")

    private fun subscription(lastCharge: String?, status: SubscriptionStatus = SubscriptionStatus.ACTIVE) = Subscription(
        key = "netflix:649", merchantId = "netflix", name = "Netflix", category = "OTT",
        amount = Money(BigDecimal(649)), frequency = Frequency.MONTHLY, nextRenewal = d("2026-11-12"),
        renewalSource = RenewalSource.PAYMENT_HISTORY, confidence = Confidence.HIGH, status = status,
        charges = listOfNotNull(lastCharge?.let { Charge(d(it), Money(BigDecimal(649))) }), cancelUrl = null,
    )

    @Test
    fun `a fresh renewal is reminded`() {
        assertTrue(shouldRemind(at9(today), renewal, ReminderState()))
    }

    @Test
    fun `a reminder already shown is not repeated`() {
        assertFalse(shouldRemind(at9(today), renewal, ReminderState(lastStage = reminderStage(renewal, today))))
    }

    @Test
    fun `an answered renewal is not reminded again`() {
        assertFalse(shouldRemind(at9(today), renewal, ReminderState(answer = ReminderAnswer.KEEP)))
        assertFalse(shouldRemind(at9(today), renewal, ReminderState(answer = ReminderAnswer.CANCEL_PLANNED)))
    }

    @Test
    fun `a snoozed reminder stays quiet until its date, then comes back`() {
        val snoozed = ReminderState(snoozedUntil = d("2026-10-11"))
        assertFalse(shouldRemind(at9(today), renewal, snoozed))
        // The snooze date is the day before, so the reminder returns with the evening one.
        assertFalse(shouldRemind(at9(d("2026-10-11")), renewal, snoozed))
        assertTrue(shouldRemind(d("2026-10-11").atTime(19, 0), renewal, snoozed))
        assertTrue(shouldRemind(at9(d("2026-10-12")), renewal, snoozed))
    }

    @Test
    fun `a charge on or after the planned renewal means the cancel did not work`() {
        assertTrue(chargedDespiteCancelPlan(subscription("2026-10-12"), plannedFor = d("2026-10-12")))
        assertTrue(chargedDespiteCancelPlan(subscription("2026-10-14"), plannedFor = d("2026-10-12")))
    }

    @Test
    fun `a charge a day early still counts, since the two sides do not always agree on the day`() {
        assertTrue(chargedDespiteCancelPlan(subscription("2026-10-11"), plannedFor = d("2026-10-12")))
    }

    @Test
    fun `the previous cycle's charge does not`() {
        assertFalse(chargedDespiteCancelPlan(subscription("2026-09-12"), plannedFor = d("2026-10-12")))
        assertFalse(chargedDespiteCancelPlan(subscription(null), plannedFor = d("2026-10-12")))
    }

    @Test
    fun `marking it cancelled does not hide a charge that landed anyway`() {
        assertTrue(chargedDespiteCancelPlan(subscription("2026-10-12", SubscriptionStatus.CANCELLED), plannedFor = d("2026-10-12")))
    }

    @Test
    fun `a cancelled subscription with no new charge stays quiet`() {
        assertFalse(chargedDespiteCancelPlan(subscription("2026-09-12", SubscriptionStatus.CANCELLED), plannedFor = d("2026-10-12")))
    }

    @Test
    fun `the dashboard keeps asking after the notification was shown`() {
        val state = ReminderState(lastStage = reminderStage(renewal, today))
        assertFalse(shouldRemind(at9(today), renewal, state))
        assertTrue(isAwaitingAnswer(today, state))
    }

    @Test
    fun `pending questions are the due, unanswered, unsnoozed ones`() {
        fun due(name: String, next: String) = subscription(null).copy(key = name, name = name, nextRenewal = d(next))
        val subs = listOf(due("soon", "2026-10-12"), due("answered", "2026-10-11"), due("snoozed", "2026-10-11"), due("far", "2026-12-01"))
        val states = mapOf(
            "answered" to ReminderState(answer = ReminderAnswer.KEEP),
            "snoozed" to ReminderState(snoozedUntil = d("2026-10-11")),
        )
        val pending = subs.pendingQuestions(today, leadDays = 3) { sub, _ -> states[sub.key] ?: ReminderState() }
        assertEquals(listOf("soon"), pending.map { it.key })
    }

    @Test
    fun `snoozing is offered only while tomorrow is still before the charge`() {
        assertTrue(canSnoozeReminder(d("2026-10-12"), today))
        assertFalse(canSnoozeReminder(d("2026-10-11"), today))
        assertFalse(canSnoozeReminder(today, today))
    }

    @Test
    fun `stages get more urgent as the renewal nears`() {
        assertEquals(1, reminderStage(d("2026-10-13"), today))
        assertEquals(1, reminderStage(d("2026-10-12"), today))
        assertEquals(2, reminderStage(d("2026-10-11"), today))
        assertEquals(3, reminderStage(today, today))
    }

    @Test
    fun `a swiped reminder comes back at each later stage, once`() {
        // Shown three days out, then ignored.
        val swiped = ReminderState(lastStage = reminderStage(renewal, d("2026-10-09")))
        assertFalse(shouldRemind(at9(d("2026-10-09")), renewal, swiped))
        assertFalse(shouldRemind(at9(today), renewal, swiped))
        assertFalse(shouldRemind(at9(d("2026-10-11")), renewal, swiped))
        assertTrue(shouldRemind(d("2026-10-11").atTime(19, 0), renewal, swiped))
        // Shown the day before, ignored again: the next one is the day itself.
        val againSwiped = ReminderState(lastStage = 2)
        assertFalse(shouldRemind(at9(d("2026-10-11")), renewal, againSwiped))
        assertTrue(shouldRemind(at9(renewal), renewal, againSwiped))
        // And not a fourth time on the same day.
        assertFalse(shouldRemind(at9(renewal), renewal, ReminderState(lastStage = 3)))
    }

    @Test
    fun `a missed pass shows the reminder once, at whatever stage it has reached`() {
        assertTrue(shouldRemind(at9(renewal), renewal, ReminderState(lastStage = null)))
        assertTrue(shouldRemind(at9(renewal), renewal, ReminderState(lastStage = 1)))
    }

    @Test
    fun `answering stops every later stage`() {
        val kept = ReminderState(answer = ReminderAnswer.KEEP, lastStage = 1)
        assertFalse(shouldRemind(at9(d("2026-10-11")), renewal, kept))
        assertFalse(shouldRemind(at9(renewal), renewal, kept))
    }

    private val finalState = ReminderState(lastStage = 3)

    @Test
    fun `a swiped final reminder is followed up once, on the day`() {
        assertTrue(shouldFollowUp(renewal, renewal, finalState))
        assertFalse(shouldFollowUp(renewal, renewal, finalState.copy(followedUp = true)))
    }

    @Test
    fun `no follow-up before the day, after an answer, during a snooze, or before the final reminder was shown`() {
        assertFalse(shouldFollowUp(d("2026-10-11"), renewal, finalState))
        assertFalse(shouldFollowUp(renewal, renewal, finalState.copy(answer = ReminderAnswer.KEEP)))
        assertFalse(shouldFollowUp(renewal, renewal, finalState.copy(snoozedUntil = d("2026-10-13"))))
        assertFalse(shouldFollowUp(renewal, renewal, ReminderState(lastStage = 2)))
        assertFalse(shouldFollowUp(renewal, renewal, ReminderState()))
    }

    @Test
    fun `the follow-up waits four hours, but not past eight in the evening`() {
        fun at(h: Int, m: Int = 0) = d("2026-10-12").atTime(h, m)
        assertEquals(Duration.ofHours(4), followUpDelay(at(9)))
        assertEquals(Duration.ofHours(2), followUpDelay(at(18)))
        assertEquals(Duration.ofMinutes(45), followUpDelay(at(19, 15)))
    }

    @Test
    fun `too late in the evening there is no follow-up`() {
        assertNull(followUpDelay(d("2026-10-12").atTime(19, 45)))
        assertNull(followUpDelay(d("2026-10-12").atTime(22, 0)))
    }

    @Test
    fun `the day-before reminder waits for the evening`() {
        val dayBefore = d("2026-10-11")
        assertFalse(shouldRemind(dayBefore.atTime(9, 0), renewal, ReminderState(lastStage = 1)))
        assertFalse(shouldRemind(dayBefore.atTime(18, 59), renewal, ReminderState(lastStage = 1)))
        assertTrue(shouldRemind(dayBefore.atTime(19, 0), renewal, ReminderState(lastStage = 1)))
        assertTrue(shouldRemind(dayBefore.atTime(22, 30), renewal, ReminderState()))
    }

    @Test
    fun `the evening hour does not hold back the other stages`() {
        assertTrue(shouldRemind(today.atTime(9, 0), renewal, ReminderState()))
        assertTrue(shouldRemind(renewal.atTime(9, 0), renewal, ReminderState(lastStage = 1)))
    }

    @Test
    fun `an evening that was missed still leaves the morning of the renewal day`() {
        assertTrue(shouldRemind(renewal.atTime(9, 0), renewal, ReminderState(lastStage = 1)))
        assertFalse(shouldRemind(d("2026-10-11").atTime(20, 0), renewal, ReminderState(lastStage = 2)))
    }

    @Test
    fun `renewal-day wording follows where the date came from`() {
        assertEquals(RenewalDayKind.EXACT, renewalDayKind(RenewalSource.PRE_DEBIT_NOTICE, SubscriptionStatus.ACTIVE))
        assertEquals(RenewalDayKind.EXACT, renewalDayKind(RenewalSource.MANDATE, SubscriptionStatus.ACTIVE))
        assertEquals(RenewalDayKind.EXACT, renewalDayKind(RenewalSource.USER_SUPPLIED, SubscriptionStatus.ACTIVE))
        assertEquals(RenewalDayKind.EXACT, renewalDayKind(RenewalSource.MANUAL, SubscriptionStatus.ACTIVE))
        assertEquals(RenewalDayKind.ESTIMATED, renewalDayKind(RenewalSource.PAYMENT_HISTORY, SubscriptionStatus.ACTIVE))
        assertEquals(RenewalDayKind.ESTIMATED, renewalDayKind(RenewalSource.TRIAL_ESTIMATE, SubscriptionStatus.ACTIVE))
        assertEquals(RenewalDayKind.PLAN_END, renewalDayKind(RenewalSource.PLAN_EXPIRY, SubscriptionStatus.ACTIVE))
        assertEquals(RenewalDayKind.TRIAL, renewalDayKind(RenewalSource.MANDATE, SubscriptionStatus.TRIAL))
    }

    @Test
    fun `no reminder goes out in the night, the morning pass sends it`() {
        val renewal = d("2026-10-19")
        // A pre-debit text at 00:46 starts a check; the renewal is days away or today, either way it waits.
        assertFalse(shouldRemind(d("2026-10-17").atTime(0, 46), renewal, ReminderState()))
        assertFalse(shouldRemind(d("2026-10-18").atTime(23, 30), renewal, ReminderState()))
        assertFalse(shouldRemind(renewal.atTime(6, 59), renewal, ReminderState(lastStage = 2)))
        assertTrue(shouldRemind(renewal.atTime(7, 0), renewal, ReminderState(lastStage = 2)))
        assertTrue(shouldRemind(d("2026-10-17").atTime(9, 0), renewal, ReminderState()))
    }

    @Test
    fun `a charge alert stays while the plan still renews and goes once it no longer does`() {
        assertFalse(chargeAlertResolved(subscription("2026-10-05")))
        assertTrue(chargeAlertResolved(subscription("2026-10-05", SubscriptionStatus.CANCELLED)))
        assertTrue(chargeAlertResolved(subscription("2026-10-05", SubscriptionStatus.ENDING)))
        assertTrue(chargeAlertResolved(null))
    }

    @Test
    fun `the verify nudge goes when the renewal day begins, when the mandate is revoked, or when the answer is gone`() {
        val live = subscription("2026-09-12")
        val evening = d("2026-10-11")
        assertFalse(verifyCancelResolved(live, d("2026-10-12"), evening))
        assertTrue(verifyCancelResolved(live, d("2026-10-12"), d("2026-10-12")))
        assertTrue(verifyCancelResolved(live, null, evening))
        assertTrue(verifyCancelResolved(null, d("2026-10-12"), evening))
        assertTrue(verifyCancelResolved(subscription("2026-09-12", SubscriptionStatus.CANCELLED), d("2026-10-12"), evening))
        val revoked = live.copy(events = listOf(PaymentEvent.MandateRevoked("netflix", "Netflix", d("2026-10-11"))))
        assertTrue(verifyCancelResolved(revoked, d("2026-10-12"), evening))
        val oldRevocation = live.copy(
            events = listOf(
                PaymentEvent.MandateRevoked("netflix", "Netflix", d("2026-08-01")),
                PaymentEvent.MandateCreated("netflix", "Netflix", d("2026-09-12"), Money(BigDecimal(649)), Frequency.MONTHLY, null, null),
            ),
        )
        assertFalse(verifyCancelResolved(oldRevocation, d("2026-10-12"), evening))
    }
}
