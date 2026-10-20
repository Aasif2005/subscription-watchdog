package com.aasif.watchdog.core

import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.RenewalSource
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.core.model.SubscriptionStatus
import com.aasif.watchdog.core.engine.SubscriptionEngine
import com.aasif.watchdog.core.parser.SmsParser
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A receipt that never names a renewal date often names the plan's length instead — "3 months
 * plan" — which is the only thing that turns "you subscribed on 1 Sep" into a date worth warning
 * about. See [com.aasif.watchdog.core.parser.Extractors.planDuration].
 */
class PlanDurationTest {
    private val parser = SmsParser(fromEmail = true)
    private val received = LocalDateTime.of(2026, 9, 1, 10, 0)

    private fun parse(body: String, sender: String = "billing@netflix.com") =
        parser.parse(Sms(sender, body, received))

    @Test
    fun `a 3 months plan ends 3 months after it was bought`() {
        val e = assertIs<PaymentEvent.MandateCreated>(parse(
            "Payment of Rs 599 received. Welcome to your Netflix subscription: 3 months plan, activated today.",
        ))
        assertEquals(LocalDate.of(2026, 12, 1), e.validTill)
        assertEquals(Frequency.QUARTERLY, e.frequency)
    }

    @Test
    fun `a 12-month subscription runs a year`() {
        val e = assertIs<PaymentEvent.MandateCreated>(parse(
            "Payment of Rs 999 received. Your Netflix subscription: 12-month plan activated today.",
        ))
        assertEquals(LocalDate.of(2027, 9, 1), e.validTill)
        assertEquals(Frequency.YEARLY, e.frequency)
    }

    @Test
    fun `a 90 day plan is read in days, not months`() {
        val e = assertIs<PaymentEvent.MandateCreated>(parse(
            "Payment of Rs 299 received. Your Netflix subscription is active for 90 days, activated today.",
        ))
        assertEquals(LocalDate.of(2026, 11, 30), e.validTill)
    }

    @Test
    fun `a named annual plan is read as a year even without a number`() {
        val e = assertIs<PaymentEvent.MandateCreated>(parse(
            "Payment of Rs 1499 received. Your Netflix annual subscription plan activated today.",
        ))
        assertEquals(LocalDate.of(2027, 9, 1), e.validTill)
        assertEquals(Frequency.YEARLY, e.frequency)
    }

    // ---- What the engine does with that date --------------------------------------------------

    private val engine = SubscriptionEngine()

    @Test
    fun `a plan expiry in the future is offered as the renewal at medium confidence`() {
        val mandate = PaymentEvent.MandateCreated(
            "netflix", "Netflix", LocalDate.of(2026, 9, 1), null, Frequency.QUARTERLY,
            startDate = LocalDate.of(2026, 9, 1), validTill = LocalDate.of(2026, 12, 1),
        )
        val s = engine.build(listOf(mandate), today = LocalDate.of(2026, 9, 10)).single()
        assertEquals(LocalDate.of(2026, 12, 1), s.nextRenewal)
        assertEquals(RenewalSource.PLAN_EXPIRY, s.renewalSource)
        assertEquals(SubscriptionStatus.ACTIVE, s.status)
    }

    @Test
    fun `a plan that already expired stays lapsed, not renewing on its own end date`() {
        val mandate = PaymentEvent.MandateCreated(
            "netflix", "Netflix", LocalDate.of(2026, 1, 1), null, Frequency.QUARTERLY,
            startDate = LocalDate.of(2026, 1, 1), validTill = LocalDate.of(2026, 4, 1),
        )
        val s = engine.build(listOf(mandate), today = LocalDate.of(2026, 9, 10)).single()
        assertEquals(SubscriptionStatus.LAPSED, s.status)
        assertNull(s.nextRenewal)
    }
}
