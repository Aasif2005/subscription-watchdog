package com.aasif.watchdog.core.usage

import java.time.LocalDate

/** "12 min", "1 h 5 min"; a total under a minute is said so rather than shown as 0. */
fun formatUsageMinutes(minutes: Long): String = when {
    minutes < 1 -> "under a minute"
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0L -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

/** The one place that says what a figure is about: this device only, so a show on a TV or another phone is not in it. */
const val ON_THIS_DEVICE = "on this device"

/**
 * "Screen time on this device since 10 Oct: 3 h 20 min"; "Playing time" for a music app. It names the first day counted, so the figure can
 * be read against the plan's own period, and says when the days before that were not available.
 */
fun usageLineText(summary: UsageSummary, date: (LocalDate) -> String): String {
    val what = if (summary.playback) "Playing time" else "Screen time"
    val gap = if (summary.partial) " (earlier days weren't available)" else ""
    return "$what $ON_THIS_DEVICE since ${date(summary.from)}: ${formatUsageMinutes(summary.minutes)}$gap"
}

/** Shown until a full day of the new period has passed: where counting starts, rather than a number from the last period. */
fun usageTrackingText(start: LocalDate, date: (LocalDate) -> String): String =
    "Tracking use $ON_THIS_DEVICE since ${date(start)}. The first figure appears once a full day has passed."

/** What the figure leaves out, for a small note under it. */
fun usageNoteText(playback: Boolean): String =
    (if (playback) "Counts time the app was playing" else "Counts time the app was open") +
        " on this device only. Use on a TV, a computer or another phone is not included."

/**
 * The sentence for a renewal reminder, or null when there is nothing to say. Always says "on this device": a show watched
 * on a TV does not show up. When Android no longer held the start of the period ([UsageVerdict.Low.partial]) it names the
 * days that were seen and says the earlier use was not available, so the result is not taken for the whole [period].
 */
fun usageHintText(name: String, period: String, verdict: UsageVerdict, date: (LocalDate) -> String, playback: Boolean = false): String? {
    val ask = "Do you want it for next $period?"
    val use = if (playback) "$name playing" else name
    return when (verdict) {
        is UsageVerdict.Low ->
            if (verdict.partial) {
                "From ${date(verdict.from)} to ${date(verdict.to)} we saw ${formatUsageMinutes(verdict.minutes)} of $use $ON_THIS_DEVICE. " +
                    "Use before ${date(verdict.from)} wasn't available, so this may not reflect the whole $period. $ask"
            } else {
                "Since ${date(verdict.from)} we saw ${formatUsageMinutes(verdict.minutes)} of $use $ON_THIS_DEVICE. $ask"
            }
        is UsageVerdict.Unused ->
            if (verdict.partial) {
                "We saw no use of $use $ON_THIS_DEVICE from ${date(verdict.from)} to ${date(verdict.to)}. " +
                    "Use before ${date(verdict.from)} wasn't available, so this may not cover the whole $period. $ask"
            } else {
                "We saw no use of $use $ON_THIS_DEVICE from ${date(verdict.from)} to ${date(verdict.to)}. $ask"
            }
        UsageVerdict.Ok, UsageVerdict.NoData -> null
    }
}
