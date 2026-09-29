package com.aasif.watchdog.core

import com.aasif.watchdog.core.gmail.GmailQueryBuilder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GmailQueryBuilderTest {
    private val query = GmailQueryBuilder.build(afterEpochSeconds = 1_759_000_000L)

    @Test
    fun `search terms are one OR group so the date and folder filters apply to all of them`() {
        assertTrue(query.startsWith("("), query)
        assertTrue(query.contains(") after:"), query)
    }

    @Test
    fun `the cursor is passed through as an epoch bound`() {
        assertTrue(query.contains("after:1759000000"), query)
    }

    @Test
    fun `spam and trash are excluded`() {
        assertTrue(query.contains("-in:spam"), query)
        assertTrue(query.contains("-in:trash"), query)
    }

    @Test
    fun `promotions are not excluded, since Gmail files some real receipts there`() {
        assertFalse(query.contains("category:promotions"), query)
    }

    @Test
    fun `covers the words receipts actually use`() {
        listOf("subscription", "autopay", "mandate", "renewal", "receipt", "invoice", "debited")
            .forEach { assertTrue(query.contains(it), "missing '$it' in: $query") }
    }

    @Test
    fun `multi-word terms are quoted so they match as phrases`() {
        assertTrue(query.contains("\"auto pay\""), query)
        assertTrue(query.contains("\"payment successful\""), query)
    }

    @Test
    fun `merchant names are absent, keeping the result count down`() {
        assertFalse(query.contains("Netflix", ignoreCase = true), query)
        assertFalse(query.contains("Spotify", ignoreCase = true), query)
    }

    @Test
    fun `no upper bound is set for an ordinary catch-up run`() {
        assertFalse(query.contains("before:"), query)
    }

    @Test
    fun `the backfill bounds both ends so it can walk backwards a slice at a time`() {
        val slice = GmailQueryBuilder.build(afterEpochSeconds = 1_725_000_000L, beforeEpochSeconds = 1_759_000_000L)
        assertTrue(slice.contains("after:1725000000"), slice)
        assertTrue(slice.contains("before:1759000000"), slice)
    }
}
