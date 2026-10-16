package com.aasif.watchdog.data.usage

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.aasif.watchdog.core.usage.BucketLevel
import com.aasif.watchdog.core.usage.UsageBucket
import com.aasif.watchdog.core.usage.UsageHistory
import com.aasif.watchdog.core.usage.residualSpans

/**
 * Reads how long chosen apps were on screen from Android's usage statistics: day by day, and for the older time the
 * weekly, monthly and yearly totals the phone still holds. Only the package names the caller passes in have their time
 * read; Android's answer also names other apps, and from those only the start of the bucket is used (to know the
 * bucket exists), never their time or name.
 */
class UsageReader(private val context: Context) {
    /** Usage access is a special permission the user switches on in Settings; there is no dialog and no callback. */
    fun hasAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Which of [packages] are on this phone, and the moment each was first installed. */
    fun installedAt(packages: Collection<String>): Map<String, Long> = packages.mapNotNull { pkg ->
        try {
            @Suppress("DEPRECATION")
            pkg to context.packageManager.getPackageInfo(pkg, 0).firstInstallTime
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }.toMap()

    /**
     * Everything Android still holds for [packages], level by level (daily, weekly, monthly, yearly), turned into finished
     * days and the leftover start of each coarser bucket by [residualSpans]. Which buckets exist is learned from every
     * package's entries, so a package that is absent from an existing bucket reads as zero use; only the timestamps of
     * other apps are used, never their time. A bucket longer than its level allows is Android answering a finer query
     * with a coarser bucket it no longer has a finer one for, and is not used at that level.
     */
    fun history(packages: Collection<String>, nowMillis: Long): Map<String, UsageHistory> {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyMap()
        val wanted = packages.toSet()
        val since = nowMillis - LOOKBACK_MILLIS
        val buckets = HashMap<String, MutableList<UsageBucket>>()
        for ((level, interval) in LEVELS) {
            val stats = manager.queryUsageStats(interval, since, nowMillis).orEmpty()
                .filter { it.lastTimeStamp - it.firstTimeStamp <= level.nominalMillis * LENGTH_SLACK }
            val exist = stats.map { it.firstTimeStamp }.toSortedSet()
            val millis = HashMap<Pair<String, Long>, Long>()
            for (stat in stats) if (stat.packageName in wanted) millis.merge(stat.packageName to stat.firstTimeStamp, stat.totalTimeInForeground, Long::plus)
            for (pkg in wanted) buckets.getOrPut(pkg) { ArrayList() } += exist.map { UsageBucket(level, it, millis[pkg to it] ?: 0L) }
        }
        return buckets.mapValues { (_, list) -> residualSpans(list, nowMillis) }
    }

    private companion object {

        /** Far enough back to reach the oldest yearly bucket a phone keeps. */
        const val LOOKBACK_MILLIS = 800L * 24 * 60 * 60 * 1000

        /** A bucket of a level may run a little over its nominal length (daylight saving, a long month). */
        const val LENGTH_SLACK = 1.25

        val LEVELS = listOf(
            BucketLevel.DAILY to UsageStatsManager.INTERVAL_DAILY,
            BucketLevel.WEEKLY to UsageStatsManager.INTERVAL_WEEKLY,
            BucketLevel.MONTHLY to UsageStatsManager.INTERVAL_MONTHLY,
            BucketLevel.YEARLY to UsageStatsManager.INTERVAL_YEARLY,
        )
    }
}
