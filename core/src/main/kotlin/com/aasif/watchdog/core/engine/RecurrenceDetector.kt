package com.aasif.watchdog.core.engine

import com.aasif.watchdog.core.model.Confidence
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent.Debit
import java.math.BigDecimal

/** A run of similar debits to one merchant that repeats on a regular schedule. */
data class RecurringSeries(
    val debits: List<Debit>,
    val frequency: Frequency,
    val confidence: Confidence,
) {
    val latestAmount: Money get() = debits.last().amount
}

/**
 * Finds recurring charges in plain debit history, for when the mandate SMS is missing.
 *
 * Debits to one merchant are split by amount (so two plans with one merchant stay apart), the
 * gaps between charges are matched to a [Frequency], and the series is kept only if the gaps
 * are regular enough.
 */
object RecurrenceDetector {
    private const val AMOUNT_TOLERANCE = 0.15
    private const val MIN_REGULARITY = 0.6

    /**
     * @param debits all debits for a single merchant.
     * @param knownMerchant true for catalog services; a regular charge there is almost certainly a
     *   subscription, whereas for an unknown shop it could be a habit (weekly groceries).
     */
    fun detect(debits: List<Debit>, knownMerchant: Boolean): List<RecurringSeries> =
        // A payee that is someone's phone number is a person, not a subscription, no matter how
        // regularly money goes to them.
        clusterByAmount(debits.filterNot { it.peerToPeer }.distinctBy { it.date to it.amount })
            .mapNotNull { series(it.sortedBy(Debit::date), knownMerchant) }

    private fun clusterByAmount(debits: List<Debit>): List<List<Debit>> {
        val clusters = mutableListOf<MutableList<Debit>>()
        // Different currencies are different plans by definition, so cluster within currency first.
        for (d in debits.sortedWith(compareBy({ it.amount.currency }, { it.amount.amount }))) {
            val base = clusters.lastOrNull()?.first()?.amount
            if (base != null && base.currency == d.amount.currency && isSimilar(base.amount, d.amount.amount)) {
                clusters.last() += d
            } else {
                clusters += mutableListOf(d)
            }
        }
        // A price change (Netflix 499 -> 649) splits one plan into two clusters that don't overlap
        // in time; join those back together.
        val byTime = clusters.sortedBy { c -> c.minOf { it.date } }
        val merged = mutableListOf<MutableList<Debit>>()
        for (c in byTime) {
            val prev = merged.lastOrNull()
            if (prev != null && c.minOf { it.date } > prev.maxOf { it.date }) prev += c else merged += c
        }
        return merged
    }

    private fun isSimilar(a: BigDecimal, b: BigDecimal): Boolean {
        val diff = (a - b).abs()
        return diff <= BigDecimal(20) || diff <= a.max(b).multiply(BigDecimal(AMOUNT_TOLERANCE))
    }

    private fun series(debits: List<Debit>, knownMerchant: Boolean): RecurringSeries? {
        val viaMandate = debits.any { it.viaMandate }
        val trusted = knownMerchant || viaMandate
        val dates = debits.map { it.date }.distinct()
        if (dates.size < 2) return null

        val gaps = dates.zipWithNext { a, b -> java.time.temporal.ChronoUnit.DAYS.between(a, b) }
        val frequency = gaps.mapNotNull(Frequency::fromGap).groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key ?: return null
        val regularity = gaps.count { Frequency.fromGap(it) == frequency }.toDouble() / gaps.size
        if (regularity < MIN_REGULARITY) return null

        // Short periods from an unknown payee are usually habits, not subscriptions.
        if (!trusted && frequency.approxDays < Frequency.MONTHLY.approxDays) return null

        val confidence = when {
            dates.size >= 3 && trusted -> Confidence.HIGH
            dates.size >= 3 -> Confidence.MEDIUM
            trusted -> if (knownMerchant && viaMandate) Confidence.MEDIUM else Confidence.LOW
            else -> return null // two charges to an unknown payee is not enough evidence
        }
        return RecurringSeries(debits, frequency, confidence)
    }
}
