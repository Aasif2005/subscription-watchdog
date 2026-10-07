package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.worthKeepingFromSms
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.core.parser.SmsParser
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How AutoPay alerts read when they arrive as app notifications: the listener joins the title and
 * the body with a space and uses the package name as the sender (`PaymentNotificationListener`).
 *
 * The wording below is **synthetic**. It follows the shape of the RBI-mandated bank texts in
 * [SmsParserTest] with a notification-style title in front; no real Google Pay, PhonePe or Paytm
 * notification text has been captured yet, so a pass here shows the parser copes with that shape
 * and does not prove any app actually phrases its alerts this way.
 */
class NotificationParsingTest {
    private val parser = SmsParser()
    private val received = LocalDateTime.of(2026, 9, 5, 10, 30)

    private fun notification(packageName: String, title: String, text: String) =
        parser.parse(Sms(sender = packageName, body = "$title $text", receivedAt = received))

    @Test
    fun `an upcoming AutoPay debit with a title in front reads as a pre-debit notice`() {
        val e = assertIs<PaymentEvent.PreDebitNotice>(
            notification(
                "com.phonepe.app", "Upcoming AutoPay",
                "Rs 649.00 will be debited on 06-Sep-2026 towards NETFLIX under your UPI AutoPay mandate. UMN: abc123@ybl",
            ),
        )
        assertEquals("netflix", e.merchantId)
        assertEquals(Money(BigDecimal("649.00")), e.amount)
        assertEquals(LocalDate.of(2026, 9, 6), e.debitDate)
    }

    @Test
    fun `a mandate created notification keeps the merchant, amount and frequency`() {
        val e = assertIs<PaymentEvent.MandateCreated>(
            notification(
                "com.google.android.apps.nbu.paisa.user", "AutoPay set up",
                "UPI mandate of Rs 199 created successfully for SPOTIFY INDIA. Frequency: Monthly. First debit on 12-09-2026.",
            ),
        )
        assertEquals("spotify", e.merchantId)
        assertEquals(Money(BigDecimal("199")), e.amount)
    }

    @Test
    fun `a revoked mandate notification is a revocation`() {
        val e = assertIs<PaymentEvent.MandateRevoked>(
            notification(
                "net.one97.paytm", "AutoPay cancelled",
                "Your UPI AutoPay mandate for NETFLIX has been revoked successfully.",
            ),
        )
        assertEquals("netflix", e.merchantId)
    }

    @Test
    fun `an ordinary payment to a person parses, but is not worth keeping`() {
        val e = notification("com.phonepe.app", "Payment successful", "You paid Rs 250 to Ravi Kumar. UPI Ref 123456789012")
        // Stored, it would put a private person's name and a one-off amount in the database and
        // could pass for a monthly pattern (rent); the same filter bank SMS go through drops it.
        assertIs<PaymentEvent.Debit>(e)
        assertFalse(e.worthKeepingFromSms())
    }

    @Test
    fun `an AutoPay debit notification is kept even for a merchant the catalog does not know`() {
        val e = notification(
            "com.phonepe.app", "Payment successful",
            "Rs 899 paid to Acme Learning via UPI AutoPay mandate. UMN: x1y2z3@ybl",
        )
        assertIs<PaymentEvent.Debit>(e)
        assertTrue(e.viaMandate)
        assertTrue(e.worthKeepingFromSms())
    }

    @Test
    fun `a cashback notification is ignored`() {
        assertNull(notification("com.phonepe.app", "You won a reward", "Get 10% cashback on your next recharge. Offer valid till 30 Sep."))
    }
}
