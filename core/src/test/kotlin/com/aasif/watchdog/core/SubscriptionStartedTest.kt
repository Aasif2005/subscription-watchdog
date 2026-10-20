package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.SourcedEvent
import com.aasif.watchdog.core.engine.SubscriptionEngine
import com.aasif.watchdog.core.engine.nameChannelEvents
import com.aasif.watchdog.core.gmail.GmailQueryBuilder
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.PaymentEvent.Debit
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Welcome to Spotify Premium" names the app behind a Google Play AutoPay and can state the plan
 * length. The mail wording here is synthetic: no real welcome mail was available.
 */
class SubscriptionStartedTest {
    private val mailParser = SmsParser(fromEmail = true)
    private val day = LocalDate.of(2026, 10, 12)
    private val at = LocalDateTime.of(2026, 10, 12, 10, 5)
    private val channels = setOf("googleplay", "apple")

    private fun mail(sender: String, body: String, signedBy: String? = sender.substringAfter('@')) =
        mailParser.parse(Sms(sender, body, at, verifiedDomain = signedBy))

    private fun money(v: String) = Money(BigDecimal(v))
    private val bankMandate = MandateCreated("googleplay", "Google Play", day, money("119.00"), null, null, null, receivedAt = at.minusMinutes(5))

    @Test
    fun `a signed welcome mail from a known service is read as an announcement`() {
        val e = assertIs<MandateCreated>(mail("no-reply@spotify.com", "Welcome to Spotify Premium! Your monthly plan is now active."))
        assertEquals("spotify", e.merchantId)
        assertNull(e.amount)
        assertEquals(Frequency.MONTHLY, e.frequency)
        assertTrue(e.announcementOnly)
    }

    @Test
    fun `an unsigned or unknown sender, or a free account, is not`() {
        assertNull(mail("no-reply@spotify.com", "Welcome to Spotify Premium!", signedBy = null))
        assertNull(mail("hello@randomnewsletter.com", "Welcome to Random Premium!"))
        assertNull(mail("no-reply@spotify.com", "Welcome to Spotify! Start listening for free."))
    }

    @Test
    fun `a welcome mail names the one Google Play mandate around that day`() {
        val welcome = assertIs<MandateCreated>(mail("no-reply@spotify.com", "Welcome to Spotify Premium! Your monthly plan is now active."))
        val named = nameChannelEvents(listOf(SourcedEvent(bankMandate, false), SourcedEvent(welcome, true)), channels)
        assertEquals("spotify", named.first().merchantId)
    }

    @Test
    fun `two services welcoming the user the same day leave the mandate unnamed`() {
        val spotify = MandateCreated("spotify", "Spotify", day, null, null, null, null, fromMail = true)
        val netflix = MandateCreated("netflix", "Netflix", day, null, null, null, null, fromMail = true)
        val named = nameChannelEvents(listOf(SourcedEvent(bankMandate, false), SourcedEvent(spotify, true), SourcedEvent(netflix, true)), channels)
        assertEquals("googleplay", named.first().merchantId)
    }

    @Test
    fun `a receipt with another amount does not name a bank debit`() {
        val bankDebit = Debit("googleplay", "Google Play", day, money("99"), day, viaMandate = false)
        val claudeReceipt = Debit("claude", "Claude", day, money("1999"), day, viaMandate = true)
        assertEquals("googleplay", nameChannelEvents(listOf(SourcedEvent(bankDebit, false), SourcedEvent(claudeReceipt, true)), channels).first().merchantId)
    }

    @Test
    fun `an announcement alone is not a subscription`() {
        val welcome = MandateCreated("spotify", "Spotify", day, null, Frequency.MONTHLY, null, null, fromMail = true)
        assertTrue(SubscriptionEngine().build(listOf(welcome), day).isEmpty())
    }

    @Test
    fun `the period the service states beats the bank's mandate frequency`() {
        // Named Spotify by the welcome mail; the bank's mandate says monthly, the mail says yearly.
        val bank = bankMandate.copy(merchantId = "spotify", merchantName = "Spotify", frequency = Frequency.MONTHLY)
        val welcome = MandateCreated("spotify", "Spotify", day, null, Frequency.YEARLY, null, null, receivedAt = at, fromMail = true)
        val sub = SubscriptionEngine().build(listOf(bank, welcome), day.plusDays(1)).single()
        assertEquals(Frequency.YEARLY, sub.frequency)
        assertEquals(money("119.00"), sub.amount, "the announcement must not erase the bank's amount")
    }

    @Test
    fun `the mail's period dates the end of a plan cancelled the same day`() {
        val bank = bankMandate.copy(merchantId = "spotify", merchantName = "Spotify")
        val welcome = MandateCreated("spotify", "Spotify", day, null, Frequency.MONTHLY, null, null, receivedAt = at, fromMail = true)
        val paid = Debit("spotify", "Spotify", day, money("119"), day, viaMandate = true)
        val revoked = MandateRevoked("spotify", "Spotify", day, at.plusMinutes(30), amount = money("119"))
        val sub = SubscriptionEngine().build(listOf(bank, welcome, paid, revoked), day.plusDays(1)).single()
        assertEquals(SubscriptionStatus.ENDING, sub.status)
        assertEquals(LocalDate.of(2026, 11, 12), sub.activeUntil)
    }

    @Test
    fun `the welcome query asks only known senders and includes Promotions`() {
        val q = GmailQueryBuilder.buildSubscriptionStarted(listOf("spotify", "netflix"), 1_759_000_000L)
        assertTrue(q.contains("from:spotify OR from:netflix"), q)
        assertTrue(q.contains("welcome"), q)
        assertTrue(!q.contains("category:promotions"), q)
    }
}
