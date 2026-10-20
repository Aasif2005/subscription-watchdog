package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.StampedEvent
import com.aasif.watchdog.core.engine.SubscriptionEngine
import com.aasif.watchdog.core.engine.isDuplicateOfAny
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.PaymentEvent.MandateCreated
import com.aasif.watchdog.core.model.PaymentEvent.MandateRevoked
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.core.model.SubscriptionStatus
import com.aasif.watchdog.core.parser.SmsParser
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real bank texts behind an active Claude Pro plan billed through Google Play (IOB, 18 Sep
 * 2026): created at 11:27, revoked at 11:29, created again at 11:30. The app used to drop the
 * 11:30 message as a duplicate of the 11:27 one, leaving "created, then revoked" — a cancelled plan.
 */
class MandateRecreatedTest {
    private val parser = SmsParser()
    private val day = LocalDate.of(2026, 9, 18)
    private val engine = SubscriptionEngine()

    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 9, 18, h, m)
    private fun sms(body: String, time: LocalDateTime) = parser.parse(Sms("VA-IOBCHN-S", body, time))

    private val created = "Your UPI-Mandate is successfully created towards Google Play for Rs.1999.00. Funds are blocked from A/C No. XXXX - IOB Bank"
    private val revoked = "Your UPI-Mandate is successfully Revoked towards Google Play for Rs.1999.00. Funds are unblocked from A/C No. XXXX - IOB Bank"

    private fun millis(t: LocalDateTime) = t.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun `the texts parse as a mandate and a revocation`() {
        assertIs<MandateCreated>(sms(created, at(11, 27)))
        assertIs<MandateRevoked>(sms(revoked, at(11, 29)))
    }

    @Test
    fun `a mandate created again after a revocation is not a duplicate of the first`() {
        val first = sms(created, at(11, 27))!!
        val revoke = sms(revoked, at(11, 29))!!
        val again = sms(created, at(11, 30))!!
        val known = listOf(StampedEvent(first, millis(at(11, 27))), StampedEvent(revoke, millis(at(11, 29))))
        assertFalse(again.isDuplicateOfAny(millis(at(11, 30)), known))
    }

    @Test
    fun `the same mandate announced twice with no revocation between is still a duplicate`() {
        val first = sms(created, at(11, 27))!!
        val again = sms(created, at(11, 28))!!
        assertTrue(again.isDuplicateOfAny(millis(at(11, 28)), listOf(StampedEvent(first, millis(at(11, 27))))))
    }

    private fun stamped(e: PaymentEvent, t: LocalDateTime): PaymentEvent = when (e) {
        is MandateCreated -> e.copy(receivedAt = t)
        is MandateRevoked -> e.copy(receivedAt = t)
        else -> e
    }

    @Test
    fun `created, revoked, created again leaves the plan live`() {
        val events = listOf(
            stamped(sms(created, at(11, 27))!!, at(11, 27)),
            stamped(sms(revoked, at(11, 29))!!, at(11, 29)),
            stamped(sms(created, at(11, 30))!!, at(11, 30)),
        )
        val sub = engine.build(events, today = LocalDate.of(2026, 10, 4)).single()
        assertTrue(sub.isLive, "the last thing that happened was a new mandate")
    }

    @Test
    fun `created then revoked later the same day is cancelled`() {
        val events = listOf(
            stamped(sms(created, at(11, 27))!!, at(11, 27)),
            stamped(sms(revoked, at(11, 29))!!, at(11, 29)),
        )
        val sub = engine.build(events, today = LocalDate.of(2026, 10, 4)).single()
        assertEquals(SubscriptionStatus.CANCELLED, sub.status)
    }

    @Test
    fun `revoked first, created after, is live`() {
        val events = listOf(
            stamped(sms(revoked, at(11, 27))!!, at(11, 27)),
            stamped(sms(created, at(11, 29))!!, at(11, 29)),
        )
        assertTrue(engine.build(events, today = LocalDate.of(2026, 10, 4)).single().isLive)
    }

    @Test
    fun `an amount of 1999 is carried through`() {
        val m = assertNotNull(sms(created, at(11, 27)) as? MandateCreated)
        assertEquals(Money(BigDecimal("1999.00")), m.amount)
        assertEquals(day, m.receivedOn)
    }
}
