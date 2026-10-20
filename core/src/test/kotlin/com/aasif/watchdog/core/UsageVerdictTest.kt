package com.aasif.watchdog.core

import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.usage.TrackedApps
import com.aasif.watchdog.core.usage.UsageCategory
import com.aasif.watchdog.core.usage.UsageVerdict
import com.aasif.watchdog.core.usage.UsageSpan
import com.aasif.watchdog.core.usage.usageVerdict
import com.aasif.watchdog.core.usage.usageSummary
import com.aasif.watchdog.core.usage.usageLineText
import com.aasif.watchdog.core.usage.usageTrackingText
import com.aasif.watchdog.core.usage.usagePeriodStart
import com.aasif.watchdog.core.model.Charge
import com.aasif.watchdog.core.model.Confidence
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.RenewalSource
import com.aasif.watchdog.core.model.Subscription
import com.aasif.watchdog.core.model.SubscriptionStatus
import java.math.BigDecimal
import com.aasif.watchdog.core.usage.usageNoteText
import com.aasif.watchdog.core.usage.usagePackagesToWatch
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class UsageVerdictTest {
    private val renewal = LocalDate.of(2026, 10, 1)
    private val today = LocalDate.of(2026, 10, 29)

    /** The day rows from [monitoringSince] on, as one-day spans. */
    private fun verdict(
        minutes: Map<LocalDate, Long>,
        lastRenewal: LocalDate,
        monitoringSince: LocalDate,
        today: LocalDate,
        category: UsageCategory,
    ) = usageVerdict(
        minutes.filterKeys { !it.isBefore(monitoringSince) }.map { (d, m) -> UsageSpan(d, 1, m * 60_000) },
        lastRenewal, today, category,
    )

    /** One value per finished day from [from] through yesterday, overridden by [days]. */
    private fun watched(from: LocalDate, perDay: Long, vararg except: Pair<LocalDate, Long>): Map<LocalDate, Long> {
        val m = generateSequence(from) { it.plusDays(1) }.takeWhile { it.isBefore(today) }.associateWith { perDay }.toMutableMap()
        except.forEach { (d, v) -> m[d] = v }
        return m
    }

    @Test
    fun `zero use over a fully watched period is unused`() {
        val v = verdict(watched(renewal, 0), renewal, renewal, today, UsageCategory.VIDEO)
        assertEquals(UsageVerdict.Unused(renewal, today.minusDays(1)), v)
    }

    @Test
    fun `a little use is low and reports the minutes`() {
        // 12 minutes in the whole month against a 60-minute floor.
        val v = verdict(watched(renewal, 0, renewal.plusDays(3) to 12), renewal, renewal, today, UsageCategory.VIDEO)
        assertEquals(UsageVerdict.Low(12, renewal, today.minusDays(1)), v)
    }

    @Test
    fun `use at or above the floor is fine`() {
        assertEquals(UsageVerdict.Ok, verdict(watched(renewal, 2), renewal, renewal, today, UsageCategory.VIDEO)) // 56 min, floor 56
        assertEquals(UsageVerdict.Ok, verdict(watched(renewal, 3), renewal, renewal, today, UsageCategory.VIDEO))
    }

    @Test
    fun `just under the floor is low`() {
        // 28 days watched: floor is 60 * 28 / 30 = 56; 55 minutes is under it.
        val v = verdict(watched(renewal, 0, renewal.plusDays(2) to 55), renewal, renewal, today, UsageCategory.VIDEO)
        assertIs<UsageVerdict.Low>(v)
    }

    @Test
    fun `productivity apps have a lower floor`() {
        val m = watched(renewal, 0, renewal.plusDays(2) to 40)
        assertEquals(UsageVerdict.Ok, verdict(m, renewal, renewal, today, UsageCategory.PRODUCTIVITY))
        assertIs<UsageVerdict.Low>(verdict(m, renewal, renewal, today, UsageCategory.VIDEO))
    }

    @Test
    fun `watching that began mid period covers the days seen and says it is partial`() {
        val began = LocalDate.of(2026, 10, 10)
        val v = verdict(watched(began, 0), renewal, began, today, UsageCategory.VIDEO)
        assertEquals(UsageVerdict.Unused(began, today.minusDays(1), partial = true), v)
    }

    @Test
    fun `too few watched days say nothing`() {
        val began = LocalDate.of(2026, 10, 20)
        assertEquals(UsageVerdict.NoData, verdict(watched(began, 0), renewal, began, today, UsageCategory.VIDEO))
    }

    @Test
    fun `days Android no longer had are unknown, not zero`() {
        // Only the last 9 days are known: not enough, so no claim that the app was unused.
        assertEquals(UsageVerdict.NoData, verdict(watched(today.minusDays(9), 0), renewal, renewal, today, UsageCategory.VIDEO))
        // A hole of more than a fifth of the period also means no claim.
        val holes = watched(renewal, 0).filterKeys { it.dayOfMonth !in 5..14 }
        assertEquals(UsageVerdict.NoData, verdict(holes, renewal, renewal, today, UsageCategory.VIDEO))
    }

    @Test
    fun `an app that is not on the phone has no rows and so no verdict`() {
        assertEquals(UsageVerdict.NoData, verdict(emptyMap(), renewal, renewal, today, UsageCategory.VIDEO))
    }

    @Test
    fun `renewal today leaves no finished day to judge`() {
        assertEquals(UsageVerdict.NoData, verdict(emptyMap(), today, renewal, today, UsageCategory.VIDEO))
    }

    @Test
    fun `music apps are tracked as audio, by playback and not screen time`() {
        assertEquals(UsageCategory.AUDIO, TrackedApps.forMerchant("spotify")?.category)
        assertEquals(UsageCategory.AUDIO, TrackedApps.forMerchant("applemusic")?.category)
        assertEquals(UsageCategory.VIDEO, TrackedApps.forMerchant("netflix")?.category)
    }

    @Test
    fun `the period before a renewal starts one period earlier`() {
        assertEquals(LocalDate.of(2026, 9, 30), Frequency.MONTHLY.previous(LocalDate.of(2026, 10, 30)))
        assertEquals(LocalDate.of(2025, 10, 31), Frequency.YEARLY.previous(LocalDate.of(2026, 10, 31)))
        assertEquals(LocalDate.of(2026, 10, 24), Frequency.WEEKLY.previous(LocalDate.of(2026, 10, 31)))
    }

    @Test
    fun `seconds of real use on many days are never rounded to no use`() {
        // 20 seconds on each of 28 days is 9 minutes: low, not unused.
        val spans = (0 until 28).map { UsageSpan(renewal.plusDays(it.toLong()), 1, 20_000) }
        val v = usageVerdict(spans, renewal, today, UsageCategory.VIDEO)
        assertEquals(UsageVerdict.Low(9, renewal, today.minusDays(1)), v)
    }

    private fun days(from: LocalDate, count: Int, minutesEach: Long) =
        (0 until count).map { UsageSpan(from.plusDays(it.toLong()), 1, minutesEach * 60_000) }

    @Test
    fun `a summary is given from the first watched day, before a verdict could be`() {
        val spans = days(today.minusDays(3), 3, 10)
        assertIs<UsageVerdict.NoData>(usageVerdict(spans, renewal, today, UsageCategory.VIDEO))
        val s = usageSummary(spans, renewal, today, playback = false)!!
        assertEquals(30, s.minutes)
        assertEquals(3, s.days)
    }

    @Test
    fun `no watched days gives no summary`() {
        assertNull(usageSummary(emptyList(), renewal, today, playback = false))
        // The period has not had a finished day yet.
        assertNull(usageSummary(days(today, 1, 5), today, today, playback = false))
    }

    @Test
    fun `a summary counts each day once`() {
        val s = usageSummary(days(renewal, 5, 10) + UsageSpan(renewal, 3, 999_000_000), renewal, today, playback = false)!!
        assertEquals(50, s.minutes)
    }

    private fun fmt(d: LocalDate) = "${d.dayOfMonth} Oct"

    @Test
    fun `the figure says it is about this device, from which day, and names playing time for music`() {
        val screen = usageSummary(days(renewal, 28, 20), renewal, today, playback = false)!!
        assertEquals("Screen time on this device since 1 Oct: 9 h 20 min", usageLineText(screen, ::fmt))
        val music = usageSummary(days(today.minusDays(9), 9, 30), renewal, today, playback = true)!!
        assertTrue(usageLineText(music, ::fmt).startsWith("Playing time on this device since "), usageLineText(music, ::fmt))
        assertTrue("on this device only" in usageNoteText(playback = true))
        assertTrue("on this device only" in usageNoteText(playback = false))
        assertTrue("on this device" in usageTrackingText(today, ::fmt))
    }

    @Test
    fun `days Android no longer had are said so`() {
        val s = usageSummary(days(today.minusDays(9), 9, 20), renewal, today, playback = false)!!
        assertTrue(s.partial)
        assertTrue("earlier days weren't available" in usageLineText(s, ::fmt))
        val whole = usageSummary(days(renewal, 28, 20), renewal, today, playback = false)!!
        assertFalse(whole.partial)
    }

    private fun sub(charges: List<LocalDate>, events: List<PaymentEvent>, renewal: LocalDate?, frequency: Frequency = Frequency.MONTHLY) = Subscription(
        key = "claude", merchantId = "claude", name = "Claude Pro", category = "AI",
        amount = Money(BigDecimal(1999)), frequency = frequency, nextRenewal = renewal,
        renewalSource = RenewalSource.MANDATE, confidence = Confidence.HIGH, status = SubscriptionStatus.ACTIVE,
        charges = charges.map { Charge(it, Money(BigDecimal(1999))) }, cancelUrl = null, events = events,
    )

    private fun mandate(on: LocalDate) =
        PaymentEvent.MandateCreated("claude", "Claude", on, Money(BigDecimal(1999)), Frequency.MONTHLY, on, null)

    @Test
    fun `the period starts at the debit, or at the day a new plan was taken`() {
        // Debited today: counting starts today, not a period earlier.
        val debited = sub(listOf(LocalDate.of(2026, 10, 19)), listOf(mandate(LocalDate.of(2026, 9, 18))), LocalDate.of(2026, 11, 18))
        assertEquals(LocalDate.of(2026, 10, 19), usagePeriodStart(debited))
        // A plan taken on the 10th with no debit yet: from the 10th.
        val fresh = sub(emptyList(), listOf(mandate(LocalDate.of(2026, 10, 10))), LocalDate.of(2026, 11, 10))
        assertEquals(LocalDate.of(2026, 10, 10), usagePeriodStart(fresh))
        // A plan whose AutoPay was cancelled has no renewal date, and still starts at its last charge.
        val cancelled = sub(listOf(LocalDate.of(2026, 10, 5)), emptyList(), null)
        assertEquals(LocalDate.of(2026, 10, 5), usagePeriodStart(cancelled))
        // Nothing known at all, and no renewal to count back from.
        assertNull(usagePeriodStart(sub(emptyList(), emptyList(), null)))
    }

    @Test
    fun `a plan not charged yet is counted from the day it was taken, however far its first debit is`() {
        // ChatGPT Go: taken 5 Nov 2025, monthly, first debit a year later. Not pulled forward to a month before the debit.
        val taken = LocalDate.of(2025, 11, 5)
        val firstDebit = LocalDate.of(2026, 11, 5)
        val go = sub(emptyList(), listOf(mandate(taken)), firstDebit)
        assertEquals(taken, usagePeriodStart(go))
        assertEquals(taken, usagePeriodStart(go, firstDebit))
        // A mail announcing a plan, with no amount, makes no plan of its own: the one-period limit applies.
        val announced = PaymentEvent.MandateCreated("claude", "Claude", taken, null, null, null, null, fromMail = true)
        assertEquals(firstDebit.minusMonths(1), usagePeriodStart(sub(emptyList(), listOf(announced), firstDebit)))
        // After the debit, counting restarts there.
        val debited = sub(listOf(firstDebit), listOf(mandate(taken)), LocalDate.of(2026, 12, 5))
        assertEquals(firstDebit, usagePeriodStart(debited))
    }

    @Test
    fun `an old charge is still held to one period before the renewal`() {
        // Charged in January, renewal in October: the charge is stale, so the period is the one before the renewal.
        val stale = sub(listOf(LocalDate.of(2026, 1, 5)), listOf(mandate(LocalDate.of(2025, 12, 1))), LocalDate.of(2026, 11, 5))
        assertEquals(LocalDate.of(2026, 10, 5), usagePeriodStart(stale))
    }

    @Test
    fun `a weekly stretch that began before the period is not used`() {
        val start = LocalDate.of(2026, 10, 5)
        val spans = days(LocalDate.of(2026, 10, 10), 9, 2) + UsageSpan(LocalDate.of(2026, 10, 4), 6, 99_000_000)
        val s = usageSummary(spans, start, LocalDate.of(2026, 10, 19), playback = false)!!
        assertEquals(18, s.minutes)
        assertEquals(9, s.days)
    }

    @Test
    fun `there is no figure on the day a period begins`() {
        assertNull(usageSummary(days(LocalDate.of(2026, 10, 10), 9, 2), LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 19), playback = false))
    }

    @Test
    fun `music apps are tracked by playback and their hours do not read as unused`() {
        assertEquals(true, TrackedApps.forMerchant("spotify")!!.measuresPlayback)
        assertEquals(true, TrackedApps.forMerchant("applemusic")!!.measuresPlayback)
        assertEquals(false, TrackedApps.forMerchant("netflix")!!.measuresPlayback)
        // Listening an hour a day is well above the music floor.
        assertIs<UsageVerdict.Ok>(usageVerdict(days(renewal, 28, 60), renewal, today, UsageCategory.AUDIO))
        assertIs<UsageVerdict.Low>(usageVerdict(days(renewal, 28, 1), renewal, today, UsageCategory.AUDIO))
    }

    @Test
    fun `without playback time a music app is not watched at all`() {
        assertTrue("com.spotify.music" in TrackedApps.playbackPackages)
        assertFalse("com.netflix.mediaclient" in TrackedApps.playbackPackages)
    }
}
