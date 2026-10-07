package com.aasif.watchdog.core.engine

import com.aasif.watchdog.core.model.RenewalSource
import com.aasif.watchdog.core.model.SubscriptionStatus

/** How firmly a reminder on the renewal day itself can speak, which depends on where the date came from. */
enum class RenewalDayKind {
    /** The bank, the mandate or the user gave this exact day. */
    EXACT,

    /** The day was worked out from past payments, so the charge may land a day either side. */
    ESTIMATED,

    /** A free trial that ends today. */
    TRIAL,

    /** A plan whose stated length runs out today; nothing says it renews. */
    PLAN_END,
}

/**
 * What kind of renewal-day wording is honest for a subscription. Never "last date": a mandate
 * normally goes on charging every cycle, and an estimated date is only an estimate.
 */
fun renewalDayKind(source: RenewalSource, status: SubscriptionStatus): RenewalDayKind = when {
    status == SubscriptionStatus.TRIAL -> RenewalDayKind.TRIAL
    source == RenewalSource.PLAN_EXPIRY -> RenewalDayKind.PLAN_END
    source == RenewalSource.PAYMENT_HISTORY || source == RenewalSource.TRIAL_ESTIMATE -> RenewalDayKind.ESTIMATED
    else -> RenewalDayKind.EXACT
}
