package com.aasif.watchdog.core

import com.aasif.watchdog.core.usage.UsageVerdict
import com.aasif.watchdog.core.usage.formatUsageMinutes
import com.aasif.watchdog.core.usage.usageHintText
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageHintTest {
    private val from = LocalDate.of(2026, 4, 20)
    private val to = LocalDate.of(2026, 10, 15)
    private fun d(date: LocalDate) = "${date.dayOfMonth} ${date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}"

    @Test
    fun `a full period keeps the plain wording`() {
        assertEquals(
            "Since 20 Apr we saw 12 min of Netflix on this phone. Do you want it for next month?",
            usageHintText("Netflix", "month", UsageVerdict.Low(12, from, to), ::d),
        )
        assertEquals(
            "We saw no use of Netflix on this phone from 20 Apr to 15 Oct. Do you want it for next month?",
            usageHintText("Netflix", "month", UsageVerdict.Unused(from, to), ::d),
        )
    }

    @Test
    fun `a partial period names the dates seen and says earlier use was not available`() {
        val low = usageHintText("Netflix", "year", UsageVerdict.Low(95, from, to, partial = true), ::d)!!
        assertEquals(
            "From 20 Apr to 15 Oct we saw 1 h 35 min of Netflix on this phone. " +
                "Use before 20 Apr wasn't available, so this may not reflect the whole year. Do you want it for next year?",
            low,
        )
        val none = usageHintText("Netflix", "year", UsageVerdict.Unused(from, to, partial = true), ::d)!!
        assertTrue("from 20 Apr to 15 Oct" in none)
        assertTrue("Use before 20 Apr wasn't available" in none)
        assertTrue("may not cover the whole year" in none)
    }

    @Test
    fun `no hint when there is nothing low to say`() {
        assertNull(usageHintText("Netflix", "month", UsageVerdict.Ok, ::d))
        assertNull(usageHintText("Netflix", "month", UsageVerdict.NoData, ::d))
        assertFalse("wasn't available" in usageHintText("Netflix", "month", UsageVerdict.Low(5, from, to), ::d)!!)
    }

    @Test
    fun `minutes read naturally`() {
        assertEquals("under a minute", formatUsageMinutes(0))
        assertEquals("45 min", formatUsageMinutes(45))
        assertEquals("2 h", formatUsageMinutes(120))
        assertEquals("1 h 5 min", formatUsageMinutes(65))
    }
}
