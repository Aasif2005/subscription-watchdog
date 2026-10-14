package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.SourcedEvent
import com.aasif.watchdog.core.engine.SubscriptionEngine
import com.aasif.watchdog.core.engine.nameChannelEvents
import com.aasif.watchdog.core.engine.revocationQuestions
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
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A bank's create and revoke texts for one mandate carry the same mandate number, which is what
 * says that a revocation "towards Google Play" ended the Apple Music mandate. The texts follow the
 * shape of a real IOB pair (number after the masked account, 29 characters); the number is made up.
 */
class MandateRefTest {
    private val parser = SmsParser()
    private val engine = SubscriptionEngine()
    private val channels = setOf("googleplay", "apple")
    private val ref = "a1b2c3d4e5f60718293a4b5c6d7e8"
    private val otherRef = "9f8e7d6c5b4a39281706f5e4d3c2b"

    // The parser leaves the arrival time to the store, which sets it when reading a row back.
    private fun bank(verb: String, amount: String, number: String, at: LocalDateTime): PaymentEvent =
        when (val e = parsed(verb, amount, number, at)) {
            is MandateCreated -> e.copy(receivedAt = at)
            is MandateRevoked -> e.copy(receivedAt = at)
            else -> e
        }

    private fun parsed(verb: String, amount: String, number: String, at: LocalDateTime) = assertNotNull(
        parser.parse(
            Sms(
                "BV-IOBCHN-S",
                "Your UPI-Mandate is successfully $verb towards Google Play for Rs.$amount. Funds are " +
                    "${if (verb == "created") "blocked" else "unblocked"} from A/C No. ##AccNum##.$number - IOB Bank",
                at,
            ),
        ),
    )

    private fun refOf(e: PaymentEvent) = when (e) {
        is MandateCreated -> e.mandateRef
        is MandateRevoked -> e.mandateRef
        else -> null
    }

    @Test
    fun `the create and revoke texts of one mandate share a reference`() {
        val created = bank("created", "139.00", ref, LocalDateTime.of(2026, 8, 15, 6, 11))
        val revoked = bank("Revoked", "139.00", ref, LocalDateTime.of(2026, 8, 15, 6, 24))
        assertIs<MandateCreated>(created)
        assertIs<MandateRevoked>(revoked)
        assertNotNull(refOf(created))
        assertEquals(refOf(created), refOf(revoked))
    }

    @Test
    fun `another mandate has another reference, and the number itself is never kept`() {
        val a = bank("created", "139.00", ref, LocalDateTime.of(2026, 8, 15, 6, 11))
        val b = bank("created", "139.00", otherRef, LocalDateTime.of(2026, 6, 16, 2, 18))
        assertNotEquals(refOf(a), refOf(b))
        assertEquals(16, refOf(a)!!.length)
        assertEquals(false, ref.contains(refOf(a)!!))
    }

    @Test
    fun `a number with its UPI handle, or labelled, matches the bare one`() {
        fun refIn(text: String) = assertNotNull(parser.parse(Sms("VM-HDFCBK-S", text, LocalDateTime.of(2026, 8, 15, 6, 24)))).let(::refOf)
        val bare = refIn("Your UPI-Mandate is successfully Revoked towards Google Play for Rs.139.00. UMN $ref")
        assertNotNull(bare)
        assertEquals(bare, refIn("Your UPI-Mandate is successfully Revoked towards Google Play for Rs.139.00. UMN: $ref@okicici"))
        assertEquals(bare, refIn("Your UPI-Mandate is successfully Revoked towards Google Play for Rs.139.00. Ref $ref@okicici"))
    }

    @Test
    fun `account numbers, phone numbers and transaction ids are not mandate numbers`() {
        val text = "Rs 249.00 debited from A/C XX1234 on 15-08-26 to Google Play. UPI Ref 123456789012. Call 18001234567."
        val e = parser.parse(Sms("VM-HDFCBK-S", text, LocalDateTime.of(2026, 8, 15, 6, 24)))
        assertNull(e?.let(::refOf) ?: (e as? PaymentEvent.Debit)?.mandateRef)
    }

    @Test
    fun `a revocation naming only Google Play is filed under the app its mandate was named for`() {
        val bankCreated = bank("created", "139.00", ref, LocalDateTime.of(2026, 8, 15, 6, 11))
        val bankRevoked = bank("Revoked", "139.00", ref, LocalDateTime.of(2026, 8, 15, 6, 24))
        val appleMail = MandateCreated(
            "applemusic", "Apple Music", LocalDate.of(2026, 8, 15), Money(BigDecimal("139.00")), null,
            LocalDate.of(2027, 2, 15), null, autoRenews = true, receivedAt = LocalDateTime.of(2026, 8, 15, 6, 12), fromMail = true,
        )
        val named = nameChannelEvents(
            listOf(SourcedEvent(bankCreated, false), SourcedEvent(appleMail, true), SourcedEvent(bankRevoked, false)),
            channels,
        )
        assertEquals(listOf("applemusic", "applemusic", "applemusic"), named.map { it.merchantId })
    }

    @Test
    fun `a revocation with another mandate's number stays on the channel`() {
        val bankCreated = bank("created", "139.00", ref, LocalDateTime.of(2026, 8, 15, 6, 11))
        val elsewhere = bank("Revoked", "139.00", otherRef, LocalDateTime.of(2026, 8, 15, 6, 24))
        val appleMail = MandateCreated(
            "applemusic", "Apple Music", LocalDate.of(2026, 8, 15), Money(BigDecimal("139.00")), null,
            LocalDate.of(2027, 2, 15), null, receivedAt = LocalDateTime.of(2026, 8, 15, 6, 12), fromMail = true,
        )
        val named = nameChannelEvents(
            listOf(SourcedEvent(bankCreated, false), SourcedEvent(appleMail, true), SourcedEvent(elsewhere, false)),
            channels,
        )
        assertEquals("googleplay", named.last().merchantId)
    }

    @Test
    fun `Apple Music shows its AutoPay as cancelled and still active to the end of the trial`() {
        val bankCreated = bank("created", "139.00", ref, LocalDateTime.of(2026, 8, 15, 6, 11))
        val bankRevoked = bank("Revoked", "139.00", ref, LocalDateTime.of(2026, 8, 15, 6, 24))
        val appleMail = MandateCreated(
            "applemusic", "Apple Music", LocalDate.of(2026, 8, 15), Money(BigDecimal("139.00")), null,
            LocalDate.of(2027, 2, 15), null, autoRenews = true, receivedAt = LocalDateTime.of(2026, 8, 15, 6, 12), fromMail = true,
        )
        val named = nameChannelEvents(
            listOf(SourcedEvent(bankCreated, false), SourcedEvent(appleMail, true), SourcedEvent(bankRevoked, false)),
            channels,
        )
        val apple = engine.build(named, LocalDate.of(2026, 10, 8)).single { it.merchantId == "applemusic" }
        assertEquals(SubscriptionStatus.ENDING, apple.status)
        assertEquals(LocalDate.of(2027, 2, 15), apple.activeUntil)
    }

    // ---- the whole text is scanned ----

    private fun refIn(text: String, parser: SmsParser = SmsParser(), sender: String = "VM-HDFCBK-S") =
        parser.parse(Sms(sender, text, LocalDateTime.of(2026, 8, 15, 6, 24)))?.let(::refOf)

    @Test
    fun `the number is found wherever the text puts it`() {
        val expected = refIn("Your UPI-Mandate is successfully Revoked towards Google Play for Rs.139.00. UMN $ref")
        assertNotNull(expected)
        assertEquals(expected, refIn("Your UPI-Mandate is successfully Revoked towards Google Play for Rs.139.00. Mandate Ref No: $ref. Funds are unblocked."))
        assertEquals(expected, refIn("$ref: UPI-Mandate successfully Revoked towards Google Play for Rs.139.00. -Bank"))
        assertEquals(expected, refIn("Your UPI-Mandate is successfully Revoked towards Google Play for Rs.139.00 (ref $ref@okicici) Funds are unblocked."))
    }

    @Test
    fun `a dashed UUID is the same number as its undashed form`() {
        val dashed = "130a977c-cabb-11e7-abc4-cec278b6b50a"
        val plain = "130a977ccabb11e7abc4cec278b6b50a"
        assertEquals(
            refIn("Your UPI-Mandate is successfully Revoked towards Google Play for Rs.139.00. Ref $dashed"),
            refIn("Your UPI-Mandate is successfully Revoked towards Google Play for Rs.139.00. UMN: $plain"),
        )
    }

    @Test
    fun `numbers that are not a mandate number are not read as one`() {
        assertNull(refIn("Your UPI-Mandate is successfully Revoked towards Google Play for Rs.139.00. Txn 123456789012. A/C XX1234"))
        assertNull(refIn("Your UPI-Mandate is successfully Revoked towards Google Play for Rs.139.00. Details: https://bank.example/m/$ref"))
        assertNull(refIn("Your UPI-Mandate is successfully Revoked towards Google Play for Rs.139.00. Ref a1b2c3d4e5f6071829"))
    }

    @Test
    fun `mail only counts a labelled or handled number`() {
        val mail = SmsParser(fromEmail = true)
        assertNull(mail.mandateRefOf("Your Apple Music subscription of Rs 139.00 was cancelled. Tracking $ref"))
    }

    private fun SmsParser.mandateRefOf(body: String) =
        com.aasif.watchdog.core.parser.SmsClassifier.classify(body).let { _ ->
            parse(Sms("no-reply@apple.com", body, LocalDateTime.of(2026, 8, 15, 6, 24), verifiedDomain = "apple.com"))?.let(::refOf)
        }

    // ---- a number shared by unrelated mandates says nothing ----

    @Test
    fun `a number printed on mandates of different amounts is not used to link them`() {
        val footer = "c0ffee11c0ffee11c0ffee11c0ffee1"
        val a = bank("created", "139.00", footer, LocalDateTime.of(2026, 8, 15, 6, 11))
        val b = bank("created", "1999.00", footer, LocalDateTime.of(2026, 9, 18, 11, 27))
        val revoked = bank("Revoked", "139.00", footer, LocalDateTime.of(2026, 9, 20, 9, 0))
        val appleMail = MandateCreated(
            "applemusic", "Apple Music", LocalDate.of(2026, 8, 15), Money(BigDecimal("139.00")), null,
            LocalDate.of(2027, 2, 15), null, receivedAt = LocalDateTime.of(2026, 8, 15, 6, 12), fromMail = true,
        )
        val named = nameChannelEvents(
            listOf(SourcedEvent(a, false), SourcedEvent(b, false), SourcedEvent(appleMail, true), SourcedEvent(revoked, false)),
            channels,
        )
        assertEquals("googleplay", named.last().merchantId)
    }

    // ---- when nothing can place a cancellation, the user is asked ----

    private fun playMandate(amount: String, at: LocalDateTime) =
        MandateCreated("googleplay", "Google Play", at.toLocalDate(), Money(BigDecimal(amount)), null, null, null, receivedAt = at)

    private fun playRevoke(at: LocalDateTime, amount: String? = null) =
        MandateRevoked("googleplay", "Google Play", at.toLocalDate(), at, amount = amount?.let { Money(BigDecimal(it)) })

    @Test
    fun `a cancellation with no amount or number leaves live plans asking`() {
        val events = listOf(
            playMandate("139.00", LocalDateTime.of(2026, 9, 1, 10, 0)),
            playMandate("1999.00", LocalDateTime.of(2026, 9, 2, 10, 0)),
            playRevoke(LocalDateTime.of(2026, 9, 10, 10, 0)),
        )
        val subs = engine.build(events, LocalDate.of(2026, 10, 8))
        assertEquals(2, subs.count { it.unplacedRevocationOn == LocalDate.of(2026, 9, 10) })
        assertEquals(2, subs.revocationQuestions().size)
    }

    @Test
    fun `a cancellation older than the mandate, or one with an amount, does not ask`() {
        val older = engine.build(
            listOf(playRevoke(LocalDateTime.of(2026, 8, 1, 10, 0)), playMandate("139.00", LocalDateTime.of(2026, 9, 1, 10, 0)), playMandate("1999.00", LocalDateTime.of(2026, 9, 2, 10, 0))),
            LocalDate.of(2026, 10, 8),
        )
        assertEquals(0, older.revocationQuestions().size)
        val stated = engine.build(
            listOf(playMandate("139.00", LocalDateTime.of(2026, 9, 1, 10, 0)), playMandate("1999.00", LocalDateTime.of(2026, 9, 2, 10, 0)), playRevoke(LocalDateTime.of(2026, 9, 10, 10, 0), "149.00")),
            LocalDate.of(2026, 10, 8),
        )
        assertEquals(0, stated.revocationQuestions().size)
    }

    @Test
    fun `a revocation naming no payee is placed by its number, with no mail needed`() {
        val created = MandateCreated("netflix", "Netflix", LocalDate.of(2026, 7, 1), Money(BigDecimal("199.00")), null, null, null, receivedAt = LocalDateTime.of(2026, 7, 1, 9, 0), mandateRef = "0011223344556677")
        val revoked = MandateRevoked("u:unknown", "Unknown merchant", LocalDate.of(2026, 7, 7), LocalDateTime.of(2026, 7, 7, 9, 0), mandateRef = "0011223344556677")
        val named = nameChannelEvents(listOf(SourcedEvent(created, false), SourcedEvent(revoked, false)), channels)
        assertEquals("netflix", named.last().merchantId)
    }
}
