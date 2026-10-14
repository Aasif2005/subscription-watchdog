package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.SourcedEvent
import com.aasif.watchdog.core.engine.SubscriptionEngine
import com.aasif.watchdog.core.engine.nameChannelEvents
import com.aasif.watchdog.core.engine.replacementQuestions
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.PaymentEvent.Debit
import com.aasif.watchdog.core.model.PaymentEvent.MandateCreated
import com.aasif.watchdog.core.model.PaymentEvent.MandateRevoked
import com.aasif.watchdog.core.model.PaymentEvent.PreDebitNotice
import com.aasif.watchdog.core.model.SubscriptionStatus
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Netflix ₹499 monthly changed to ₹1,200 yearly, billed through Google Play. Synthetic events. */
class PlanChangeTest {
    private val engine = SubscriptionEngine()
    private fun d(s: String) = LocalDate.parse(s)
    private fun money(v: Int) = Money(BigDecimal(v))
    private fun mandate(amount: Int, on: String, f: Frequency? = null, merchant: String = "googleplay") =
        MandateCreated(merchant, merchant, d(on), money(amount), f, null, null)
    private fun charge(amount: Int, on: String, merchant: String = "googleplay") =
        Debit(merchant, merchant, d(on), money(amount), d(on), viaMandate = true)

    private val monthly = listOf(mandate(499, "2026-08-05", Frequency.MONTHLY), charge(499, "2026-08-05"), charge(499, "2026-09-05"))
    private val yearly = listOf(mandate(1200, "2026-09-20", Frequency.YEARLY), charge(1200, "2026-09-20"))

    @Test
    fun `with the old mandate's revocation text, the old plan simply ends`() {
        val subs = engine.build(monthly + yearly + MandateRevoked("googleplay", "googleplay", d("2026-09-20"), amount = money(499)), d("2026-09-25"))
        assertEquals(listOf(BigDecimal(1200)), subs.filter { it.isLive }.map { it.amount.amount })
        assertTrue(subs.replacementQuestions().isEmpty())
    }

    @Test
    fun `without it, both look live and the user is asked which`() {
        val subs = engine.build(monthly + yearly, d("2026-09-25"))
        assertEquals(2, subs.count { it.isLive })
        val q = subs.replacementQuestions().single()
        assertEquals(BigDecimal(499), q.older.amount.amount)
        assertEquals(BigDecimal(1200), q.newer.amount.amount)
    }

    @Test
    fun `once the old plan's billing date passes with no notice and no charge, it is taken as stopped`() {
        val old = engine.build(monthly + yearly, d("2026-10-09")).single { it.amount == money(499) }
        assertEquals(SubscriptionStatus.LAPSED, old.status)
        assertEquals(d("2026-10-05"), old.missedChargeOn)
    }

    @Test
    fun `two real apps, each charged on time, never lapse`() {
        val other = listOf(mandate(139, "2026-08-10", Frequency.MONTHLY), charge(139, "2026-08-10"), charge(139, "2026-09-10"), charge(139, "2026-10-10"))
        val subs = engine.build(monthly + charge(499, "2026-10-05") + other, d("2026-10-12"))
        assertTrue(subs.all { it.isLive }, subs.toString())
    }

    @Test
    fun `a pre-debit notice keeps a plan whose charge is a little late alive`() {
        val notice = PreDebitNotice("googleplay", "googleplay", d("2026-10-04"), money(499), d("2026-10-06"))
        assertTrue(engine.build(monthly + notice, d("2026-10-09")).single().isLive)
    }

    // Apple Music ₹199 monthly changed to ₹350 yearly, with mail naming the app: one ordinary plan.
    private val oldMandate = MandateCreated("applemusic", "Apple Music", d("2026-10-01"), money(199), Frequency.MONTHLY, null, null, mandateRef = "oldref")
    private val oldCharge = charge(199, "2026-10-01", "applemusic")
    private fun newMandate(on: String) = MandateCreated("applemusic", "Apple Music", d(on), money(350), Frequency.YEARLY, null, null, mandateRef = "newref")
    private fun revoke(on: String, amount: Int? = 199, ref: String? = "oldref") =
        MandateRevoked("applemusic", "Apple Music", d(on), amount = amount?.let(::money), mandateRef = ref)

    @Test
    fun `a named app whose old AutoPay was revoked and a new one created is one plan that remembers what it replaced`() {
        val sub = engine.build(listOf(oldMandate, oldCharge, revoke("2026-10-14"), newMandate("2026-10-14")), d("2026-10-15")).single()
        assertTrue(sub.isLive, sub.toString())
        assertEquals(money(350), sub.amount)
        assertEquals(Frequency.YEARLY, sub.frequency)
        val before = sub.changedFrom!!
        assertEquals(money(199), before.amount)
        assertEquals(Frequency.MONTHLY, before.frequency)
    }

    @Test
    fun `the old AutoPay's revocation arriving after the new mandate does not cancel the new plan`() {
        val sub = engine.build(listOf(oldMandate, oldCharge, newMandate("2026-10-14"), revoke("2026-10-16")), d("2026-10-17")).single()
        assertTrue(sub.isLive, sub.toString())
        assertEquals(money(350), sub.amount)
        assertEquals(money(199), sub.changedFrom?.amount)
    }

    @Test
    fun `a late revocation with no amount is still placed on the old mandate by its number`() {
        val sub = engine.build(listOf(oldMandate, oldCharge, newMandate("2026-10-14"), revoke("2026-10-16", amount = null)), d("2026-10-17")).single()
        assertTrue(sub.isLive, sub.toString())
    }

    @Test
    fun `a revocation of the newest mandate still ends the plan`() {
        val sub = engine.build(listOf(oldMandate, oldCharge, newMandate("2026-10-14"), revoke("2026-10-16", amount = 350, ref = "newref")), d("2026-10-17")).single()
        assertEquals(SubscriptionStatus.ENDING, sub.status, sub.toString())
    }

    @Test
    fun `a charge of the old amount after its revocation is still flagged, a charge of the new one is not`() {
        val base = listOf(oldMandate, oldCharge, newMandate("2026-10-14"), revoke("2026-10-16"))
        val oldAgain = engine.build(base + charge(199, "2026-10-20", "applemusic"), d("2026-10-21")).single()
        assertEquals(d("2026-10-20"), oldAgain.chargedAfterCancel)
        val newCharge = engine.build(base + charge(350, "2026-10-20", "applemusic"), d("2026-10-21")).single()
        assertEquals(null, newCharge.chargedAfterCancel)
    }

    @Test
    fun `on an unnamed channel a revoked plan and a new one stay two plans, with no guessed link`() {
        val subs = engine.build(
            listOf(mandate(199, "2026-10-01", Frequency.MONTHLY), charge(199, "2026-10-01"), MandateRevoked("googleplay", "googleplay", d("2026-10-14"), amount = money(199)), mandate(350, "2026-10-14", Frequency.YEARLY)),
            d("2026-10-15"),
        )
        assertEquals(2, subs.size)
        assertEquals(listOf(BigDecimal(350)), subs.filter { it.isLive }.map { it.amount.amount })
        assertTrue(subs.all { it.changedFrom == null })
        assertTrue(subs.replacementQuestions().isEmpty())
    }

    @Test
    fun `an ordinary price history is not a plan change when only one amount was ever set up`() {
        val sub = engine.build(listOf(oldMandate, oldCharge), d("2026-10-05")).single()
        assertEquals(null, sub.changedFrom)
    }

    @Test
    fun `when mail names both mandates as Netflix, the newer simply replaces the older`() {
        val bank: List<PaymentEvent> = listOf(mandate(499, "2026-08-05"), mandate(1200, "2026-09-20"))
        val receipts: List<PaymentEvent> = listOf(
            MandateCreated("netflix", "Netflix", d("2026-08-05"), money(499), Frequency.MONTHLY, null, null, fromMail = true),
            MandateCreated("netflix", "Netflix", d("2026-09-20"), money(1200), Frequency.YEARLY, null, null, fromMail = true),
        )
        val named = nameChannelEvents(bank.map { SourcedEvent(it, false) } + receipts.map { SourcedEvent(it, true) }, setOf("googleplay"))
        val subs = engine.build(named, d("2026-09-25"))
        val netflix = subs.single()
        assertEquals("netflix", netflix.merchantId)
        assertEquals(money(1200), netflix.amount)
        assertEquals(Frequency.YEARLY, netflix.frequency)
    }
}
