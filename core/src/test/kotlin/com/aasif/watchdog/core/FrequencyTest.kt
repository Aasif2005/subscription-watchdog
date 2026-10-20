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
    fun `an anchor decades back lands on the right next date`() {
        val anchor = d("1980-10-20")
        val today = d("2026-10-20")
        assertEquals(today, Frequency.MONTHLY.nextOnOrAfter(anchor, today))
        assertEquals(today, Frequency.BIMONTHLY.nextOnOrAfter(anchor, today))
        assertEquals(d("2026-12-20"), Frequency.BIMONTHLY.nextOnOrAfter(anchor, d("2026-10-21")))
        assertEquals(today, Frequency.QUARTERLY.nextOnOrAfter(anchor, today))
        assertEquals(today, Frequency.YEARLY.nextOnOrAfter(anchor, today))
        assertEquals(d("2026-11-20"), Frequency.MONTHLY.nextOnOrAfter(anchor, d("2026-10-21")))
    }

    @Test
    fun `a month-end anchor a century back keeps its day`() {
        assertEquals(d("2026-02-28"), Frequency.MONTHLY.nextOnOrAfter(d("1926-01-31"), d("2026-02-01")))
        assertEquals(d("2026-03-31"), Frequency.MONTHLY.nextOnOrAfter(d("1926-01-31"), d("2026-03-01")))
    }

    @Test
    fun `gap matching`() {
        assertEquals(Frequency.MONTHLY, Frequency.fromGap(28))
        assertEquals(Frequency.MONTHLY, Frequency.fromGap(31))
        assertEquals(Frequency.YEARLY, Frequency.fromGap(366))
        assertEquals(null, Frequency.fromGap(45))
    }
}
