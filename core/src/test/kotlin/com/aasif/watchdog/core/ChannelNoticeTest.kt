package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.SourcedEvent
import com.aasif.watchdog.core.engine.SubscriptionEngine
import com.aasif.watchdog.core.engine.heldForMail
import com.aasif.watchdog.core.engine.nameChannelEvents
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.PaymentEvent.MandateCreated
import com.aasif.watchdog.core.model.PaymentEvent.MandateRevoked
import com.aasif.watchdog.core.model.PaymentEvent.PreDebitNotice
import com.aasif.watchdog.core.model.RenewalSource
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.core.parser.SmsParser
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Claude Pro bought on Google Play (18 Sep 2026). Play's page said the next payment was 18 Oct, but on 17 Oct IOB and GPay said the
 * debit would be on 19 Oct. IOB's text prints the amount with no currency and names only "Google Play", so it used to become a separate
 * anonymous "Google Play subscription renews tomorrow" while Claude Pro still said "renews today".
 */
class ChannelNoticeTest {
    private val sms = SmsParser()
    private val engine = SubscriptionEngine()
    private val play = setOf("googleplay")
    private fun money(v: String) = Money(BigDecimal(v))
    private val sep18 = LocalDate.of(2026, 9, 18)
    private val oct18 = LocalDate.of(2026, 10, 18)

    private val iobNotice = Sms(
        "BT-IOBCHN-S",
        "UPI AutoPay Alert: 1999.00 will be deducted from IOB account XXXXX17 for Google Play on 2026-10-19 -IOB",
        LocalDateTime.of(2026, 10, 17, 0, 46),
    )

    private fun bankMandate(app: String = "googleplay", amount: String = "1999.00", on: LocalDate = sep18, ref: String = "m1") =
        MandateCreated(app, "Google Play", on, money(amount), Frequency.AS_PRESENTED, on, null, receivedAt = on.atTime(11, 30, 37), mandateRef = ref)

    /** What a Play receipt mail gives: the app, its amount, monthly, renewing a month after purchase. */
    private fun receipt(app: String, amount: String = "1999.00", on: LocalDate = sep18) =
        MandateCreated(app, app, on, money(amount), Frequency.MONTHLY, on, null, autoRenews = true, receivedAt = on.atTime(11, 30, 51), fromMail = true, planName = "Claude Pro")

    private fun build(vararg events: SourcedEvent, today: LocalDate = oct18) =
        engine.build(nameChannelEvents(events.toList(), play), today)

    @Test
    fun `IOB's notice without a currency still has its amount and date`() {
        val e = assertIs<PreDebitNotice>(sms.parse(iobNotice))
        assertEquals("googleplay", e.merchantId)
        assertEquals(money("1999.00"), e.amount)
        assertEquals(LocalDate.of(2026, 10, 19), e.debitDate)
    }

    @Test
    fun `the bank's notice moves Claude Pro to 19 Oct and no anonymous Google Play plan appears`() {
        val subs = build(
            SourcedEvent(bankMandate(), false),
            SourcedEvent(receipt("claude"), true),
            SourcedEvent(sms.parse(iobNotice)!!, false),
        )
        val sub = subs.single()
        assertEquals("claude", sub.merchantId)
        assertEquals("Claude Pro", sub.name)
        assertEquals(LocalDate.of(2026, 10, 19), sub.nextRenewal)
        assertEquals(RenewalSource.PRE_DEBIT_NOTICE, sub.renewalSource)
        assertEquals(oct18, sub.movedFrom)
    }

    @Test
    fun `a notice stored before amounts were read is still filed under the only app bought through Play`() {
        // The phone's stored event: amount missing.
        val stored = PreDebitNotice("googleplay", "Google Play", LocalDate.of(2026, 10, 17), null, LocalDate.of(2026, 10, 19))
        val sub = build(SourcedEvent(bankMandate(), false), SourcedEvent(receipt("claude"), true), SourcedEvent(stored, false)).single()
        assertEquals("claude", sub.merchantId)
        assertEquals(LocalDate.of(2026, 10, 19), sub.nextRenewal)
    }

    @Test
    fun `with two live Play apps charging the same week an amount-less notice is not guessed and makes no plan of its own`() {
        val sep20 = LocalDate.of(2026, 9, 20)
        val stored = PreDebitNotice("googleplay", "Google Play", LocalDate.of(2026, 10, 17), null, LocalDate.of(2026, 10, 19))
        val subs = build(
            SourcedEvent(bankMandate(), false), SourcedEvent(receipt("claude"), true),
            SourcedEvent(bankMandate(amount = "399.00", on = sep20, ref = "m2"), false), SourcedEvent(receipt("chatgpt", "399.00", sep20), true),
            SourcedEvent(stored, false),
        )
        assertTrue(subs.none { it.merchantId == "googleplay" }, subs.map { it.key }.toString())
        assertEquals(oct18, subs.single { it.merchantId == "claude" }.nextRenewal)
    }

    @Test
    fun `an amount-less notice goes to the Play app that is due around its date`() {
        val oct1 = LocalDate.of(2026, 10, 1)
        val stored = PreDebitNotice("googleplay", "Google Play", LocalDate.of(2026, 10, 17), null, LocalDate.of(2026, 10, 19))
        val subs = build(
            SourcedEvent(bankMandate(), false), SourcedEvent(receipt("claude"), true),
            SourcedEvent(bankMandate(amount = "399.00", on = oct1, ref = "m2"), false), SourcedEvent(receipt("chatgpt", "399.00", oct1), true),
            SourcedEvent(stored, false),
        )
        assertEquals(LocalDate.of(2026, 10, 19), subs.single { it.merchantId == "claude" }.nextRenewal)
        assertTrue(subs.none { it.merchantId == "googleplay" })
    }

    @Test
    fun `two Play apps at the same price are told apart by the notice's date`() {
        val oct1 = LocalDate.of(2026, 10, 1)
        val subs = build(
            SourcedEvent(bankMandate(), false), SourcedEvent(receipt("claude"), true),
            SourcedEvent(bankMandate(on = oct1, ref = "m2"), false), SourcedEvent(receipt("chatgpt", "1999.00", oct1), true),
            SourcedEvent(sms.parse(iobNotice)!!, false),
        )
        assertEquals(LocalDate.of(2026, 10, 19), subs.single { it.merchantId == "claude" }.nextRenewal)
        assertEquals(RenewalSource.PRE_DEBIT_NOTICE, subs.single { it.merchantId == "claude" }.renewalSource)
        assertTrue(subs.none { it.merchantId == "googleplay" })
    }

    @Test
    fun `a notification that names the app goes to that app`() {
        val gpay = Sms("com.google.android.apps.nbu.paisa.user", "UPI AutoPay Alert: Rs.1999.00 will be debited from your account for ANTHROPIC on 2026-10-19", LocalDateTime.of(2026, 10, 17, 9, 0))
        val e = assertIs<PreDebitNotice>(sms.parse(gpay))
        assertEquals("claude", e.merchantId)
    }

    @Test
    fun `with two live Play apps the notice's amount picks the right one`() {
        val oct1 = LocalDate.of(2026, 10, 1)
        val subs = build(
            SourcedEvent(bankMandate(), false), SourcedEvent(receipt("claude"), true),
            SourcedEvent(bankMandate(amount = "399.00", on = oct1, ref = "m2"), false), SourcedEvent(receipt("chatgpt", "399.00", oct1), true),
            SourcedEvent(sms.parse(iobNotice)!!, false),
        )
        assertEquals(LocalDate.of(2026, 10, 19), subs.single { it.merchantId == "claude" }.nextRenewal)
        assertTrue(subs.none { it.merchantId == "googleplay" })
    }

    @Test
    fun `an amount that fits no app is not attached to one`() {
        val other = iobNotice.copy(body = iobNotice.body.replace("1999.00", "149.00"))
        val subs = build(SourcedEvent(bankMandate(), false), SourcedEvent(receipt("claude"), true), SourcedEvent(sms.parse(other)!!, false))
        val claude = subs.single { it.merchantId == "claude" }
        assertEquals(oct18, claude.nextRenewal)
        assertNull(claude.movedFrom)
    }

    @Test
    fun `an app whose AutoPay was revoked on a later day is not a candidate`() {
        val revoked = MandateRevoked("googleplay", "Google Play", LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 2).atTime(9, 0), amount = money("1999.00"), mandateRef = "m1")
        val stored = PreDebitNotice("googleplay", "Google Play", LocalDate.of(2026, 10, 17), null, LocalDate.of(2026, 10, 19))
        val named = nameChannelEvents(
            listOf(SourcedEvent(bankMandate(), false), SourcedEvent(receipt("claude"), true), SourcedEvent(revoked, false), SourcedEvent(stored, false)),
            play,
        )
        assertEquals("googleplay", named.filterIsInstance<PreDebitNotice>().single().merchantId)
    }

    @Test
    fun `a bare number is only read as an amount in a pre-debit sentence`() {
        val debit = Sms("AD-IOBCHN-S", "Your a/c XXXXX17 debited for payee KALIDAS for Rs. 20.00 on 2026-10-11, ref 665047659950.", LocalDateTime.of(2026, 10, 11, 8, 21))
        assertEquals(money("20.00"), (sms.parse(debit) as? PaymentEvent.Debit)?.amount)
    }

    @Test
    fun `a Google Play plan waits while mail is still being read and shows once it is done`() {
        // Bank texts read before the receipt mail: only the channel is known.
        val early = build(SourcedEvent(bankMandate(), false), SourcedEvent(sms.parse(iobNotice)!!, false))
        assertTrue(early.any { it.sharedBilling }, early.map { it.key }.toString())
        assertTrue(early.heldForMail(catchingUp = true).none { it.sharedBilling })
        // Import finished with no receipt naming it: shown as the channel.
        assertEquals(early, early.heldForMail(catchingUp = false))
        // Import finished and the receipt names it: Claude Pro, nothing held.
        val named = build(SourcedEvent(bankMandate(), false), SourcedEvent(receipt("claude"), true), SourcedEvent(sms.parse(iobNotice)!!, false))
        assertEquals(listOf("claude"), named.heldForMail(catchingUp = true).map { it.merchantId })
    }

    private val iobDebit = Sms(
        "JX-IOBCHN-S",
        "Your a/c XXXXX17 debited for payee Google Play for Rs. 1999.00 on 2026-10-19, ref 562864622926.If not you, report to your bank immediately-IOB.",
        LocalDateTime.of(2026, 10, 19, 0, 46),
    )
    private val oct19 = LocalDate.of(2026, 10, 19)

    @Test
    fun `the bank's debit text moves Claude Pro past the day it was paid and leaves no Google Play plan`() {
        val subs = build(
            SourcedEvent(bankMandate(), false), SourcedEvent(receipt("claude"), true),
            SourcedEvent(sms.parse(iobNotice)!!, false), SourcedEvent(sms.parse(iobDebit)!!, false),
            today = oct19,
        )
        val claude = subs.single()
        assertEquals("claude", claude.merchantId)
        assertEquals(LocalDate.of(2026, 11, 19), claude.nextRenewal)
        assertTrue(claude.charges.any { it.date == oct19 && it.amount == money("1999.00") }, claude.charges.toString())
    }

    @Test
    fun `a debit of another amount stays on the channel`() {
        val other = iobDebit.copy(body = iobDebit.body.replace("1999.00", "149.00"))
        val named = nameChannelEvents(
            listOf(SourcedEvent(bankMandate(), false), SourcedEvent(receipt("claude"), true), SourcedEvent(sms.parse(other)!!, false)),
            play,
        )
        assertEquals("googleplay", named.filterIsInstance<PaymentEvent.Debit>().single().merchantId)
    }

    @Test
    fun `a debit of the same price far from the app's schedule is a one-off, not its charge`() {
        val oneOff = iobDebit.copy(body = iobDebit.body.replace("2026-10-19", "2026-10-07"))
        val named = nameChannelEvents(
            listOf(SourcedEvent(bankMandate(), false), SourcedEvent(receipt("claude"), true), SourcedEvent(sms.parse(oneOff)!!, false)),
            play,
        )
        assertEquals("googleplay", named.filterIsInstance<PaymentEvent.Debit>().single().merchantId)
    }

    @Test
    fun `two Play apps at one price with no date to tell them apart leave the debit unnamed`() {
        val oct1 = LocalDate.of(2026, 10, 1)
        val named = nameChannelEvents(
            listOf(
                SourcedEvent(bankMandate(), false), SourcedEvent(receipt("claude"), true),
                SourcedEvent(bankMandate(on = oct1, ref = "m2"), false), SourcedEvent(receipt("chatgpt", "1999.00", oct1), true),
                SourcedEvent(sms.parse(iobDebit.copy(body = iobDebit.body.replace("2026-10-19", "2026-10-10")))!!, false),
            ),
            play,
        )
        assertEquals("googleplay", named.filterIsInstance<PaymentEvent.Debit>().single().merchantId)
    }
}
