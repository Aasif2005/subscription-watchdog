package com.aasif.watchdog.ui

import com.aasif.watchdog.core.engine.RenewalDayKind
import com.aasif.watchdog.core.engine.renewalDayKind
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.RenewalSource
import com.aasif.watchdog.core.model.Subscription
import com.aasif.watchdog.core.model.SubscriptionStatus
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val rupees = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))

/** Symbols for the handful of currencies mail actually bills in; anything else falls back to its code. */
private val currencySymbols = mapOf("INR" to "₹", "USD" to "$", "EUR" to "€", "GBP" to "£")
private val dayMonthYear = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
private val hourMinute = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

/** "₹1,499" or "₹149.50", with Indian digit grouping. */
fun formatRupees(amount: BigDecimal): String {
    rupees.minimumFractionDigits = if (amount.stripTrailingZeros().scale() <= 0) 0 else 2
    return rupees.format(amount)
}

/** [formatRupees] for INR, "$7.00"-style for anything else — no conversion, just its own symbol. */
fun formatMoney(money: Money): String {
    if (money.currency == Money.INR) return formatRupees(money.amount)
    val symbol = currencySymbols[money.currency] ?: "${money.currency} "
    val scale = if (money.amount.stripTrailingZeros().scale() <= 0) 0 else 2
    return "$symbol${money.amount.setScale(scale, java.math.RoundingMode.HALF_UP).toPlainString()}"
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
        next == null && frequency.hasSchedule && needsInput -> "Renewal date unknown — tell the app"
        next == null -> "Charged whenever the merchant bills (no fixed date)"
        status == SubscriptionStatus.TRIAL -> "Trial ends ${relativeDays(next)} · ${formatDate(next)}"
        renewalSource == RenewalSource.PLAN_EXPIRY -> "Plan ends ${relativeDays(next)} · ${formatDate(next)}"
        else -> "Renews ${relativeDays(next)} · ${formatDate(next)}"
    }
}

/** From this hour on, on the renewal day itself, the reminder stops saying "will be charged" and says what is known. */
private const val DAY_OF_HOUR = 9

/**
 * The renewal reminder's title and body, shared by the notification and the dashboard card so
 * the two never disagree. A trial ending and a plan running out are not renewals, and an amount
 * nothing ever stated is left out rather than shown as ₹0.
 *
 * After 9:00 on the renewal day the wording changes: by then a morning debit may already have
 * happened, so it says the charge is due (or expected, when the date was only estimated) and
 * points at the bank, rather than promising time to act. It never says "last date": a mandate
 * normally goes on charging every cycle.
 */
fun Subscription.reminderText(date: LocalDate, now: LocalDateTime = LocalDateTime.now()): Pair<String, String> {
    val today = now.toLocalDate()
    val whenText = relativeDays(date, today)
    val on = formatDate(date)
    val price = if (amountKnown) formatMoney(amount) else null
    val dayOf = date == today && now.hour >= DAY_OF_HOUR
    return when (renewalDayKind(renewalSource, status)) {
        RenewalDayKind.TRIAL ->
            "$name free trial ends $whenText" to when {
                dayOf -> "Your $name free trial ends today. Some services stop accepting cancellations before the end of the day."
                else -> price?.let { "$it will be charged unless you cancel before $on." } ?: "Cancel before $on to avoid being charged."
            }
        // A plan length read off a receipt is an end date, not a confirmed auto-renewal.
        RenewalDayKind.PLAN_END ->
            "$name plan ends $whenText" to
                (price?.let { "$it plan ends on $on. Renew it if you want to keep it." } ?: "Your plan ends on $on.")
        RenewalDayKind.EXACT ->
            "$name renews $whenText" to when {
                dayOf -> "${price?.let { "$name $it" } ?: name} is due to be charged today. To stop it, revoke the AutoPay now, then check your bank for a debit."
                else -> price?.let { "$it (${frequency.label.lowercase()}) will be charged on $on." } ?: "Renews on $on."
            }
        RenewalDayKind.ESTIMATED ->
            "$name renews $whenText" to when {
                dayOf -> "${price?.let { "$name $it" } ?: name} is expected to renew around today, based on your past payments. Check your bank."
                else -> price?.let { "$it (${frequency.label.lowercase()}) will be charged on $on." } ?: "Renews on $on."
            }
    }
}

fun RenewalSource.explanation(): String = when (this) {
    RenewalSource.USER_SUPPLIED -> "You told us"
    RenewalSource.PRE_DEBIT_NOTICE -> "Exact date from your bank's pre-debit notice"
    RenewalSource.MANDATE -> "From your AutoPay mandate's start date and frequency"
    RenewalSource.PAYMENT_HISTORY -> "Estimated from the gaps between your past payments"
    RenewalSource.PLAN_EXPIRY -> "The plan length stated in its receipt"
    RenewalSource.TRIAL_ESTIMATE -> "Estimated from this service's usual free-trial length"
    RenewalSource.MANUAL -> "Added by you"
}
