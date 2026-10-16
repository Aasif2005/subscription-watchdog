package com.aasif.watchdog.core.usage

/**
 * Android keeps screen time at four levels, each level covering the same time as the finer ones but for longer back:
 * about 10 daily, 4 weekly, 6 monthly and 3 yearly buckets on a Samsung phone. The oldest bucket of a level is dropped first,
 * so the finer buckets always cover the end of a coarser one, and what is left of the coarser one is its start.
 */
enum class BucketLevel(val nominalMillis: Long) {
    DAILY(DAY_MILLIS),
    WEEKLY(7 * DAY_MILLIS),
    MONTHLY(30 * DAY_MILLIS),
    YEARLY(365 * DAY_MILLIS),
}

/**
 * One bucket as Android keeps it, seen for one package: when it began and how long the package was in the foreground
 * during it. A package with no entry in a bucket that exists has [millis] 0, which is a real zero.
 */
data class UsageBucket(val level: BucketLevel, val startMillis: Long, val millis: Long)

/** Foreground time over a stretch of real time. */
data class HistorySpan(val startMillis: Long, val endMillis: Long, val millis: Long)

/**
 * What the buckets say about one package: [dailies] are the finished daily buckets, [heads] the start of each coarser bucket
 * that no finer bucket covers any more. Together they never overlap.
 */
data class UsageHistory(val dailies: List<HistorySpan>, val heads: List<HistorySpan>)

/** A bucket boundary may be off by a few hours between levels (Android starts each level at its own moment). */
private const val EDGE_TOLERANCE_MILLIS = 3 * HOUR_MILLIS

/** A leftover shorter than this is not worth a span. */
private const val MIN_HEAD_MILLIS = 6 * HOUR_MILLIS

/** A subtraction may come out this far below zero from rounding before the bucket is judged inconsistent. */
private const val ROUNDING_MILLIS = 60_000L

/** A bucket longer than its nominal length by this factor is not one bucket of that level. */
private const val LENGTH_SLACK = 1.25

private class Placed(val level: BucketLevel, val start: Long, val end: Long, val millis: Long)

/**
 * Works out, for one package, the finished daily buckets and the part of each weekly, monthly and yearly bucket that
 * the finer buckets do not cover. That leftover is the bucket's total minus the totals of the finer buckets inside it,
 * and it covers the time from the bucket's start to where the finer buckets begin. Whenever the pieces do not fit
 * (a gap between finer buckets, a finer bucket sticking out of the coarser one, a leftover below zero) the coarse
 * bucket gives nothing: unknown stays unknown.
 *
 * A bucket's end is where the next one of its level begins; the newest bucket of a level is still running and ends at [nowMillis].
 */
fun residualSpans(buckets: List<UsageBucket>, nowMillis: Long): UsageHistory {
    val byLevel = BucketLevel.entries.associateWith { level ->
        place(level, buckets.filter { it.level == level }, nowMillis)
    }
    val dailies = byLevel.getValue(BucketLevel.DAILY)
        .filter { isCompleteBucket(it.start, nowMillis) }
        .map { HistorySpan(it.start, it.end, it.millis) }
    val heads = BucketLevel.entries.filter { it != BucketLevel.DAILY }.flatMap { level ->
        val finer = BucketLevel.entries.filter { it.ordinal < level.ordinal }.reversed().map { byLevel.getValue(it) }
        byLevel.getValue(level).mapNotNull { head(it, finer) }
    }
    return UsageHistory(dailies, heads)
}

private fun place(level: BucketLevel, buckets: List<UsageBucket>, nowMillis: Long): List<Placed> {
    val sorted = buckets.groupBy { it.startMillis }.map { (start, same) -> UsageBucket(level, start, same.sumOf { it.millis }) }
        .sortedBy { it.startMillis }
    return sorted.mapIndexed { i, b ->
        val cap = b.startMillis + (level.nominalMillis * LENGTH_SLACK).toLong()
        val end = minOf(sorted.getOrNull(i + 1)?.startMillis ?: nowMillis, cap)
        Placed(level, b.startMillis, end, b.millis)
    }.filter { it.end > it.start }
}

/** The part of [bucket] that no finer bucket covers, or null when there is none or it cannot be told reliably. */
private fun head(bucket: Placed, finerLevels: List<List<Placed>>): HistorySpan? {
    // The coarsest finer level that has anything inside this bucket is the one to subtract.
    var inside: List<Placed> = emptyList()
    for (level in finerLevels) {
        inside = level.filter { it.end > bucket.start + EDGE_TOLERANCE_MILLIS && it.start < bucket.end - EDGE_TOLERANCE_MILLIS }
        if (inside.isNotEmpty()) break
    }
    if (inside.isEmpty()) {
        return HistorySpan(bucket.start, bucket.end, bucket.millis).takeIf { it.endMillis - it.startMillis >= MIN_HEAD_MILLIS }
    }
    val first = inside.first()
    if (first.start <= bucket.start + EDGE_TOLERANCE_MILLIS) return null // finer buckets cover it all
    if (!chained(inside)) return null
    // The finer buckets must end where this one does, and none may reach out of it.
    if (kotlin.math.abs(inside.last().end - bucket.end) > EDGE_TOLERANCE_MILLIS) return null
    val millis = bucket.millis - inside.sumOf { it.millis }
    if (millis < -ROUNDING_MILLIS) return null
    val span = HistorySpan(bucket.start, first.start, millis.coerceAtLeast(0))
    if (span.endMillis - span.startMillis < MIN_HEAD_MILLIS) return null
    // A still finer bucket inside the leftover (kept longer than the level above it) would be counted twice.
    val overlapped = finerLevels.flatten().any {
        it.end > span.startMillis + EDGE_TOLERANCE_MILLIS && it.start < span.endMillis - EDGE_TOLERANCE_MILLIS
    }
    return span.takeIf { !overlapped }
}

/** Each bucket begins where the one before it ended. */
private fun chained(buckets: List<Placed>): Boolean =
    buckets.zipWithNext().all { (a, b) -> b.start - a.end <= EDGE_TOLERANCE_MILLIS }
