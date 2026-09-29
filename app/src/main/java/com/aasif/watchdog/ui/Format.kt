package com.aasif.watchdog.ui

import com.aasif.watchdog.core.model.RenewalSource
import com.aasif.watchdog.core.model.Subscription
import com.aasif.watchdog.core.model.SubscriptionStatus
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val rupees = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))
private val dayMonthYear = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
private val hourMinute = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

/** "₹1,499" or "₹149.50", with Indian digit grouping. */
fun formatRupees(amount: BigDecimal): String {
    rupees.minimumFractionDigits = if (amount.stripTrailingZeros().scale() <= 0) 0 else 2
    return rupees.format(amount)
}

fun formatDate(date: LocalDate): String = date.format(dayMonthYear)

/**
 * "05 Sep 2026, 8:42 pm", falling back to the date alone for events stored before arrival times
 * were kept.
 */
fun formatDateTime(date: LocalDate, atMillis: Long?): String {
    if (atMillis == null || atMillis <= 0) return formatDate(date)
    val time = Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()).toLocalTime()
    return "${formatDate(date)}, ${time.format(hourMinute)}"
}

fun relativeDays(date: LocalDate, today: LocalDate = LocalDate.now()): String =
    when (val days = ChronoUnit.DAYS.between(today, date)) {
        0L -> "today"
        1L -> "tomorrow"
        in 2..60 -> "in $days days"
        else -> "on ${formatDate(date)}"
    }

/** One line under the name: when the next charge happens, or why there won't be one. */
fun Subscription.renewalLine(): String {
    val next = nextRenewal
    return when {
        status == SubscriptionStatus.CANCELLED -> "Cancelled"
        status == SubscriptionStatus.LAPSED -> "No recent charges — probably ended"
        next == null -> "Charged whenever the merchant bills (no fixed date)"
        status == SubscriptionStatus.TRIAL -> "Trial ends ${relativeDays(next)} · ${formatDate(next)}"
        else -> "Renews ${relativeDays(next)} · ${formatDate(next)}"
    }
}

fun RenewalSource.explanation(): String = when (this) {
    RenewalSource.PRE_DEBIT_NOTICE -> "Exact date from your bank's pre-debit notice"
    RenewalSource.MANDATE -> "From your AutoPay mandate's start date and frequency"
    RenewalSource.PAYMENT_HISTORY -> "Estimated from the gaps between your past payments"
    RenewalSource.TRIAL_ESTIMATE -> "Estimated from this service's usual free-trial length"
    RenewalSource.MANUAL -> "Added by you"
}
