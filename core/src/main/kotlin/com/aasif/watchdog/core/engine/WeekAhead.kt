package com.aasif.watchdog.core.engine

import com.aasif.watchdog.core.model.Frequency
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Whole days from [today] to [date]; negative when [date] has passed. */
fun daysUntil(date: LocalDate, today: LocalDate): Int = ChronoUnit.DAYS.between(today, date).toInt()

/** How far through the current billing period a renewal on [next] is: [elapsed] of [length] days gone. */
data class RenewalProgress(val elapsed: Int, val length: Int) {
    val fraction: Float get() = if (length <= 0) 0f else (elapsed.toFloat() / length).coerceIn(0f, 1f)
}

/**
 * The period that ends on [next], and how much of it has passed by [today]. Null for a charge with no fixed schedule, where
 * "how far through" has no meaning. A date already past reads as a full period.
 */
fun renewalProgress(next: LocalDate, frequency: Frequency, today: LocalDate): RenewalProgress? {
    if (!frequency.hasSchedule) return null
    val start = frequency.previous(next)
    val length = ChronoUnit.DAYS.between(start, next).toInt()
    if (length <= 0) return null
    return RenewalProgress(ChronoUnit.DAYS.between(start, today).toInt().coerceIn(0, length), length)
}
