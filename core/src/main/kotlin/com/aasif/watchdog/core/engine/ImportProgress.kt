package com.aasif.watchdog.core.engine

import java.util.concurrent.TimeUnit

/** Frontier value meaning an account's whole look-back window has been read. */
const val BACKFILL_COMPLETE = 0L

/** Frontier value meaning an account's import has not started. */
const val BACKFILL_NOT_STARTED = -1L

/**
 * How much of one account's look-back window has been read, from 0 to 1.
 *
 * The Gmail import reads newest mail first and moves a "frontier" back to the oldest message it saw, so the share of the
 * window between now and the frontier is the share already covered. It counts time, not messages: busy months read slower
 * than quiet ones, but it only ever moves forward, which a count against a rough estimate does not.
 */
fun backfillFraction(nowMillis: Long, frontierMillis: Long, windowDays: Long): Float {
    if (frontierMillis == BACKFILL_COMPLETE) return 1f
    if (frontierMillis < 0 || windowDays <= 0) return 0f
    val window = TimeUnit.DAYS.toMillis(windowDays)
    return ((nowMillis - frontierMillis).toFloat() / window).coerceIn(0f, 1f)
}

/** Several accounts import side by side; the strip shows their mean, and 1 only when every one is finished. */
fun combinedBackfillFraction(fractions: List<Float>): Float =
    if (fractions.isEmpty()) 0f else fractions.average().toFloat().coerceIn(0f, 1f)
