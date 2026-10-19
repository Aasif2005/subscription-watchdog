package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.SubscriptionEngine
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent.MandateCreated
import com.aasif.watchdog.core.model.PaymentEvent.MandateRevoked
import com.aasif.watchdog.core.usage.UsageCategory
import com.aasif.watchdog.core.usage.UsageSpan
import com.aasif.watchdog.core.usage.UsageVerdict
import com.aasif.watchdog.core.usage.trackedApp
import com.aasif.watchdog.core.usage.usagePackagesToWatch
import com.aasif.watchdog.core.usage.usageVerdict
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The date edges and the 80 % rule that decide whether "barely used" may be said. */
class UsageCoverageTest {
    private val renewal = LocalDate.of(2026, 9, 18)
    private val today = LocalDate.of(2026, 10, 16) // finished days: 18 Sep .. 15 Oct = 28 days
    private fun day(date: LocalDate, millis: Long = 0) = UsageSpan(date, 1, millis)
    private fun verdict(spans: List<UsageSpan>, lastRenewal: LocalDate = renewal, now: LocalDate = today, minDays: Int = 14) =
        usageVerdict(spans, lastRenewal, now, UsageCategory.VIDEO, minDays)

    /** Days 0..n-1 after the renewal, except the days in [missing]. */
    private fun zeros(n: Int, missing: Set<Int> = emptySet()) =
        (0 until n).filter { it !in missing }.map { day(renewal.plusDays(it.toLong())) }

    @Test
    fun `80 percent of a 28 day window is 22 point 4 days so 22 is too few and 23 is enough`() {
        // First and last day present, holes in between, so the window stays 28 days.
        fun withHoles(holes: Int) = zeros(28, (1..holes).toSet())
        assertEquals(UsageVerdict.NoData, verdict(withHoles(6)))   // 22 covered
        assertIs<UsageVerdict.Unused>(verdict(withHoles(5)))       // 23 covered
        assertIs<UsageVerdict.Unused>(verdict(withHoles(0)))       // 28 covered
    }

    @Test
    fun `fourteen covered days is the least`() {
        assertEquals(UsageVerdict.NoData, verdict(zeros(13)))
        assertIs<UsageVerdict.Unused>(verdict(zeros(14)))
        // 14 days whose window is 17 days (3 holes): 14 / 17 = 82 %, enough.
        assertIs<UsageVerdict.Unused>(verdict(zeros(17, setOf(3, 7, 11))))
        // 14 days over a 18 day window: 77 %, too sparse.
        assertEquals(UsageVerdict.NoData, verdict(zeros(18, setOf(3, 6, 9, 12))))
    }

    @Test
    fun `a hole at the end or the start is partial, a short period covered fully is not`() {
        val startMissing = (6 until 28).map { day(renewal.plusDays(it.toLong())) } // 22 days from 24 Sep
        val v = verdict(startMissing)
        assertIs<UsageVerdict.Unused>(v)
        assertEquals(true, v.partial)
        assertEquals(LocalDate.of(2026, 9, 24), v.from)
        val endMissing = verdict(zeros(22))
        assertIs<UsageVerdict.Unused>(endMissing)
        assertEquals(true, endMissing.partial)
        assertEquals(false, (verdict(zeros(28)) as UsageVerdict.Unused).partial)
        // One day short of the period is within the one day of slack.
        assertEquals(false, (verdict(zeros(27)) as UsageVerdict.Unused).partial)
        assertEquals(true, (verdict(zeros(26)) as UsageVerdict.Unused).partial)
    }

    @Test
    fun `a day is counted from the renewal day to yesterday and no other day`() {
        val before = day(renewal.minusDays(1), 99 * 60_000)
        val after = day(today, 99 * 60_000)
        val v = verdict(zeros(28) + before + after)
        assertIs<UsageVerdict.Unused>(v) // neither the day before the renewal nor today added any minutes
        assertEquals(renewal, v.from)
        assertEquals(today.minusDays(1), v.to)
    }

    @Test
    fun `a long span counts only when all of it lies inside the period`() {
        // A stretch that began before the period cannot be split, so none of it is used.
        assertEquals(UsageVerdict.NoData, verdict(listOf(UsageSpan(renewal.minusDays(1), 29, 600_000))))
        assertEquals(UsageVerdict.NoData, verdict(listOf(UsageSpan(renewal.minusDays(2), 29, 600_000))))
        // And may not run into today.
        assertEquals(UsageVerdict.NoData, verdict(listOf(UsageSpan(renewal, 29, 600_000))))
        assertIs<UsageVerdict.Low>(verdict(listOf(UsageSpan(renewal, 28, 600_000))))
    }

    @Test
    fun `a renewal today or yesterday has no finished day to judge`() {
        assertEquals(UsageVerdict.NoData, verdict(zeros(5), lastRenewal = today))
        assertEquals(UsageVerdict.NoData, verdict(listOf(day(today.minusDays(1))), lastRenewal = today.minusDays(1)))
        assertEquals(UsageVerdict.NoData, verdict(zeros(28), lastRenewal = today.plusDays(1)))
    }

    @Test
    fun `the floor is the use of the days seen, not of the whole period`() {
        // VIDEO floor 60 min / 30 days: 23 covered days -> 46 min. 45 min is low, 46 is fine.
        val spans = { minutes: Long -> zeros(23).take(22) + day(renewal.plusDays(22), minutes * 60_000) }
        assertIs<UsageVerdict.Low>(verdict(spans(45)))
        assertEquals(UsageVerdict.Ok, verdict(spans(46)))
    }

    @Test
    fun `random span sets never give a false claim`() {
        repeat(2000) { seed ->
            val rnd = Random(seed)
            val period = rnd.nextInt(28, 120)
            val start = LocalDate.of(2026, 1, 1).plusDays(rnd.nextLong(0, 200))
            val now = start.plusDays(period.toLong() + 1)
            // Mix one-day spans with a few longer ones, some overlapping, some outside the period.
            val spans = ArrayList<UsageSpan>()
            var d = -3
            while (d < period + 3) {
                val len = if (rnd.nextInt(6) == 0) rnd.nextInt(2, 12) else 1
                val millis = if (rnd.nextInt(3) == 0) 0 else rnd.nextLong(0, 90 * 60_000)
                if (rnd.nextInt(5) != 0) spans += UsageSpan(start.plusDays(d.toLong()), len, millis)
                d += if (rnd.nextInt(4) == 0) 1 else len
            }
            val v = usageVerdict(spans, start, now, UsageCategory.VIDEO)
            val lastDay = now.minusDays(1)
            // Rebuild what should count with an independent, brute-force pass.
            val taken = HashSet<LocalDate>()
            var covered = 0
            var total = 0L
            for (s in spans.sortedBy { it.days }) {
                if (s.firstDay.isBefore(start) || s.lastDay.isAfter(lastDay)) continue
                val ds = (0 until s.days).map { s.firstDay.plusDays(it.toLong()) }
                if (ds.any { it in taken }) continue
                taken += ds; covered += s.days; total += s.millis
            }
            val tag = "seed $seed"
            when (v) {
                UsageVerdict.NoData -> {}
                UsageVerdict.Ok -> assertTrue(covered >= 14 && total >= 60 * 60_000L * covered / 30.0, "$tag ok")
                is UsageVerdict.Unused -> assertTrue(total == 0L && covered >= 14, "$tag unused with $total ms")
                is UsageVerdict.Low -> {
                    assertTrue(total > 0 && total < 60 * 60_000L * covered / 30.0 && covered >= 14, "$tag low")
                    assertEquals(maxOf(1L, total / 60_000), v.minutes, "$tag minutes")
                }
            }
            if (v is UsageVerdict.Unused || v is UsageVerdict.Low) {
                val from = taken.min(); val last = taken.max()
                assertTrue(covered >= (java.time.temporal.ChronoUnit.DAYS.between(from, last) + 1) * 0.8, "$tag coverage")
                // Order of the input must not matter.
                assertEquals(v, usageVerdict(spans.shuffled(rnd), start, now, UsageCategory.VIDEO), "$tag order")
            }
        }
    }

    // --- Active subscriptions only ---

    private val engine = SubscriptionEngine()
    private fun money(v: String) = Money(BigDecimal(v))
    private fun mandate(id: String, name: String, on: LocalDate) =
        MandateCreated(id, name, on, money("199.00"), Frequency.MONTHLY, on.plusMonths(1), null, autoRenews = true, fromMail = true)

    @Test
    fun `only live subscriptions of tracked services have their apps watched`() {
        val now = LocalDate.of(2026, 10, 16)
        val live = engine.build(listOf(mandate("netflix", "Netflix", LocalDate.of(2026, 9, 20)), mandate("hotstar", "JioHotstar", LocalDate.of(2026, 9, 25))), now)
        assertEquals(setOf("com.netflix.mediaclient", "in.startv.hotstar", "in.startv.hotstar.dplus"), usagePackagesToWatch(live))
        // A service that is not on the tracked list adds nothing.
        val other = engine.build(listOf(mandate("zomato", "Zomato", LocalDate.of(2026, 9, 20))), now)
        assertEquals(emptySet(), usagePackagesToWatch(other))
        // Music is watched through playback time, which only newer Android reports; without it the app would read as never used.
        val music = engine.build(listOf(mandate("spotify", "Spotify", LocalDate.of(2026, 9, 20))), now)
        assertEquals(setOf("com.spotify.music"), usagePackagesToWatch(music))
        assertEquals(emptySet(), usagePackagesToWatch(music, playbackSupported = false))
        // A subscription whose AutoPay was revoked is no longer watched.
        val revoked = MandateRevoked("netflix", "Netflix", LocalDate.of(2026, 10, 1), null, amount = money("199.00"), activeUntil = null)
        val ended = engine.build(listOf(mandate("netflix", "Netflix", LocalDate.of(2026, 9, 20)), revoked), now)
        assertEquals(emptySet(), usagePackagesToWatch(ended))
        assertEquals(emptySet(), usagePackagesToWatch(emptyList()))
    }

    @Test
    fun `a subscription added by hand is matched by the name typed`() {
        val now = LocalDate.of(2026, 10, 16)
        fun manual(name: String) = engine.manual("manual:1", name, money("199"), Frequency.MONTHLY, LocalDate.of(2026, 9, 18), false, now)
        assertEquals(setOf("com.netflix.mediaclient"), usagePackagesToWatch(listOf(manual("Netflix"))))
        assertEquals(setOf("com.netflix.mediaclient"), usagePackagesToWatch(listOf(manual("my netflix plan"))))
        assertEquals(emptySet(), usagePackagesToWatch(listOf(manual("Gym"))))
        assertEquals("netflix", manual("Netflix").trackedApp()?.merchantId)
    }
}
