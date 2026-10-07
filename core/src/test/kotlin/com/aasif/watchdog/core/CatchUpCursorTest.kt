package com.aasif.watchdog.core

import com.aasif.watchdog.core.gmail.CatchUpCursor
import com.aasif.watchdog.core.gmail.CatchUpStep
import com.aasif.watchdog.core.gmail.next
import kotlin.test.Test
import kotlin.test.assertEquals

class CatchUpCursorTest {
    private val lastSync = 1_000_000L
    private val now = 2_000_000L

    @Test
    fun `a window read in one go moves the cursor to now`() {
        assertEquals(CatchUpStep.Done(now), CatchUpCursor(lastSync).next(now, exhausted = true, oldestScanned = 1_500_000L))
    }

    @Test
    fun `a throttled or capped read continues below the oldest message it saw`() {
        assertEquals(
            CatchUpStep.Continue(upperBound = 1_600_000L, target = now),
            CatchUpCursor(lastSync).next(now, exhausted = false, oldestScanned = 1_600_000L),
        )
    }

    @Test
    fun `later runs keep the original target, so mail that arrived meanwhile is read next time`() {
        val cursor = CatchUpCursor(lastSync, upperBound = 1_600_000L, target = now)
        val later = now + 500_000L
        assertEquals(CatchUpStep.Continue(1_300_000L, now), cursor.next(later, exhausted = false, oldestScanned = 1_300_000L))
        assertEquals(CatchUpStep.Done(now), cursor.next(later, exhausted = true, oldestScanned = 1_300_000L))
    }

    @Test
    fun `the bound always moves back, even when Gmail returns the same oldest message again`() {
        val cursor = CatchUpCursor(lastSync, upperBound = 1_600_000L, target = now)
        assertEquals(CatchUpStep.Continue(1_599_000L, now), cursor.next(now, exhausted = false, oldestScanned = 1_600_000L))
    }

    @Test
    fun `reaching the last sync ends the walk`() {
        assertEquals(CatchUpStep.Done(now), CatchUpCursor(lastSync).next(now, exhausted = false, oldestScanned = lastSync))
    }

    @Test
    fun `a read that looked at nothing ends rather than looping`() {
        assertEquals(CatchUpStep.Done(now), CatchUpCursor(lastSync).next(now, exhausted = false, oldestScanned = null))
    }
}
