package com.aasif.watchdog.core

import com.aasif.watchdog.core.engine.BACKFILL_COMPLETE
import com.aasif.watchdog.core.engine.BACKFILL_NOT_STARTED
import com.aasif.watchdog.core.engine.backfillFraction
import com.aasif.watchdog.core.engine.combinedBackfillFraction
import com.aasif.watchdog.core.engine.daysUntil
import com.aasif.watchdog.core.engine.renewalProgress
import com.aasif.watchdog.core.model.Frequency
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class WeekAheadTest {
    private val today = LocalDate.of(2026, 10, 20)

    @Test
    fun `days until is signed`() {
        assertEquals(3, daysUntil(today.plusDays(3), today))
        assertEquals(-2, daysUntil(today.minusDays(2), today))
    }

    private val now = 1_800_000_000_000L
    private fun days(n: Long) = TimeUnit.DAYS.toMillis(n)

    @Test
    fun `an import that has not started is at zero and a finished one at one`() {
        assertEquals(0f, backfillFraction(now, BACKFILL_NOT_STARTED, 400))
        assertEquals(1f, backfillFraction(now, BACKFILL_COMPLETE, 400))
    }

    @Test
    fun `the fraction is the share of the window between now and the frontier`() {
        assertEquals(0.25f, backfillFraction(now, now - days(100), 400), 0.0001f)
        assertEquals(0.5f, backfillFraction(now, now - days(200), 400), 0.0001f)
    }

    @Test
    fun `a frontier beyond the window or in the future is clamped`() {
        assertEquals(1f, backfillFraction(now, now - days(900), 400))
        assertEquals(0f, backfillFraction(now, now + days(5), 400))
    }

    @Test
    fun `accounts are averaged and none is zero`() {
        assertEquals(0.5f, combinedBackfillFraction(listOf(1f, 0f)), 0.0001f)
        assertEquals(0f, combinedBackfillFraction(emptyList()))
    }

    @Test
    fun `renewal progress counts the days of the period already gone`() {
        val next = LocalDate.of(2026, 10, 22)
        val progress = renewalProgress(next, Frequency.MONTHLY, LocalDate.of(2026, 10, 20))!!
        assertEquals(30, progress.length)
        assertEquals(28, progress.elapsed)
        assertEquals(28f / 30f, progress.fraction, 0.0001f)
    }

    @Test
    fun `renewal progress is clamped at both ends of the period`() {
        val next = LocalDate.of(2026, 10, 22)
        assertEquals(0, renewalProgress(next, Frequency.MONTHLY, LocalDate.of(2026, 8, 1))!!.elapsed)
        val late = renewalProgress(next, Frequency.MONTHLY, LocalDate.of(2026, 10, 30))!!
        assertEquals(late.length, late.elapsed)
        assertEquals(1f, late.fraction)
    }

    @Test
    fun `a charge with no schedule has no progress`() {
        assertEquals(null, renewalProgress(LocalDate.of(2026, 10, 22), Frequency.AS_PRESENTED, LocalDate.of(2026, 10, 20)))
    }
}
