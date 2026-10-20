package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.isLikelyDuplicateOf
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventDedupTest {
    private val sep5 = LocalDate.of(2026, 9, 5)

    private fun money(v: String) = Money(BigDecimal(v))

    private fun debit(
        merchant: String = "netflix",
        amount: String = "649",
        date: LocalDate = sep5,
        viaMandate: Boolean = true,
    ) = PaymentEvent.Debit("$merchant", "Netflix", date, money(amount), date, viaMandate)

    // ---- Debits ---------------------------------------------------------------------------------

    @Test
    fun `the same charge seen by SMS and by e-mail is one event`() {
        assertTrue(debit().isLikelyDuplicateOf(debit()))
    }

    @Test
    fun `amount scale does not matter, since sources write it differently`() {
        val fromSms = debit(amount = "649")
        val fromEmail = debit(amount = "649.00")
        assertTrue(fromSms.isLikelyDuplicateOf(fromEmail), "649 vs 649.00 should collapse")
    }

    @Test
    fun `the source flag is ignored when deciding sameness`() {
        assertTrue(debit(viaMandate = true).isLikelyDuplicateOf(debit(viaMandate = false)))
    }

    @Test
    fun `two genuinely different charges in one month are kept`() {
        assertFalse(debit(date = sep5).isLikelyDuplicateOf(debit(date = sep5.plusDays(11))))
        assertFalse(debit(amount = "649").isLikelyDuplicateOf(debit(amount = "199")))
    }

    @Test
    fun `different merchants never collapse`() {
        assertFalse(debit(merchant = "netflix").isLikelyDuplicateOf(debit(merchant = "spotify")))
    }

    // ---- Other event types ----------------------------------------------------------------------

    @Test
    fun `pre-debit notices collapse on the announced date`() {
        val a = PaymentEvent.PreDebitNotice("netflix", "Netflix", sep5, money("649"), sep5.plusDays(1))
        val b = PaymentEvent.PreDebitNotice("netflix", "Netflix", sep5.plusDays(1), money("649.00"), sep5.plusDays(1))
        assertTrue(a.isLikelyDuplicateOf(b), "same announced debit, seen a day apart")
    }

    @Test
    fun `mandates collapse on start date, amount and frequency`() {
        val a = PaymentEvent.MandateCreated("netflix", "Netflix", sep5, money("649"), Frequency.MONTHLY, sep5, null)
        val b = PaymentEvent.MandateCreated("netflix", "Netflix", sep5, money("649.00"), Frequency.MONTHLY, sep5, null)
        assertTrue(a.isLikelyDuplicateOf(b))

        val yearly = PaymentEvent.MandateCreated("netflix", "Netflix", sep5, money("649"), Frequency.YEARLY, sep5, null)
        assertFalse(a.isLikelyDuplicateOf(yearly), "a monthly and a yearly plan are different subscriptions")
    }

    @Test
    fun `dateless mandates for one merchant still collapse`() {
        val a = PaymentEvent.MandateCreated("hotstar", "JioHotstar", sep5, null, null, null, null)
        val b = PaymentEvent.MandateCreated("hotstar", "JioHotstar", sep5, null, null, null, null)
        assertTrue(a.isLikelyDuplicateOf(b), "this is the case a SQL unique index would miss")
    }

    @Test
    fun `revocations collapse when the confirmations arrive a day apart`() {
        val sms = PaymentEvent.MandateRevoked("netflix", "Netflix", sep5)
        val email = PaymentEvent.MandateRevoked("netflix", "Netflix", sep5.plusDays(1))
        assertTrue(sms.isLikelyDuplicateOf(email))
        assertFalse(sms.isLikelyDuplicateOf(PaymentEvent.MandateRevoked("netflix", "Netflix", sep5.plusDays(9))))
    }

    @Test
    fun `different event types are never duplicates of each other`() {
        val notice = PaymentEvent.PreDebitNotice("netflix", "Netflix", sep5, money("649"), sep5)
        assertFalse(debit().isLikelyDuplicateOf(notice))
        assertFalse(notice.isLikelyDuplicateOf(debit()))
    }

    @Test
    fun `two revocations of different amounts on one morning are two mandates`() {
        val a = PaymentEvent.MandateRevoked("googleplay", "Google Play", sep5, amount = money("139"))
        val b = PaymentEvent.MandateRevoked("googleplay", "Google Play", sep5, amount = money("149"))
        assertFalse(a.isLikelyDuplicateOf(b))
        assertTrue(a.isLikelyDuplicateOf(PaymentEvent.MandateRevoked("googleplay", "Google Play", sep5, amount = money("139.00"))))
        // One side not stating it is still the same revocation reported twice.
        assertTrue(a.isLikelyDuplicateOf(PaymentEvent.MandateRevoked("googleplay", "Google Play", sep5.plusDays(1))))
    }
}
