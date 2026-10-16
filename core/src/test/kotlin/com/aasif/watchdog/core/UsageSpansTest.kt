package com.aasif.watchdog.core

import com.aasif.watchdog.core.usage.BucketLevel
import com.aasif.watchdog.core.usage.HistorySpan
import com.aasif.watchdog.core.usage.UsageBucket
import com.aasif.watchdog.core.usage.UsageCategory
import com.aasif.watchdog.core.usage.UsageSpan
import com.aasif.watchdog.core.usage.UsageVerdict
import com.aasif.watchdog.core.usage.residualSpans
import com.aasif.watchdog.core.usage.usageSpan
import com.aasif.watchdog.core.usage.usageVerdict
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageSpansTest {
    private val india = ZoneId.of("Asia/Kolkata")
    private fun at(local: String, zone: ZoneId = india): Long = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()
    private fun sec(s: Long) = s * 1000
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun b(level: BucketLevel, start: String, seconds: Long) = UsageBucket(level, at(start), sec(seconds))

    // --- A real phone: YouTube on a Samsung, 16 Oct 2026, figures copied from Android's own buckets (seconds). ---

    private val now = at("2026-10-16T21:03:00")
    private val realYoutube = listOf(
        b(BucketLevel.DAILY, "2026-10-07T20:43:55", 1251),
        b(BucketLevel.DAILY, "2026-10-08T20:44:55", 0),
        b(BucketLevel.DAILY, "2026-10-09T20:45:38", 0),
        b(BucketLevel.DAILY, "2026-10-10T20:45:45", 125),
        b(BucketLevel.DAILY, "2026-10-11T20:51:23", 693),
        b(BucketLevel.DAILY, "2026-10-12T20:51:38", 23),
        b(BucketLevel.DAILY, "2026-10-13T20:52:48", 55),
        b(BucketLevel.DAILY, "2026-10-14T20:56:48", 1455),
        b(BucketLevel.DAILY, "2026-10-15T20:57:26", 260),
        b(BucketLevel.DAILY, "2026-10-16T20:58:33", 2), // still running
        b(BucketLevel.WEEKLY, "2026-09-19T20:14:44", 4906),
        b(BucketLevel.WEEKLY, "2026-09-26T20:28:47", 4411),
        b(BucketLevel.WEEKLY, "2026-10-03T20:34:31", 2797),
        b(BucketLevel.WEEKLY, "2026-10-10T20:45:45", 2613), // running
        b(BucketLevel.MONTHLY, "2026-04-20T13:58:33", 9696),
        b(BucketLevel.MONTHLY, "2026-05-20T14:49:45", 27587),
        b(BucketLevel.MONTHLY, "2026-06-19T15:04:45", 29467),
        b(BucketLevel.MONTHLY, "2026-07-19T19:18:42", 19178),
        b(BucketLevel.MONTHLY, "2026-08-18T19:40:50", 49625),
        b(BucketLevel.MONTHLY, "2026-09-17T20:13:38", 23875), // running
        b(BucketLevel.YEARLY, "2025-02-23T23:28:18", 139408),
        b(BucketLevel.YEARLY, "2026-02-24T10:15:08", 189815), // running
    )

    @Test
    fun `real phone data gives exactly the leftovers worked out by hand`() {
        val h = residualSpans(realYoutube, now)
        // Nine finished daily buckets; the one running is not a day yet.
        assertEquals(9, h.dailies.size)
        assertEquals(at("2026-10-07T20:43:55"), h.dailies.first().startMillis)
        assertEquals(at("2026-10-15T20:57:26"), h.dailies.last().startMillis)

        val heads = h.heads.sortedBy { it.startMillis }
        // yearly 2025 (whole, nothing finer inside), yearly 2026 head, monthly Apr..Aug whole, monthly Sep head, weeklies.
        val byStart = heads.associateBy { it.startMillis }

        // Monthly 17 Sep: 23875 - (4906 + 4411 + 2797 + 2613) = 9148 s, from the 17th to where the first weekly starts.
        val sepHead = byStart.getValue(at("2026-09-17T20:13:38"))
        assertEquals(at("2026-09-19T20:14:44"), sepHead.endMillis)
        assertEquals(sec(9148), sepHead.millis)

        // The two whole weeks have nothing finer inside.
        assertEquals(sec(4906), byStart.getValue(at("2026-09-19T20:14:44")).millis)
        assertEquals(sec(4411), byStart.getValue(at("2026-09-26T20:28:47")).millis)

        // Weekly 3 Oct: 2797 - (1251 + 0 + 0) = 1546 s, up to the first daily bucket.
        val octHead = byStart.getValue(at("2026-10-03T20:34:31"))
        assertEquals(at("2026-10-07T20:43:55"), octHead.endMillis)
        assertEquals(sec(1546), octHead.millis)

        // The running week is covered by daily buckets: nothing is left over.
        assertNull(byStart[at("2026-10-10T20:45:45")])

        // Yearly 2026: 189815 - (9696+27587+29467+19178+49625+23875) = 30387 s, from 24 Feb to the first monthly bucket.
        val yearHead = byStart.getValue(at("2026-02-24T10:15:08"))
        assertEquals(at("2026-04-20T13:58:33"), yearHead.endMillis)
        assertEquals(sec(30387), yearHead.millis)
    }

    @Test
    fun `real phone pieces tile the time with no overlap`() {
        val h = residualSpans(realYoutube, now)
        val all = (h.dailies + h.heads).sortedBy { it.startMillis }
        all.zipWithNext().forEach { (a, c) ->
            assertTrue(a.endMillis <= c.startMillis + 3 * hour, "overlap between $a and $c")
        }
        // From the 17th of September to the last finished day, every hour is accounted for once.
        val covered = all.filter { it.startMillis >= at("2026-09-17T00:00:00") }
        assertEquals(at("2026-09-17T20:13:38"), covered.first().startMillis)
        covered.zipWithNext().forEach { (a, c) -> assertTrue(c.startMillis - a.endMillis < 3 * hour, "gap between $a and $c") }
    }

    @Test
    fun `leftover plus finer totals add back up to the coarse total`() {
        val h = residualSpans(realYoutube, now)
        val monthSep = realYoutube.first { it.level == BucketLevel.MONTHLY && it.startMillis == at("2026-09-17T20:13:38") }
        val weeks = realYoutube.filter { it.level == BucketLevel.WEEKLY }.sumOf { it.millis }
        val head = h.heads.first { it.startMillis == monthSep.startMillis }
        assertEquals(monthSep.millis, head.millis + weeks)
    }

    // --- Cases where the buckets do not fit: nothing is guessed. ---

    private val t0 = at("2026-10-01T08:00:00")
    private val n = t0 + 20 * day

    private fun daily(i: Int, seconds: Long) = UsageBucket(BucketLevel.DAILY, t0 + 7 * day + i * day, sec(seconds))

    @Test
    fun `a coarse bucket with no finer bucket inside counts whole`() {
        val h = residualSpans(listOf(UsageBucket(BucketLevel.WEEKLY, t0, sec(600)), UsageBucket(BucketLevel.WEEKLY, t0 + 7 * day, sec(5))), t0 + 10 * day)
        assertEquals(listOf(HistorySpan(t0, t0 + 7 * day, sec(600)), HistorySpan(t0 + 7 * day, t0 + 10 * day, sec(5))), h.heads)
    }

    @Test
    fun `finer totals above the coarse total drop the leftover`() {
        val week = UsageBucket(BucketLevel.WEEKLY, t0, sec(1000))
        val days = (0 until 4).map { UsageBucket(BucketLevel.DAILY, t0 + 3 * day + it * day, sec(400)) } // 1600 > 1000
        assertTrue(residualSpans(listOf(week) + days, t0 + 7 * day).heads.isEmpty())
    }

    @Test
    fun `a rounding shortfall of seconds is a zero, not a negative`() {
        val week = UsageBucket(BucketLevel.WEEKLY, t0, sec(1000))
        val days = (0 until 4).map { UsageBucket(BucketLevel.DAILY, t0 + 3 * day + it * day, sec(250)) } // exactly 1000
        val head = residualSpans(listOf(week) + days, t0 + 7 * day).heads.single()
        assertEquals(0, head.millis)
        val slightly = UsageBucket(BucketLevel.WEEKLY, t0, sec(1000) - 30_000)
        assertEquals(0, residualSpans(listOf(slightly) + days, t0 + 7 * day).heads.single().millis)
    }

    @Test
    fun `a missing finer bucket in the middle drops the leftover`() {
        val week = UsageBucket(BucketLevel.WEEKLY, t0, sec(5000))
        val days = listOf(3, 4, 6).map { UsageBucket(BucketLevel.DAILY, t0 + it * day, sec(10)) } // day 5 is missing
        assertTrue(residualSpans(listOf(week) + days, t0 + 7 * day).heads.isEmpty())
    }

    @Test
    fun `a finer bucket that stops short of the coarse end drops the leftover`() {
        val week = UsageBucket(BucketLevel.WEEKLY, t0, sec(5000))
        val days = listOf(3, 4, 5).map { UsageBucket(BucketLevel.DAILY, t0 + it * day, sec(10)) } // nothing for day 6
        assertTrue(residualSpans(listOf(week) + days, t0 + 7 * day + hour).heads.isEmpty())
    }

    @Test
    fun `a finer bucket sticking out of the coarse bucket drops the leftover`() {
        val week = UsageBucket(BucketLevel.WEEKLY, t0, sec(5000))
        val next = UsageBucket(BucketLevel.WEEKLY, t0 + 7 * day, sec(50))
        // The last daily bucket begins half a day before the week ends, so it reaches half a day into the next one.
        val days = listOf(3 * day, 4 * day, 5 * day, 6 * day + 12 * hour).map { UsageBucket(BucketLevel.DAILY, t0 + it, sec(10)) }
        val heads = residualSpans(listOf(week, next) + days, t0 + 8 * day).heads
        assertTrue(heads.none { it.startMillis == t0 })
    }

    @Test
    fun `a longer bucket answered for a daily query is not a day`() {
        // Android once answered a daily query with a week-long bucket; classified by the reader as weekly, never as a day.
        val h = residualSpans(listOf(UsageBucket(BucketLevel.WEEKLY, t0, sec(700))), t0 + 8 * day)
        assertTrue(h.dailies.isEmpty())
        assertEquals(sec(700), h.heads.single().millis)
    }

    @Test
    fun `a package missing from an existing bucket is a real zero`() {
        val week = UsageBucket(BucketLevel.WEEKLY, t0, 0)
        val days = (3 until 7).map { UsageBucket(BucketLevel.DAILY, t0 + it * day, 0) }
        val h = residualSpans(listOf(week) + days, t0 + 7 * day)
        assertEquals(0, h.heads.single().millis)
        assertEquals(4, h.dailies.size)
    }

    @Test
    fun `a bucket still running is not a finished day`() {
        val h = residualSpans(listOf(UsageBucket(BucketLevel.DAILY, t0, sec(60))), t0 + 5 * hour)
        assertTrue(h.dailies.isEmpty())
    }

    @Test
    fun `leftovers shorter than six hours are not worth a span`() {
        val week = UsageBucket(BucketLevel.WEEKLY, t0, sec(100))
        val days = (0 until 7).map { UsageBucket(BucketLevel.DAILY, t0 + 4 * hour + it * day, sec(10)) }
        assertTrue(residualSpans(listOf(week) + days, t0 + 7 * day + 4 * hour).heads.isEmpty())
    }

    // --- Property test: the pieces equal the real usage in the same hours, for many random phones. ---

    @Test
    fun `random phones never give a false total`() {
        repeat(400) { seed ->
            val rnd = Random(seed)
            val base = at("2024-12-20T09:00:00")
            val days = 420 + rnd.nextInt(380)
            // Daily boundaries drift by up to an hour a day, like a phone that restarts its day.
            val dailyBounds = ArrayList<Long>().apply {
                var t = base + rnd.nextLong(0, 20 * hour)
                repeat(days + 2) { add(t); t += day + rnd.nextLong(-hour, hour) }
            }
            val nowMs = dailyBounds[days] + rnd.nextLong(30 * 60_000, day - hour)
            val lastIdx = dailyBounds.indexOfLast { it <= nowMs }
            // Coarser boundaries sit on a daily boundary give or take two minutes.
            fun boundsEvery(step: Int, offset: Int) = (offset..lastIdx step step).map { dailyBounds[it] + rnd.nextLong(-120_000, 120_000) }
            val levels = mapOf(
                BucketLevel.DAILY to dailyBounds.take(lastIdx + 1),
                BucketLevel.WEEKLY to boundsEvery(7, rnd.nextInt(7)),
                BucketLevel.MONTHLY to boundsEvery(30, rnd.nextInt(30)),
                BucketLevel.YEARLY to boundsEvery(365, rnd.nextInt(60)),
            )
            val allBounds = levels.values.flatten()
            // Usage events, never within five minutes of any boundary, so a boundary's seconds of drift cannot matter.
            val events = ArrayList<Pair<Long, Long>>()
            repeat(days * 2) {
                val at = base + rnd.nextLong(0, nowMs - base)
                if (at < nowMs && allBounds.none { kotlin.math.abs(it - at) < 5 * 60_000 }) events += at to rnd.nextLong(1, 40) * 60_000
            }
            fun truth(from: Long, to: Long) = events.filter { it.first in from until to }.sumOf { it.second }
            // Android keeps only the newest few buckets of each level.
            val keep = mapOf(
                BucketLevel.DAILY to 3 + rnd.nextInt(10),
                BucketLevel.WEEKLY to 1 + rnd.nextInt(5),
                BucketLevel.MONTHLY to 1 + rnd.nextInt(7),
                BucketLevel.YEARLY to 1 + rnd.nextInt(3),
            )
            val buckets = levels.flatMap { (level, starts) ->
                starts.mapIndexed { i, s -> UsageBucket(level, s, truth(s, starts.getOrNull(i + 1) ?: nowMs)) }.takeLast(keep.getValue(level))
            }

            val h = residualSpans(buckets, nowMs)
            val pieces = (h.dailies + h.heads).sortedBy { it.startMillis }
            val tag = "seed $seed"

            h.dailies.forEach { assertEquals(truth(it.startMillis, it.endMillis), it.millis, "$tag daily $it") }
            h.heads.forEach {
                assertTrue(it.millis >= 0, "$tag negative $it")
                assertTrue(it.endMillis > it.startMillis, "$tag empty $it")
                assertEquals(truth(it.startMillis, it.endMillis), it.millis, "$tag head $it")
            }
            pieces.zipWithNext().forEach { (a, c) -> assertTrue(a.endMillis <= c.startMillis + 5 * 60_000, "$tag overlap $a $c") }
            // Nothing newer than the newest finished daily bucket, and nothing from the future.
            assertTrue(pieces.all { it.endMillis <= nowMs + 1 }, "$tag future")
        }
    }

    // --- Dates ---

    @Test
    fun `a span is labelled by its midpoint and counted in whole days`() {
        // 3 Oct 20:34 to 7 Oct 20:43 is four days, labelled from the 4th (the start's midpoint-day rule).
        val s = usageSpan(at("2026-10-03T20:34:31"), at("2026-10-07T20:43:55"), 7_000, india)
        assertEquals(UsageSpan(LocalDate.of(2026, 10, 4), 4, 7_000), s)
        assertEquals(LocalDate.of(2026, 10, 7), s.lastDay)
    }

    @Test
    fun `spans keep whole days across the clock change`() {
        val ny = ZoneId.of("America/New_York")
        // Clocks go forward on 8 Mar 2026: three calendar days are 71 hours.
        val s = usageSpan(at("2026-03-07T20:00:00", ny), at("2026-03-10T19:00:00", ny), 1, ny)
        assertEquals(3, s.days)
        assertEquals(LocalDate.of(2026, 3, 8), s.firstDay)
        // Clocks go back on 1 Nov 2026: three days are 73 hours.
        val back = usageSpan(at("2026-10-31T20:00:00", ny), at("2026-11-03T21:00:00", ny), 1, ny)
        assertEquals(3, back.days)
    }

    @Test
    fun `month end, leap day and new year`() {
        assertEquals(LocalDate.of(2028, 2, 29), usageSpan(at("2028-02-28T20:30:00"), at("2028-02-29T20:30:00"), 0, india).firstDay)
        assertEquals(LocalDate.of(2028, 3, 1), usageSpan(at("2028-02-29T20:30:00"), at("2028-03-01T20:30:00"), 0, india).firstDay)
        val year = usageSpan(at("2026-12-30T20:30:00"), at("2027-01-02T20:30:00"), 0, india)
        assertEquals(LocalDate.of(2026, 12, 31), year.firstDay)
        assertEquals(LocalDate.of(2027, 1, 2), year.lastDay)
        val leap = usageSpan(at("2028-02-27T20:30:00"), at("2028-03-02T20:30:00"), 0, india)
        assertEquals(4, leap.days)
        assertEquals(LocalDate.of(2028, 3, 2), leap.lastDay)
    }

    // --- Verdicts built from real-phone history ---

    private fun spansOf(h: com.aasif.watchdog.core.usage.UsageHistory) =
        (h.dailies + h.heads).map { usageSpan(it.startMillis, it.endMillis, it.millis, india) }

    private val today = LocalDate.of(2026, 10, 16)

    @Test
    fun `a monthly plan started 18 Sep is fully covered on the day the app is installed`() {
        val v = usageVerdict(spansOf(residualSpans(realYoutube, now)), LocalDate.of(2026, 9, 18), today, UsageCategory.VIDEO)
        // YouTube is far above the 60 minute floor.
        assertEquals(UsageVerdict.Ok, v)
        val quiet = realYoutube.map { it.copy(millis = it.millis / 10) } // a tenth of the use
        val low = usageVerdict(spansOf(residualSpans(quiet, now)), LocalDate.of(2026, 9, 18), today, UsageCategory.VIDEO)
        assertIs<UsageVerdict.Low>(low)
        assertEquals(false, low.partial)
        assertEquals(LocalDate.of(2026, 9, 18), low.from)
    }

    @Test
    fun `a quarterly plan started 18 Aug adds the monthly bucket`() {
        val quiet = realYoutube.map { it.copy(millis = 0) }
        val v = usageVerdict(spansOf(residualSpans(quiet, now)), LocalDate.of(2026, 8, 18), today, UsageCategory.VIDEO)
        assertIs<UsageVerdict.Unused>(v)
        assertEquals(LocalDate.of(2026, 8, 19), v.from) // the monthly bucket began 18 Aug evening
        assertEquals(false, v.partial)
    }

    @Test
    fun `a yearly plan that began before the phone kept anything says it is partial`() {
        val quiet = realYoutube.map { it.copy(millis = 0) }
        val v = usageVerdict(spansOf(residualSpans(quiet, now)), LocalDate.of(2025, 10, 16), today, UsageCategory.VIDEO)
        assertIs<UsageVerdict.Unused>(v)
        // The 2025 yearly bucket began before the plan, so it is left out; the 2026 one starts 24 Feb.
        assertEquals(LocalDate.of(2026, 2, 24), v.from)
        assertEquals(true, v.partial)
    }

    @Test
    fun `an overlapping coarse span never counts a day twice`() {
        val day1 = LocalDate.of(2026, 10, 1)
        val spans = (0 until 20).map { UsageSpan(day1.plusDays(it.toLong()), 1, 60_000) } + UsageSpan(day1.plusDays(5), 7, 500 * 60_000)
        val v = usageVerdict(spans, day1, LocalDate.of(2026, 10, 25), UsageCategory.VIDEO)
        // 20 days x 1 minute = 20 < the floor scaled to 20 days (40): low, and the 500 minutes were not added.
        assertEquals(UsageVerdict.Low(20, day1, day1.plusDays(19), partial = true), v)
    }

    @Test
    fun `a span that starts before the period is left out`() {
        val renewal = LocalDate.of(2026, 10, 10)
        val spans = (0 until 14).map { UsageSpan(renewal.plusDays(1 + it.toLong()), 1, 60_000) } + UsageSpan(renewal.minusDays(20), 30, 9999 * 60_000L)
        val v = usageVerdict(spans, renewal, LocalDate.of(2026, 10, 30), UsageCategory.VIDEO)
        assertIs<UsageVerdict.Low>(v)
        assertEquals(14, v.minutes)
    }
}
