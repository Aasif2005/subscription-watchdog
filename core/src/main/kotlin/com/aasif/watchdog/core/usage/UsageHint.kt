package com.aasif.watchdog.core.usage

import java.time.LocalDate

/** "12 min", "1 h 5 min"; a total under a minute is said so rather than shown as 0. */
fun formatUsageMinutes(minutes: Long): String = when {
    minutes < 1 -> "under a minute"
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0L -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

/**
 * The sentence for a renewal reminder, or null when there is nothing to say. Always says "on this phone": a show watched
 * on a TV does not show up. When Android no longer held the start of the period ([UsageVerdict.Low.partial]) it names the
 * days that were seen and says the earlier use was not available, so the result is not taken for the whole [period].
 */
fun usageHintText(name: String, period: String, verdict: UsageVerdict, date: (LocalDate) -> String): String? {
    val ask = "Do you want it for next $period?"
    return when (verdict) {
        is UsageVerdict.Low ->
            if (verdict.partial) {
                "From ${date(verdict.from)} to ${date(verdict.to)} we saw ${formatUsageMinutes(verdict.minutes)} of $name on this phone. " +
                    "Use before ${date(verdict.from)} wasn't available, so this may not reflect the whole $period. $ask"
            } else {
                "Since ${date(verdict.from)} we saw ${formatUsageMinutes(verdict.minutes)} of $name on this phone. $ask"
            }
        is UsageVerdict.Unused ->
            if (verdict.partial) {
                "We saw no use of $name on this phone from ${date(verdict.from)} to ${date(verdict.to)}. " +
                    "Use before ${date(verdict.from)} wasn't available, so this may not cover the whole $period. $ask"
            } else {
                "We saw no use of $name on this phone from ${date(verdict.from)} to ${date(verdict.to)}. $ask"
            }
        UsageVerdict.Ok, UsageVerdict.NoData -> null
    }
}
