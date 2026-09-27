package com.aasif.watchdog.core

import com.aasif.watchdog.core.model.Frequency
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class FrequencyTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun `monthly keeps anchor day through short months`() {
        val anchor = d("2026-01-31")
        assertEquals(d("2026-02-28"), Frequency.MONTHLY.nextOnOrAfter(anchor, d("2026-02-01")))
        assertEquals(d("2026-03-31"), Frequency.MONTHLY.nextOnOrAfter(anchor, d("2026-03-01")))
    }

    @Test
    fun `yearly from leap day`() {
        assertEquals(d("2029-02-28"), Frequency.YEARLY.nextOnOrAfter(d("2028-02-29"), d("2029-01-01")))
    }

    @Test
    fun `far-away target is reached exactly`() {
        assertEquals(d("2031-09-05"), Frequency.MONTHLY.nextOnOrAfter(d("2020-01-05"), d("2031-08-06")))
        assertEquals(d("2026-09-29"), Frequency.WEEKLY.nextOnOrAfter(d("2026-01-06"), d("2026-09-24")))
    }

    @Test
    fun `gap matching`() {
        assertEquals(Frequency.MONTHLY, Frequency.fromGap(28))
        assertEquals(Frequency.MONTHLY, Frequency.fromGap(31))
        assertEquals(Frequency.YEARLY, Frequency.fromGap(366))
        assertEquals(null, Frequency.fromGap(45))
    }
}
