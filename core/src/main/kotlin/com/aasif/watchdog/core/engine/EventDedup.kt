package com.aasif.watchdog.core.engine

import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** A revocation SMS and the e-mail confirming it can be a day apart. */
private const val REVOKE_WINDOW_DAYS = 2L

/**
 * Whether two parsed events describe the same real-world payment, so one can be dropped before
 * it is stored.
 *
 * Which source each event came from is deliberately ignored: collapsing the SMS, the app
 * notification and the e-mail about one charge into a single event is the entire point.
 */
fun PaymentEvent.isLikelyDuplicateOf(other: PaymentEvent): Boolean {
    if (merchantId != other.merchantId) return false
    return when (this) {
        is PaymentEvent.Debit ->
            other is PaymentEvent.Debit && date == other.date && sameAmount(amount, other.amount)

        is PaymentEvent.PreDebitNotice ->
            other is PaymentEvent.PreDebitNotice && debitDate == other.debitDate && sameAmount(amount, other.amount)

        is PaymentEvent.MandateCreated ->
            other is PaymentEvent.MandateCreated && startDate == other.startDate &&
                frequency == other.frequency && sameAmount(amount, other.amount)

        is PaymentEvent.MandateRevoked ->
            other is PaymentEvent.MandateRevoked &&
                abs(ChronoUnit.DAYS.between(receivedOn, other.receivedOn)) <= REVOKE_WINDOW_DAYS
    }
}

/** Sources differ in scale ("199" vs "199.00"), which BigDecimal.equals() treats as unequal. */
private fun sameAmount(a: Money?, b: Money?): Boolean =
    if (a == null || b == null) a == b else a.currency == b.currency && a.amount.compareTo(b.amount) == 0
