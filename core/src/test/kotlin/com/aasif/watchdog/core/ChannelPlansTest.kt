package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.SubscriptionEngine
import com.aasif.watchdog.core.engine.channelRekeyTarget
import com.aasif.watchdog.core.engine.withoutEndedChannelPlans
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.PaymentEvent.Debit
import com.aasif.watchdog.core.model.PaymentEvent.MandateCreated
import com.aasif.watchdog.core.model.PaymentEvent.MandateRevoked
import com.aasif.watchdog.core.model.SubscriptionStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Google Play bills for many apps under one payee, so the bank texts for different apps all say
 * "Google Play". The sequence below has the amounts and times of a real phone's texts: three
 * different apps' mandates, each revoked by its own amount, and one live plan.
 */
class ChannelPlansTest {
    private val engine = SubscriptionEngine()
    private val today = LocalDate.of(2026, 10, 8)

    private fun money(v: String) = Money(BigDecimal(v))
    private fun at(s: String) = LocalDateTime.parse(s)
    private fun created(amount: String, time: String) =
        MandateCreated("googleplay", "Google Play", at(time).toLocalDate(), money(amount), null, null, null, receivedAt = at(time))
    private fun revoked(amount: String?, time: String) =
        MandateRevoked("googleplay", "Google Play", at(time).toLocalDate(), at(time), amount = amount?.let(::money))

    private val history: List<PaymentEvent> = listOf(
        created("1850.00", "2025-09-30T12:48"), revoked("1850.00", "2025-10-13T20:46"),
        created("139.00", "2026-06-16T02:18"),
        Debit("googleplay", "Google Play", LocalDate.of(2026, 6, 16), money("99"), LocalDate.of(2026, 6, 16), viaMandate = false),
        created("139.00", "2026-08-15T06:11"), revoked("139.00", "2026-08-15T06:24"), revoked("149.00", "2026-08-15T06:24"),
        created("1999.00", "2026-09-18T11:27"), revoked("1999.00", "2026-09-18T11:29"), created("1999.00", "2026-09-18T11:30"),
    )

    @Test
    fun `each amount is its own app, and each revocation ends only its own`() {
        val subs = engine.build(history, today)
        val byAmount = subs.associateBy { it.amount.amount.setScale(0) }
        assertEquals(SubscriptionStatus.CANCELLED, byAmount.getValue(BigDecimal(1850)).status)
        assertEquals(SubscriptionStatus.CANCELLED, byAmount.getValue(BigDecimal(139)).status)
        assertEquals(SubscriptionStatus.ACTIVE, byAmount.getValue(BigDecimal(1999)).status)
        assertEquals(3, subs.size, "a revocation alone (₹149) or a one-off ₹99 payment is not a plan: $subs")
    }

    @Test
    fun `only the live app is listed`() {
        val listed = engine.build(history, today).withoutEndedChannelPlans()
        assertEquals(listOf(BigDecimal(1999)), listed.map { it.amount.amount.setScale(0) })
        assertEquals("googleplay#1999", listed.single().key)
    }

    @Test
    fun `the live app's proof is its own three texts and nothing else`() {
        val claude = engine.build(history, today).single { it.isLive }
        assertEquals(
            setOf(created("1999.00", "2026-09-18T11:27"), revoked("1999.00", "2026-09-18T11:29"), created("1999.00", "2026-09-18T11:30")),
            claude.events.toSet(),
        )
    }

    @Test
    fun `a revocation without an amount cannot be placed when several apps share the payee`() {
        val events = listOf(created("139.00", "2026-06-16T02:18"), created("1999.00", "2026-09-18T11:30"), revoked(null, "2026-09-20T10:00"))
        assertTrue(engine.build(events, today).all { it.isLive }, "guessing would cancel the wrong app")
    }

    @Test
    fun `with a single app on the channel, a revocation without an amount still counts`() {
        val events = listOf(created("1999.00", "2026-09-18T11:30"), revoked(null, "2026-09-20T10:00"))
        assertEquals(SubscriptionStatus.CANCELLED, engine.build(events, today).single().status)
    }

    @Test
    fun `an ordinary merchant's revocation with an amount ends only that plan`() {
        fun debit(amount: String, date: String) = Debit("netflix", "Netflix", LocalDate.parse(date), money(amount), LocalDate.parse(date), viaMandate = true)
        val events = listOf(
            debit("199", "2026-06-05"), debit("199", "2026-07-05"), debit("199", "2026-08-05"),
            debit("649", "2026-06-10"), debit("649", "2026-07-10"), debit("649", "2026-08-10"),
            MandateRevoked("netflix", "Netflix", LocalDate.of(2026, 8, 20), amount = money("199")),
        )
        val subs = engine.build(events, LocalDate.of(2026, 8, 25)).associateBy { it.amount.amount.setScale(0) }
        // The ₹199 plan's AutoPay is off; its August payment still covers it until 5 Sep.
        assertEquals(SubscriptionStatus.ENDING, subs.getValue(BigDecimal(199)).status)
        assertEquals(LocalDate.of(2026, 9, 5), subs.getValue(BigDecimal(199)).activeUntil)
        assertEquals(SubscriptionStatus.ACTIVE, subs.getValue(BigDecimal(649)).status)
        assertTrue(subs.getValue(BigDecimal(649)).events.none { it is MandateRevoked })
        assertTrue(subs.getValue(BigDecimal(649)).events.all { it !is Debit || it.amount == money("649") })
    }

    @Test
    fun `a plan set up this month counts its first payment`() {
        val events = listOf(
            MandateCreated("spotify", "Spotify", LocalDate.of(2026, 10, 12), money("119"), Frequency.MONTHLY, null, null),
            Debit("spotify", "Spotify", LocalDate.of(2026, 10, 12), money("119"), LocalDate.of(2026, 10, 12), viaMandate = true),
        )
        val sub = engine.build(events, LocalDate.of(2026, 10, 13)).single()
        assertEquals(LocalDate.of(2026, 10, 12), sub.lastCharged)
    }

    @Test
    fun `a name saved under the bare channel key moves to the plan with the newest mandate`() {
        val subs = engine.build(history, today)
        assertEquals("googleplay#1999", channelRekeyTarget("googleplay", subs))
    }

    @Test
    fun `a channel's plan is keyed by amount even when it is the only one, so the key never flips`() {
        val subs = engine.build(listOf(created("1999.00", "2026-09-18T11:30")), today)
        assertEquals("googleplay#1999", subs.single().key)
        assertEquals("googleplay#1999", channelRekeyTarget("googleplay", subs))
    }

    @Test
    fun `an ordinary merchant with one plan keeps its plain key`() {
        val subs = engine.build(listOf(MandateCreated("netflix", "Netflix", today, money("649"), null, null, null)), today)
        assertEquals("netflix", subs.single().key)
    }

    @Test
    fun `nothing to move to before the texts are read again`() {
        assertEquals(null, channelRekeyTarget("googleplay", emptyList()))
    }

    // ---- Paid for, AutoPay cancelled (synthetic events in the shape of a real first month) ----

    private fun spotify(vararg extra: PaymentEvent, frequency: Frequency? = Frequency.MONTHLY, start: LocalDate? = null) = listOf(
        MandateCreated("spotify", "Spotify", LocalDate.of(2026, 10, 12), money("119"), frequency, start, null, receivedAt = at("2026-10-12T10:00")),
        Debit("spotify", "Spotify", LocalDate.of(2026, 10, 12), money("119"), LocalDate.of(2026, 10, 12), viaMandate = true),
    ) + extra

    private val cancelledSameDay = MandateRevoked("spotify", "Spotify", LocalDate.of(2026, 10, 12), at("2026-10-12T10:30"), amount = money("119"))

    @Test
    fun `paid for a month and cancelled the same day stays usable until the month ends`() {
        val sub = engine.build(spotify(cancelledSameDay), LocalDate.of(2026, 10, 13)).single()
        assertEquals(SubscriptionStatus.ENDING, sub.status)
        assertEquals(LocalDate.of(2026, 11, 12), sub.activeUntil)
        assertEquals(false, sub.isLive, "nothing will be charged, so no renewal reminder")
        assertEquals(true, sub.isInUse)
    }

    @Test
    fun `a stated end of access beats the computed one`() {
        val stated = cancelledSameDay.copy(activeUntil = LocalDate.of(2026, 11, 20))
        assertEquals(LocalDate.of(2026, 11, 20), engine.build(spotify(stated), LocalDate.of(2026, 10, 13)).single().activeUntil)
    }

    @Test
    fun `after the paid period it is cancelled`() {
        assertEquals(SubscriptionStatus.CANCELLED, engine.build(spotify(cancelledSameDay), LocalDate.of(2026, 11, 13)).single().status)
    }

    @Test
    fun `with no stated period the end date is left unknown rather than guessed`() {
        val sub = engine.build(spotify(cancelledSameDay, frequency = null), LocalDate.of(2026, 10, 13)).single()
        assertEquals(SubscriptionStatus.ENDING, sub.status)
        assertEquals(null, sub.activeUntil)
    }

    @Test
    fun `cancelled before any payment is just cancelled`() {
        val events = listOf(
            MandateCreated("spotify", "Spotify", LocalDate.of(2026, 10, 12), money("119"), Frequency.MONTHLY, null, null, receivedAt = at("2026-10-12T10:00")),
            cancelledSameDay,
        )
        assertEquals(SubscriptionStatus.CANCELLED, engine.build(events, LocalDate.of(2026, 10, 13)).single().status)
    }

    @Test
    fun `a free trial cancelled before it converts lasts until the trial ends`() {
        val events = listOf(
            MandateCreated("spotify", "Spotify", LocalDate.of(2026, 10, 12), money("1"), Frequency.MONTHLY, LocalDate.of(2026, 11, 12), null, receivedAt = at("2026-10-12T10:00")),
            MandateRevoked("spotify", "Spotify", LocalDate.of(2026, 10, 12), at("2026-10-12T10:30")),
        )
        val sub = engine.build(events, LocalDate.of(2026, 10, 13)).single()
        assertEquals(SubscriptionStatus.ENDING, sub.status)
        assertEquals(LocalDate.of(2026, 11, 12), sub.activeUntil)
    }

    @Test
    fun `subscribing again after cancelling makes it active`() {
        val again = MandateCreated("spotify", "Spotify", LocalDate.of(2026, 10, 20), money("119"), Frequency.MONTHLY, null, null, receivedAt = at("2026-10-20T09:00"))
        assertEquals(SubscriptionStatus.ACTIVE, engine.build(spotify(cancelledSameDay, again), LocalDate.of(2026, 10, 21)).single().status)
    }

    @Test
    fun `a charge after the cancellation, with no new mandate, is flagged`() {
        val charged = Debit("spotify", "Spotify", LocalDate.of(2026, 11, 12), money("119"), LocalDate.of(2026, 11, 12), viaMandate = true)
        val sub = engine.build(spotify(cancelledSameDay, charged), LocalDate.of(2026, 11, 13)).single()
        assertEquals(LocalDate.of(2026, 11, 12), sub.chargedAfterCancel)
        val resubscribed = MandateCreated("spotify", "Spotify", LocalDate.of(2026, 11, 10), money("119"), Frequency.MONTHLY, null, null)
        assertEquals(null, engine.build(spotify(cancelledSameDay, resubscribed, charged), LocalDate.of(2026, 11, 13)).single().chargedAfterCancel)
    }
}
