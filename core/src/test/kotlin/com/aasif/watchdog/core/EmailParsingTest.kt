package com.aasif.watchdog.core

import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.Sms
import com.aasif.watchdog.core.parser.HtmlText
import com.aasif.watchdog.core.parser.SmsClassifier
import com.aasif.watchdog.core.parser.SmsParser
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Gmail path reuses the SMS rules, so the fixtures here are real-shaped HTML receipts. */
class EmailParsingTest {
    private val parser = SmsParser()
    private val received = LocalDateTime.of(2026, 9, 5, 10, 30)

    private fun parseEmail(sender: String, html: String) =
        parser.parse(Sms(sender, HtmlText.toPlainText(html), received))

    private val netflixReceipt = """
        <html><head><style>.wrap{color:#221f1f}</style></head><body>
        <table><tr><td>Netflix</td></tr>
        <tr><td>Your payment of &#8377;649.00 was successful.</td></tr>
        <tr><td>Charged on 05/09/26</td></tr></table>
        <p>Manage your subscription</p><a href="https://www.netflix.com/u">Unsubscribe</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `an HTML receipt with no matching SMS becomes a debit`() {
        val e = assertIs<PaymentEvent.Debit>(parseEmail("billing@netflix.com", netflixReceipt))
        assertEquals("netflix", e.merchantId)
        assertEquals(Money(BigDecimal("649.00")), e.amount)
        assertEquals(LocalDate.of(2026, 9, 5), e.date)
        assertTrue(e.viaMandate, "the receipt mentions a subscription")
    }

    @Test
    fun `without stripping, the entity-encoded amount is invisible and nothing is detected`() {
        assertNull(
            parser.parse(Sms("billing@netflix.com", netflixReceipt, received)),
            "raw HTML must not parse — this is why HtmlText runs first",
        )
    }

    @Test
    fun `a card renewal notice becomes a pre-debit notice with the announced date`() {
        val e = assertIs<PaymentEvent.PreDebitNotice>(parseEmail(
            "no-reply@spotify.com",
            """
            <div>Hi, your <b>Spotify</b> Premium plan renews on 12-10-2026.<br>
            &#8377;119 will be debited from your HDFC Bank card ending 1234.</div>
            <div style="font-size:9px">You get this because you subscribed. <a>Unsubscribe</a></div>
            """.trimIndent(),
        ))
        assertEquals("spotify", e.merchantId)
        assertEquals(Money(BigDecimal("119")), e.amount)
        assertEquals(LocalDate.of(2026, 10, 12), e.debitDate)
    }

    @Test
    fun `marketing mail is caught by the existing promotional filter`() {
        val body = HtmlText.toPlainText(
            "<p>Get &#8377;50 cashback when you set up AutoPay for Netflix! Limited time offer.</p>"
        )
        assertTrue(SmsClassifier.isPromotional(body), body)
    }

    @Test
    fun `an ordinary newsletter mentioning a merchant is not a payment`() {
        assertNull(parseEmail(
            "info@netflix.com",
            "<p>Your weekly Netflix picks are here. New arrivals and trending shows this week.</p>",
        ))
    }

    @Test
    fun `unsubscribe footers do not turn a newsletter into a mandate`() {
        val e = parseEmail(
            "news@spotify.com",
            """
            <p>New releases from artists you follow.</p>
            <footer>You received this email because you subscribed to Spotify updates.
            Manage preferences or unsubscribe at any time.</footer>
            """.trimIndent(),
        )
        assertNull(e, "no amount and no payment verb, so nothing should be stored")
    }

    // ---- The stricter reading mail gets, see SmsParser's `fromEmail` --------------------------

    private val mailParser = SmsParser(fromEmail = true)

    private fun parseMail(sender: String, html: String) =
        mailParser.parse(Sms(sender, HtmlText.toPlainText(html), received))

    /**
     * The case this rule exists for. A bank's own transaction alert is a spending notification,
     * not a subscription, but its footer put a noun phrase after a cue word and 140 of these
     * arrived as a recurring charge called "Idfcfirst Bank Instead".
     */
    @Test
    fun `a bank transaction alert is not a subscription`() {
        assertNull(parseMail(
            "transaction.alerts@examplebank.com",
            """
            <p>Dear Aariz, Rs 700.00 has been debited from your account XXXXXX1234
            on 27-09-2026. Available balance Rs 5,000.00.</p>
            <footer>Not you? Please report this if you have any concerns.
            Switch to Example Bank instead and earn interest credited every month.</footer>
            """.trimIndent(),
        ))
    }

    @Test
    fun `a receipt is filed under the service that sent it, not a phrase from its footer`() {
        val e = assertIs<PaymentEvent.Debit>(parseMail(
            "billing-noreply@linkedin.com",
            """
            <p>Your payment of &#8377;7,499.00 was received on 28-08-2026.</p>
            <footer>Thanks for your Premium membership. Write to Customer Care for help.</footer>
            """.trimIndent(),
        ))
        assertEquals("linkedin", e.merchantId, "the sender names the service; the body does not")
        assertEquals(Money(BigDecimal("7499.00")), e.amount)
    }

    @Test
    fun `a service the catalog does not know is named after its sending domain`() {
        val e = assertIs<PaymentEvent.Debit>(parseMail(
            "no-reply@mail.acmeanalytics.io",
            """
            <p>Your subscription renewed. &#8377;499.00 charged on 01-09-2026.</p>
            <footer>Manage your plan or talk to Customer Care.</footer>
            """.trimIndent(),
        ))
        assertEquals("u:acmeanalytics", e.merchantId, "the domain names it, not the footer")
        assertEquals(Money(BigDecimal("499.00")), e.amount)
    }

    @Test
    fun `a country-coded domain is read past its public suffix`() {
        val e = assertIs<PaymentEvent.Debit>(parseMail(
            "billing@notices.examplegym.co.in",
            "<p>Your membership subscription renewed: &#8377;1,200.00 charged on 01-09-2026.</p>",
        ))
        assertEquals("u:examplegym", e.merchantId)
    }

    @Test
    fun `a one-off payment receipt from an unknown sender is not a subscription`() {
        assertNull(parseMail(
            "receipts@corner-shop.example",
            "<p>Thanks for your order. &#8377;250.00 was charged on 01-09-2026.</p>",
        ))
    }

    @Test
    fun `the stricter reading still keeps an ordinary receipt from a known service`() {
        val e = assertIs<PaymentEvent.Debit>(parseMail("billing@netflix.com", netflixReceipt))
        assertEquals("netflix", e.merchantId)
        assertEquals(Money(BigDecimal("649.00")), e.amount)
    }

    // ---- Payment evidence, not just a merchant name (see SmsClassifier.hasPaymentEvidence) ----

    /**
     * The case this rule exists for. Coursera's own marketing subdomain (m.learn.coursera.org)
     * genuinely is Coursera, so no amount of sender verification would have rejected this mail —
     * only asking whether the reader was actually charged does.
     */
    @Test
    fun `a Coursera Plus advert with a per-year price is not a subscription`() {
        assertNull(parseMail(
            "no-reply@m.learn.coursera.org",
            """
            <p>Unlock unlimited learning with <b>Coursera Plus</b> for just &#8377;7,499/year.
            Get access to 7,000+ courses from top universities. Upgrade now!</p>
            <footer><a>Unsubscribe</a> from Coursera promotional emails.</footer>
            """.trimIndent(),
        ))
    }

    @Test
    fun `a LinkedIn notification with an unrelated number produces no amount`() {
        assertNull(parseMail(
            "messages-noreply@linkedin.com",
            "<p>You have 3 new messages waiting for you on LinkedIn. See what's new.</p>",
        ))
    }

    @Test
    fun `a USD receipt keeps its currency instead of being read as rupees`() {
        val e = assertIs<PaymentEvent.Debit>(parseMail(
            "billing@github.com",
            "<p>Your payment of \$7.00 was charged for GitHub Pro subscription on 01-09-2026.</p>",
        ))
        assertEquals("u:github", e.merchantId)
        assertEquals(Money(BigDecimal("7.00"), "USD"), e.amount)
    }

    @Test
    fun `a price starting at a figure is an advert, not a receipt`() {
        assertNull(parseMail(
            "no-reply@m.learn.coursera.org",
            "<p>Plans starting at just &#8377;399 per month. Subscribe to Coursera Plus today.</p>",
        ))
    }
}
