package com.aasif.watchdog.core.usage

import com.aasif.watchdog.core.model.Frequency
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal const val HOUR_MILLIS = 60L * 60 * 1000
internal const val DAY_MILLIS = 24 * HOUR_MILLIS

/**
 * Android's daily usage buckets are 24 hours long but not always midnight to midnight: on a Samsung phone they were
 * seen starting at about 20:45 and rolling every 24 hours. The day a bucket belongs to is the one that holds most of
 * its hours, which is the date of its midpoint; a bucket from 20:57 on the 14th to 20:57 on the 15th is the 15th.
 */
fun usageDayLabel(bucketStartMillis: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(bucketStartMillis + 12 * HOUR_MILLIS).atZone(zone).toLocalDate()

/** A bucket still in progress holds a partial day; only one that began at least 24 hours ago is final. */
fun isCompleteBucket(bucketStartMillis: Long, nowMillis: Long): Boolean = bucketStartMillis + 24 * HOUR_MILLIS <= nowMillis

/** Extra days kept beyond one billing period, so a late renewal or a missed run does not cut into the window. */
private const val KEEP_MARGIN_DAYS = 85L

/** Kept when no subscription with a tracked app is known yet, so one added later still has a little history. */
private const val DEFAULT_KEEP_DAYS = 120L

/**
 * How many days of daily rows to keep: the longest billing period among the subscriptions whose app is tracked, plus a
 * margin (yearly 450, quarterly about 176, monthly about 115).
 */
fun usageKeepDays(periodDays: Collection<Int>): Long =
    periodDays.maxOrNull()?.let { it + KEEP_MARGIN_DAYS } ?: DEFAULT_KEEP_DAYS

/**
 * How far back to keep usage, for the daily rows and the weekly, monthly and yearly spans alike, so one cannot be deleted while the other
 * is still needed. It reaches the oldest period start among the tracked subscriptions ([periodAges], days from each start to today: a plan
 * taken long before its first debit is counted from the day it was taken) as well as their billing lengths ([periodDays]), and is never
 * under a yearly plan's, because the spans are taken once per app and cannot be read again once deleted.
 */
fun usageRetentionDays(periodDays: Collection<Int>, periodAges: Collection<Int>): Long =
    maxOf(usageKeepDays(periodDays + periodAges), usageKeepDays(listOf(Frequency.YEARLY.approxDays)))
