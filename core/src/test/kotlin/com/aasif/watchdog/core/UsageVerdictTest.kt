package com.aasif.watchdog.core

import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.usage.TrackedApps
import com.aasif.watchdog.core.usage.UsageCategory
import com.aasif.watchdog.core.usage.UsageVerdict
import com.aasif.watchdog.core.usage.UsageSpan
import com.aasif.watchdog.core.usage.usageVerdict
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
    fun `tracked apps are video and AI apps, never audio`() {
        assertNull(TrackedApps.forMerchant("spotify"))
        assertNull(TrackedApps.forMerchant("applemusic"))
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
}
