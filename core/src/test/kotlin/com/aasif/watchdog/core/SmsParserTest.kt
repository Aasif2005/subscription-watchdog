package com.aasif.watchdog.core

import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
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

class SmsParserTest {
    private val parser = SmsParser()
    private val received = LocalDateTime.of(2026, 9, 5, 10, 30)

    private fun parse(body: String, sender: String = "VM-HDFCBK-S", at: LocalDateTime = received) =
        parser.parse(Sms(sender, body, at))

    private fun date(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d)

    // ---- Mandate registration ------------------------------------------------------------------

    @Test
    fun `HDFC UPI mandate with start date and validity`() {
        val e = assertIs<PaymentEvent.MandateCreated>(parse(
            "Your UPI-Mandate for Rs.649.00 is successfully created towards NETFLIX from A/c **1234. " +
                "Frequency: Monthly, Start date: 05/09/26. Validity till: 05/09/31 -HDFC Bank"
        ))
        assertEquals("netflix", e.merchantId)
        assertEquals(Money(BigDecimal("649.00")), e.amount)
        assertEquals(Frequency.MONTHLY, e.frequency)
        assertEquals(date(2026, 9, 5), e.startDate)
        assertEquals(date(2031, 9, 5), e.validTill)
    }

    @Test
    fun `SBI AutoPay registration with first debit date`() {
        val e = assertIs<PaymentEvent.MandateCreated>(parse(
            "Dear Customer, AutoPay mandate of Rs 199 has been registered for SPOTIFY INDIA with frequency MONTHLY. " +
                "First debit on 12-Sep-2026. UMN: a1b2c3@sbi. -SBI",
            sender = "AX-SBIINB",
        ))
        assertEquals("spotify", e.merchantId)
        assertEquals(Money(BigDecimal("199")), e.amount)
        assertEquals(date(2026, 9, 12), e.startDate)
        assertNull(e.validTill)
    }

    @Test
    fun `trial mandate takes the real price, not the verification rupee`() {
        val e = assertIs<PaymentEvent.MandateCreated>(parse(
            "UPI mandate created successfully. Rs 1.00 debited for verification towards YouTube. " +
                "Recurring payment of Rs 149 will start from 20-10-2026. Frequency: Monthly -Axis Bank"
        ))
        assertEquals("youtube", e.merchantId)
        assertEquals(Money(BigDecimal("149")), e.amount)
        assertEquals(date(2026, 10, 20), e.startDate)
    }

    @Test
    fun `card e-mandate with as-presented frequency`() {
        val e = assertIs<PaymentEvent.MandateCreated>(parse(
            "E-mandate registered on your ICICI Bank Credit Card XX5678 for Google Play for max Rs 2000.00, " +
                "frequency: As Presented. Valid upto 30-Aug-2031.",
            sender = "JD-ICICIT",
        ))
        assertEquals("googleplay", e.merchantId)
        assertEquals(Frequency.AS_PRESENTED, e.frequency)
        assertEquals(date(2031, 8, 30), e.validTill)
        assertNull(e.startDate)
    }

    // ---- Pre-debit notices ---------------------------------------------------------------------

    @Test
    fun `pre-debit notice gives the exact date`() {
        val e = assertIs<PaymentEvent.PreDebitNotice>(parse(
            "Rs.199.00 will be debited from your A/c XX1234 on 12-10-2026 towards Spotify AutoPay mandate. " +
                "To cancel, visit the PhonePe app. -ICICI Bank",
            at = LocalDateTime.of(2026, 10, 11, 9, 0),
        ))
        assertEquals("spotify", e.merchantId)
        assertEquals(date(2026, 10, 12), e.debitDate)
        assertEquals(Money(BigDecimal("199.00")), e.amount)
    }

    @Test
    fun `pre-debit notice saying tomorrow`() {
        val e = assertIs<PaymentEvent.PreDebitNotice>(parse(
            "Reminder: Your AutoPay of Rs 1499 for YouTube Premium will be debited tomorrow from A/c XX9876. -Kotak"
        ))
        assertEquals(date(2026, 9, 6), e.debitDate)
    }

    @Test
    fun `pre-debit notice without a year across new year`() {
        val e = assertIs<PaymentEvent.PreDebitNotice>(parse(
            "Rs 649 will be debited on 02 Jan towards Netflix via UPI AutoPay.",
            at = LocalDateTime.of(2026, 12, 31, 18, 0),
        ))
        assertEquals(date(2027, 1, 2), e.debitDate)
    }

    // ---- Debits --------------------------------------------------------------------------------

    @Test
    fun `HDFC multi-line UPI debit`() {
        val e = assertIs<PaymentEvent.Debit>(parse(
            "Sent Rs.199.00\nFrom HDFC Bank A/C *1234\nTo Spotify\nOn 05/09/26\nRef 623512345678\n" +
                "Not You?\nCall 18002586161/SMS BLOCK UPI to 7308080808"
        ))
        assertEquals("spotify", e.merchantId)
        assertEquals(Money(BigDecimal("199.00")), e.amount)
        assertEquals(date(2026, 9, 5), e.date)
    }

    @Test
    fun `Axis card spend ignores the available limit`() {
        val e = assertIs<PaymentEvent.Debit>(parse(
            "Spent INR 649.00 Axis Bank Card no. XX4321 05-09-26 10:12:44 IST NETFLIX.COM Avl Limit: INR 45,000.00 " +
                "Not you? SMS BLOCK 4321 to 919951860002",
            sender = "BP-AXISBK",
        ))
        assertEquals("netflix", e.merchantId)
        assertEquals(Money(BigDecimal("649.00")), e.amount)
        assertEquals(date(2026, 9, 5), e.date)
    }

    @Test
    fun `Kotak AutoPay debit is flagged as mandate`() {
        val e = assertIs<PaymentEvent.Debit>(parse(
            "Rs 1499.00 debited from Kotak Bank a/c XX9876 on 01-Sep-2026 to YOUTUBE PREMIUM via UPI AutoPay. " +
                "UPI Ref 624412341234. Not you? Call 18602662666"
        ))
        assertEquals("youtube", e.merchantId)
        assertTrue(e.viaMandate)
        assertEquals(date(2026, 9, 1), e.date)
    }

    @Test
    fun `ICICI style payee before credited`() {
        val e = assertIs<PaymentEvent.Debit>(parse(
            "ICICI Bank Acct XX123 debited for Rs 350.00 on 01-Sep-26; SHREE FITNESS STUDIO credited. " +
                "UPI:623412345678. Call 18002662 for dispute."
        ))
        assertEquals("u:shreefitnessstudio", e.merchantId)
        assertEquals("Shree Fitness Studio", e.merchantName)
    }

    @Test
    fun `unknown merchant from VPA`() {
        val e = assertIs<PaymentEvent.Debit>(parse(
            "Rs.299 debited from A/c XX1234 on 03-Aug-26 to VPA fitbox.gym@okaxis (UPI Ref 1234). -SBI"
        ))
        assertEquals("u:fitboxgym", e.merchantId)
        assertEquals("Fitbox Gym", e.merchantName)
    }

    @Test
    fun `gateway-wrapped VPA resolves to the real merchant`() {
        val e = assertIs<PaymentEvent.Debit>(parse(
            "Rs 119.00 debited from A/c XX1234 on 04-09-26 to razorpay.jiosaavn@icici. Ref 1234"
        ))
        assertEquals("jiosaavn", e.merchantId)
    }

    @Test
    fun `fuzzy match catches truncated names`() {
        val e = assertIs<PaymentEvent.Debit>(parse("Rs 649 debited from A/c XX1234 on 05-09-26 to NETFLX ENT."))
        assertEquals("netflix", e.merchantId)
    }

    // ---- Revocations and noise -----------------------------------------------------------------

    @Test
    fun `mandate revoked`() {
        val e = parse("Your UPI AutoPay mandate for Netflix has been revoked successfully. -Paytm Payments Bank")
        assertEquals("netflix", assertIs<PaymentEvent.MandateRevoked>(e).merchantId)
    }

    @Test
    fun `ignores OTPs, failures, credits and personal numbers`() {
        assertNull(parse("123456 is your OTP for mandate registration of Rs 649 towards Netflix. Do not share."))
        assertNull(parse("Your AutoPay payment of Rs 649 to Netflix failed due to insufficient balance."))
        assertNull(parse("Rs 5000 credited to your A/c XX1234 by NEFT from RAHUL. -HDFC"))
        assertNull(parse("Rs 649 debited to Netflix on 05-09-26", sender = "+919876543210"))
    }

    // ---- AutoPay recognition ---------------------------------------------------------------------

    /**
     * The signal that was entirely missing: a bank's debit SMS carries the mandate's UMN even when
     * it never spells out "AutoPay" anywhere, which is why 0 of 731 stored debits had
     * `viaMandate = true` before this.
     */
    @Test
    fun `a debit carrying a UMN is recognised as AutoPay even without the word`() {
        val e = assertIs<PaymentEvent.Debit>(parse(
            "Rs 149.00 debited from A/c XX1234 on 05-09-26 to NETFLIX. UMN: abc123456@okhdfcbank Ref 998877."
        ))
        assertTrue(e.viaMandate)
    }

    @Test
    fun `a razorpay-wrapped VPA resolves to the merchant it gateways for`() {
        val e = assertIs<PaymentEvent.Debit>(parse(
            "Rs 649.00 debited from A/c XX1234 on 05-09-26 to razorpay.netflix@icici. Ref 1234"
        ))
        assertEquals("netflix", e.merchantId)
    }

    @Test
    fun `money sent to a phone-number VPA is flagged as peer-to-peer`() {
        val e = assertIs<PaymentEvent.Debit>(parse(
            "Rs 500.00 debited from A/c XX1234 on 05-09-26 to 9876543210@ybl. Ref 1234"
        ))
        assertTrue(e.peerToPeer)
    }
}
