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
