package com.aasif.watchdog.core.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * How often a recurring charge repeats.
 *
 * Month-based frequencies step from a fixed anchor date so the billing day is preserved:
 * a plan that started on 31 Jan renews on 28/29 Feb and then back on 31 Mar.
 */
enum class Frequency(
    val label: String,
    private val months: Int,
    private val days: Int,
    /** How many charges of this frequency fall in an average month. */
    val perMonth: BigDecimal,
) {
    DAILY("Daily", 0, 1, BigDecimal("30.4375")),
    WEEKLY("Weekly", 0, 7, BigDecimal("4.3482")),
    FORTNIGHTLY("Fortnightly", 0, 14, BigDecimal("2.1741")),
    MONTHLY("Monthly", 1, 0, BigDecimal.ONE),
    BIMONTHLY("Every 2 months", 2, 0, BigDecimal("0.5")),
    QUARTERLY("Quarterly", 3, 0, BigDecimal("0.333333")),
    HALF_YEARLY("Half-yearly", 6, 0, BigDecimal("0.166667")),
    YEARLY("Yearly", 12, 0, BigDecimal("0.083333")),

    /** UPI "as presented" mandates: the merchant decides when to debit, so there is no fixed schedule. */
    AS_PRESENTED("As presented", 0, 0, BigDecimal.ONE);

    val hasSchedule: Boolean get() = this != AS_PRESENTED

    /** Approximate period length, used to match observed gaps between debits. */
    val approxDays: Int get() = if (months > 0) (months * 30.4375).toInt() else days

    /** The [n]th occurrence counting from [anchor] (n = 0 is the anchor itself). */
    fun occurrence(anchor: LocalDate, n: Long): LocalDate {
        require(hasSchedule) { "$this has no schedule" }
        return if (months > 0) anchor.plusMonths(months * n) else anchor.plusDays(days * n)
    }

    /** The occurrence one period before [date]: where the period that ends on [date] began. */
    fun previous(date: LocalDate): LocalDate {
        require(hasSchedule) { "$this has no schedule" }
        return if (months > 0) date.minusMonths(months.toLong()) else date.minusDays(days.toLong())
    }

    /** First occurrence of the schedule anchored at [anchor] that falls on or after [from]. */
    fun nextOnOrAfter(anchor: LocalDate, from: LocalDate): LocalDate {
        if (!anchor.isBefore(from)) return anchor
        require(hasSchedule) { "$this has no schedule" }
        // Count whole periods exactly, in calendar months for month-based plans. An estimate from an average month length
        // overshot by months once the anchor was years back (a 1980 monthly plan came out in 2027), and walking only forward
        // never corrected it.
        var n = if (months > 0) ChronoUnit.MONTHS.between(anchor, from) / months else ChronoUnit.DAYS.between(anchor, from) / days
        while (n > 0 && !occurrence(anchor, n - 1).isBefore(from)) n--
        var date = occurrence(anchor, n)
        while (date.isBefore(from)) date = occurrence(anchor, ++n)
        return date
    }

    fun monthlyEquivalent(amount: BigDecimal): BigDecimal =
        amount.multiply(perMonth).setScale(2, RoundingMode.HALF_UP)

    companion object {
        /** Frequencies ordered by period, used when inferring one from observed gaps. */
        val scheduled: List<Frequency> = entries.filter { it.hasSchedule }

        /** Tolerance in days when matching an observed gap to [f]. */
        fun toleranceDays(f: Frequency): Int = when (f) {
            DAILY -> 0
            WEEKLY -> 1
            FORTNIGHTLY -> 2
            MONTHLY -> 4
            BIMONTHLY -> 6
            QUARTERLY -> 10
            HALF_YEARLY -> 15
            YEARLY -> 20
            AS_PRESENTED -> 0
        }

        /** The frequency whose period matches [gapDays], or null if the gap is irregular. */
        fun fromGap(gapDays: Long): Frequency? =
            scheduled.firstOrNull { kotlin.math.abs(gapDays - it.approxDays) <= toleranceDays(it) }
    }
}
