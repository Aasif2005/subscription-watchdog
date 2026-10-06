package com.aasif.watchdog.core.engine

import com.aasif.watchdog.core.merchant.KnownMerchant
import com.aasif.watchdog.core.merchant.MerchantCatalog
import com.aasif.watchdog.core.merchant.NonSubscriptionCatalog
import com.aasif.watchdog.core.model.Charge
import com.aasif.watchdog.core.model.Confidence
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.PaymentEvent.Debit
import com.aasif.watchdog.core.model.PaymentEvent.MandateCreated
import com.aasif.watchdog.core.model.PaymentEvent.MandateRevoked
import com.aasif.watchdog.core.model.PaymentEvent.PreDebitNotice
import com.aasif.watchdog.core.model.RenewalSource
import com.aasif.watchdog.core.model.Subscription
import com.aasif.watchdog.core.model.SubscriptionStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Turns parsed payment events into subscriptions with a predicted next renewal.
 *
 * The renewal date comes from the strongest signal available:
 *  1. a pre-debit notice (the bank states the exact date, ~24h ahead),
 *  2. the mandate's start date and frequency,
 *  3. the gaps between past debits,
 *  4. for trials without a stated date, the catalog's typical trial length.
 */
class SubscriptionEngine(
    private val catalog: Map<String, KnownMerchant> = MerchantCatalog.byId,
) {
    /** One plan with one merchant: its debit series plus any mandate / notices matched to it. */
    private data class Plan(
        val merchantId: String,
        val merchantName: String,
        val series: RecurringSeries?,
        val mandate: MandateCreated?,
        val notices: List<PreDebitNotice>,
    ) {
        val amount: Money? get() = series?.latestAmount ?: mandate?.amount ?: notices.lastOrNull()?.amount
    }

    fun build(events: List<PaymentEvent>, today: LocalDate): List<Subscription> =
        events.filter { it.merchantId != UNKNOWN_ID }
            .groupBy { it.merchantId }
            .flatMap { (merchantId, merchantEvents) -> plansFor(merchantId, merchantEvents) }
            .let { plans -> plans.map { toSubscription(it, plans, events, today) } }
            .sortedWith(compareBy<Subscription> { !it.isLive }.thenBy { it.nextRenewal ?: LocalDate.MAX })

    private fun plansFor(merchantId: String, events: List<PaymentEvent>): List<Plan> {
        val name = events.last().merchantName
        val known = catalog[merchantId]
        val eligibleDebits = events.filterIsInstance<Debit>().filter { patternEligible(merchantId, known, it) }
        val series = RecurrenceDetector.detect(eligibleDebits, merchantId in catalog)
        val mandates = events.filterIsInstance<MandateCreated>().sortedWith(compareBy({ it.receivedOn }, { it.receivedAt }))
        val notices = events.filterIsInstance<PreDebitNotice>()

        if (series.isEmpty()) {
            // No usable history: a mandate on its own is still a subscription (new plan or trial).
            // A lone notice is too, since the bank only sends them for registered mandates.
            val mandate = mandates.lastOrNull()
            if (mandate == null && notices.isEmpty()) return emptyList()
            return listOf(Plan(merchantId, name, null, mandate, notices))
        }

        // Match the latest mandate and each notice to the series with the closest amount.
        fun closest(amount: Money?): RecurringSeries =
            if (amount == null) series.maxBy { it.debits.last().date }
            else series.minBy { if (it.latestAmount.currency != amount.currency) BigDecimal(Long.MAX_VALUE) else (it.latestAmount.amount - amount.amount).abs() }

        val latestMandate = mandates.lastOrNull()
        val mandateSeries = latestMandate?.let { closest(it.amount) }
        val noticesBySeries = notices.groupBy { closest(it.amount) }
        return series.map { s ->
            Plan(merchantId, name, s, latestMandate.takeIf { mandateSeries === s }, noticesBySeries[s].orEmpty())
        }
    }

    /**
     * Whether a debit is allowed to feed [RecurrenceDetector]'s amount+timing pattern match.
     *
     * That match can only see amount and timing, which is not evidence for merchants that sell
     * one-off units — three movie outings that happen to land ~90 days apart at a similar ticket
     * price fit a "quarterly subscription" exactly as well as a real one would ([NonSubscriptionCatalog]),
     * and a prepaid mobile recharge repeats on a schedule as a matter of habit, not because it
     * auto-renews ([KnownMerchant.autopayOnly]). Both are excluded from pattern matching; a debit
     * that carries its own explicit mandate/AutoPay signal is still let through, since that is real
     * evidence rather than a coincidence — and a genuine mandate-registration SMS reaches the
     * subscription a different way entirely, via [MandateCreated] in [plansFor] above, so excluding
     * the plain debits here does not affect it.
     */
    private fun patternEligible(merchantId: String, known: KnownMerchant?, debit: Debit): Boolean =
        debit.viaMandate || (known?.autopayOnly != true && !NonSubscriptionCatalog.matches(merchantId))

    private fun toSubscription(plan: Plan, all: List<Plan>, events: List<PaymentEvent>, today: LocalDate): Subscription {
        val known = catalog[plan.merchantId]
        val charges = plan.series?.debits?.map { Charge(it.date, it.amount) }.orEmpty()
        val lastCharge = charges.maxOfOrNull { it.date }
        val statedFrequency = plan.mandate?.frequency?.takeIf { it.hasSchedule } ?: plan.series?.frequency
        val frequency = statedFrequency ?: Frequency.MONTHLY
        val samePlanCount = all.count { it.merchantId == plan.merchantId }
        val key = if (samePlanCount == 1) plan.merchantId
        else "${plan.merchantId}#${plan.amount?.amount?.setScale(0, java.math.RoundingMode.HALF_UP) ?: 0}"

        val prediction = predict(plan, frequency, lastCharge, known, events, today)
        return Subscription(
            key = key,
            merchantId = plan.merchantId,
            name = known?.name ?: plan.merchantName,
            category = known?.category ?: "Other",
            amount = plan.amount ?: Money(BigDecimal.ZERO),
            frequency = frequency,
            nextRenewal = prediction.next,
            renewalSource = prediction.source,
            confidence = prediction.confidence,
            status = prediction.status,
            charges = charges,
            cancelUrl = known?.cancelUrl,
            amountKnown = plan.amount != null,
            frequencyKnown = statedFrequency != null && !isUnconfirmedMembershipGuess(known, plan),
            sharedBilling = known?.sharedBilling == true,
        )
    }

    /**
     * True when [known] shares its payment descriptor with a non-subscription sibling product
     * ([KnownMerchant.membershipConfusable]) and the only reason this plan exists at all is a bare
     * amount+timing pattern — no mandate registration, no pre-debit notice, no debit that itself
     * carried a mandate/AutoPay signal. A Swiggy One charge inferred purely from "two similar
     * amounts to Bundl Technologies" is exactly as likely to be two Instamart orders; that pattern
     * alone is not allowed to promote it to a confident subscription (see [toSubscription]) — it
     * is deliberately reported as if the frequency were never stated, which is what routes it to
     * the existing "Needs your input" card instead of a silent Active status.
     */
    private fun isUnconfirmedMembershipGuess(known: KnownMerchant?, plan: Plan): Boolean {
        if (known?.membershipConfusable != true) return false
        val hasRealEvidence = plan.mandate != null || plan.notices.isNotEmpty() ||
            plan.series?.debits?.any { it.viaMandate } == true
        return !hasRealEvidence
    }

    private data class Prediction(
        val next: LocalDate?,
        val source: RenewalSource,
        val confidence: Confidence,
        val status: SubscriptionStatus,
    )

    private fun revocationEndsPlan(
        revoked: MandateRevoked,
        mandate: MandateCreated?,
        lastCharge: LocalDate?,
        lastActivity: LocalDate?,
    ): Boolean {
        val revokedOn = revoked.receivedOn
        if (lastActivity == null || revokedOn.isAfter(lastActivity)) return true
        if (revokedOn.isBefore(lastActivity)) return false
        val sameDayAsMandate = mandate?.receivedOn == revokedOn && (lastCharge == null || lastCharge.isBefore(revokedOn))
        if (!sameDayAsMandate) return true
        val revokedAt = revoked.receivedAt
        val createdAt = mandate?.receivedAt
        return if (revokedAt != null && createdAt != null) revokedAt.isAfter(createdAt) else false
    }

    private fun predict(
        plan: Plan,
        frequency: Frequency,
        lastCharge: LocalDate?,
        known: KnownMerchant?,
        events: List<PaymentEvent>,
        today: LocalDate,
    ): Prediction {
        val mandate = plan.mandate
        val lastActivity = listOfNotNull(lastCharge, mandate?.receivedOn).maxOrNull()

        // Cancelled: a revocation newer than the latest mandate or charge. On the day of the latest
        // mandate the order of the two decides it (a plan change or a retried setup sends "revoked"
        // and "created" within minutes); with no times to go on, a same-day pair counts as the
        // mandate replacing the old one. On a charge day, a revocation still cancels.
        val revoked = events.filterIsInstance<MandateRevoked>()
            .filter { it.merchantId == plan.merchantId }
            .maxWithOrNull(compareBy({ it.receivedOn }, { it.receivedAt }))
        if (revoked != null && revocationEndsPlan(revoked, mandate, lastCharge, lastActivity)) {
            return Prediction(null, RenewalSource.MANDATE, Confidence.HIGH, SubscriptionStatus.CANCELLED)
        }
        if (mandate?.validTill?.isBefore(today) == true) {
            return Prediction(null, RenewalSource.MANDATE, Confidence.HIGH, SubscriptionStatus.LAPSED)
        }

        // Don't predict a second charge on the day we already saw one.
        val from = if (lastCharge != null && !lastCharge.isBefore(today)) lastCharge.plusDays(1) else today

        // 1. The bank told us the exact date.
        plan.notices.map { it.debitDate }.filter { !it.isBefore(from) }.minOrNull()?.let { date ->
            val status = if (lastCharge == null && isTrialMandate(mandate, known)) SubscriptionStatus.TRIAL else SubscriptionStatus.ACTIVE
            return Prediction(date, RenewalSource.PRE_DEBIT_NOTICE, Confidence.HIGH, status)
        }

        // 2. The mandate states when debits start and how often they repeat.
        if (mandate?.startDate != null) {
            val start = mandate.startDate
            if (lastCharge == null && start.isAfter(today)) {
                val status = if (isTrialMandate(mandate, known)) SubscriptionStatus.TRIAL else SubscriptionStatus.ACTIVE
                return Prediction(start, RenewalSource.MANDATE, Confidence.HIGH, status)
            }
            // A duration read off a receipt ("3 months plan") gives a frequency too, but unless the
            // mail actually says it renews, that date is where the plan *ends* — a single point,
            // not licence to assume it repeats forever. Step 4 (PLAN_EXPIRY) handles that case.
            val assumesRenewal = mandate.validTill == null || mandate.autoRenews
            if (frequency.hasSchedule && mandate.frequency?.hasSchedule == true && assumesRenewal) {
                return Prediction(frequency.nextOnOrAfter(start, from), RenewalSource.MANDATE, Confidence.HIGH, SubscriptionStatus.ACTIVE)
            }
        }

        // 3. Extrapolate from past charges.
        val series = plan.series
        if (series != null && lastCharge != null) {
            val lapseAfter = lastCharge.plusDays(2L * frequency.approxDays + Frequency.toleranceDays(frequency))
            if (today.isAfter(lapseAfter) && mandate == null) {
                return Prediction(null, RenewalSource.PAYMENT_HISTORY, series.confidence, SubscriptionStatus.LAPSED)
            }
            val next = frequency.nextOnOrAfter(billingAnchor(series.debits.map { it.date }), from)
            return Prediction(next, RenewalSource.PAYMENT_HISTORY, series.confidence, SubscriptionStatus.ACTIVE)
        }

        // 4. A stated plan length ("3 months plan") ran out on a date nothing stronger overrides.
        // Whether that date is also the next charge depends on auto-renewal, which the mail may or
        // may not say — either way it is a real date, worth surfacing at MEDIUM rather than guessed.
        if (mandate?.validTill != null && !mandate.validTill.isBefore(from)) {
            val status = if (lastCharge == null && isTrialMandate(mandate, known)) SubscriptionStatus.TRIAL else SubscriptionStatus.ACTIVE
            return Prediction(mandate.validTill, RenewalSource.PLAN_EXPIRY, Confidence.MEDIUM, status)
        }

        // 5. A mandate with no date and no history: most likely a free trial.
        if (mandate != null) {
            known?.trialDays?.let { days ->
                val trialEnd = mandate.receivedOn.plusDays(days.toLong())
                if (!trialEnd.isBefore(today)) {
                    return Prediction(trialEnd, RenewalSource.TRIAL_ESTIMATE, Confidence.LOW, SubscriptionStatus.TRIAL)
                }
            }
            if (frequency.hasSchedule && mandate.frequency?.hasSchedule == true) {
                val next = frequency.nextOnOrAfter(mandate.receivedOn, from)
                return Prediction(next, RenewalSource.MANDATE, Confidence.LOW, SubscriptionStatus.ACTIVE)
            }
            return Prediction(null, RenewalSource.MANDATE, Confidence.LOW, SubscriptionStatus.ACTIVE)
        }

        return Prediction(null, RenewalSource.PAYMENT_HISTORY, Confidence.LOW, SubscriptionStatus.ACTIVE)
    }

    /**
     * A trial mandate is one set up with a token amount (₹1-2 verification) or whose first real
     * debit is at least a few days away, or one for a service known to offer trials.
     */
    private fun isTrialMandate(mandate: MandateCreated?, known: KnownMerchant?): Boolean {
        if (mandate == null) return false
        val amount = mandate.amount
        if (amount != null && amount <= BigDecimal(2)) return true
        val start = mandate.startDate
        if (start != null) return ChronoUnit.DAYS.between(mandate.receivedOn, start) >= TRIAL_MIN_DAYS
        return known?.trialDays != null
    }

    /**
     * The charge whose day of month the plan really bills on. After a 31 Jan charge the bank
     * debits on 28 Feb, so anchoring on 28 Feb would wrongly predict 28 Mar instead of 31 Mar.
     */
    private fun billingAnchor(dates: List<LocalDate>): LocalDate {
        val recent = dates.sorted().takeLast(3)
        val billingDay = recent.maxOf { it.dayOfMonth }
        return recent.last { it.dayOfMonth == billingDay }
    }

    /** A subscription the user added by hand, e.g. a trial the SMS scan couldn't see. */
    fun manual(
        key: String,
        name: String,
        amount: Money,
        frequency: Frequency,
        firstCharge: LocalDate,
        isTrial: Boolean,
        today: LocalDate,
    ): Subscription {
        val next = if (frequency.hasSchedule) frequency.nextOnOrAfter(firstCharge, today) else null
        return Subscription(
            key = key,
            merchantId = key,
            name = name,
            category = "Added by you",
            amount = amount,
            frequency = frequency,
            nextRenewal = next,
            renewalSource = RenewalSource.MANUAL,
            confidence = Confidence.HIGH,
            status = if (isTrial && !firstCharge.isBefore(today)) SubscriptionStatus.TRIAL else SubscriptionStatus.ACTIVE,
            charges = emptyList(),
            cancelUrl = null,
        )
    }

    companion object {
        private const val UNKNOWN_ID = "u:unknown"
        private const val TRIAL_MIN_DAYS = 5
    }
}

/** Live subscriptions renewing within [leadDays] of [today] (inclusive), soonest first. */
fun List<Subscription>.dueForReminder(today: LocalDate, leadDays: Int): List<Subscription> =
    filter { it.isLive && it.nextRenewal != null }
        .filter { ChronoUnit.DAYS.between(today, it.nextRenewal) in 0..leadDays.toLong() }
        .sortedBy { it.nextRenewal }

/**
 * Sums only what is actually known, in rupees. A plan with an unknown amount used to count as
 * ₹0 towards this total, which made it look cheaper, not incomplete; excluding it is the honest
 * answer. A plan billed in another currency is real spend too, just not one this figure can add
 * without a conversion rate the app doesn't fetch.
 */
fun List<Subscription>.monthlyTotal(): BigDecimal =
    filter { it.status == SubscriptionStatus.ACTIVE && it.amountKnown && it.amount.currency == Money.INR }
        .fold(BigDecimal.ZERO) { acc, s -> acc + s.monthlyCost.amount }

/** What cancelled plans would have cost over a year; the basis for the savings report. */
fun List<Subscription>.yearlySavings(): BigDecimal =
    filter { it.status == SubscriptionStatus.CANCELLED && it.amountKnown && it.amount.currency == Money.INR }
        .fold(BigDecimal.ZERO) { acc, s -> acc + s.monthlyCost.amount.multiply(BigDecimal(12)) }
