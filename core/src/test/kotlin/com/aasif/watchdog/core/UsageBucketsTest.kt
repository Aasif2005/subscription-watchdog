package com.aasif.watchdog.core

import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.usage.isCompleteBucket
import com.aasif.watchdog.core.usage.usageDayLabel
import com.aasif.watchdog.core.usage.usageKeepDays
import com.aasif.watchdog.core.usage.usageRetentionDays
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsageBucketsTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun millis(y: Int, m: Int, d: Int, h: Int, min: Int, zone: ZoneId = ist) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `a bucket that starts in the evening belongs to the day that holds most of it`() {
        // 20:57 on the 14th to 20:57 on the 15th: three hours of the 14th, twenty-one of the 15th.
        assertEquals(LocalDate.of(2026, 10, 15), usageDayLabel(millis(2026, 10, 14, 20, 57), ist))
        assertEquals(LocalDate.of(2026, 10, 10), usageDayLabel(millis(2026, 10, 9, 20, 45), ist))
    }

    @Test
    fun `a midnight to midnight bucket keeps its own date`() {
        assertEquals(LocalDate.of(2026, 10, 14), usageDayLabel(millis(2026, 10, 14, 0, 0), ist))
    }

    @Test
    fun `a bucket across a daylight saving change still lands on one day`() {
        val ny = ZoneId.of("America/New_York")
        // 8 March 2026 is a 23-hour day; midnight to midnight is still the 8th.
        assertEquals(LocalDate.of(2026, 3, 8), usageDayLabel(millis(2026, 3, 8, 0, 0, ny), ny))
        // 1 November 2026 is a 25-hour day.
        assertEquals(LocalDate.of(2026, 11, 1), usageDayLabel(millis(2026, 11, 1, 0, 0, ny), ny))
    }

    @Test
    fun `the bucket in progress is not final until a day after it began`() {
        val start = millis(2026, 10, 15, 20, 57)
        assertFalse(isCompleteBucket(start, millis(2026, 10, 16, 19, 4)))
        assertTrue(isCompleteBucket(start, millis(2026, 10, 16, 20, 57)))
        assertTrue(isCompleteBucket(start, millis(2026, 10, 17, 8, 0)))
    }

    @Test
    fun `rows are kept for a billing period plus a margin`() {
        assertEquals(450, usageKeepDays(listOf(Frequency.YEARLY.approxDays)))
        assertEquals(115, usageKeepDays(listOf(Frequency.MONTHLY.approxDays)))
        assertEquals(176, usageKeepDays(listOf(Frequency.QUARTERLY.approxDays)))
        // The longest plan decides.
        assertEquals(450, usageKeepDays(listOf(Frequency.MONTHLY.approxDays, Frequency.YEARLY.approxDays)))
        // Nothing tracked yet: a modest default.
        assertEquals(120, usageKeepDays(emptyList()))
    }

    @Test
    fun `one retention reaches the oldest period start of any subscription and never drops below the span floor`() {
        val monthly = Frequency.MONTHLY.approxDays
        // A monthly plan taken five days ago alone still keeps what the yearly spans need.
        assertEquals(450, usageRetentionDays(listOf(monthly), listOf(5)))
        // A second plan taken 349 days ago (ChatGPT Go, first debit a year after the receipt) is covered too: 349 + 85 margin is under the floor.
        assertEquals(450, usageRetentionDays(listOf(monthly, monthly), listOf(5, 349)))
        // The oldest start across plans decides once it passes the floor, whichever plan it belongs to.
        assertEquals(500 + 85, usageRetentionDays(listOf(monthly, monthly), listOf(500, 5)))
        assertEquals(500 + 85, usageRetentionDays(listOf(monthly, monthly), listOf(5, 500)))
        // Nothing tracked yet.
        assertEquals(450, usageRetentionDays(emptyList(), emptyList()))
    }
}
