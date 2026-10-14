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
        /** Every mandate set up for this plan, oldest first; [mandate] is the latest of them. */
        val mandates: List<MandateCreated> = listOfNotNull(mandate),
        /** The revocations that can be told apart as this plan's, see [attributeRevocations]. */
        val revocations: List<MandateRevoked> = emptyList(),
        /**
         * The mandate's own debits when they are too few to form a [series] — the first charge
         * of a plan set up this month is one debit, and it is still a payment for this plan.
         */
        val mandateDebits: List<Debit> = emptyList(),
        /** Amount-less revocations on this payee that no plan could claim, see [attributeRevocations]. */
        val unplaced: List<MandateRevoked> = emptyList(),
    ) {
        val amount: Money? get() = series?.latestAmount ?: mandate?.amount ?: notices.lastOrNull()?.amount

        /** Exactly what this plan was built from: the "Why this is here" list, and nothing else. */
        val events: List<PaymentEvent>
            get() = series?.debits.orEmpty() + mandateDebits + mandates + notices + revocations
    }

    fun build(events: List<PaymentEvent>, today: LocalDate): List<Subscription> =
        events.filter { it.merchantId != UNKNOWN_ID }
            .groupBy { it.merchantId }
            .flatMap { (merchantId, merchantEvents) -> plansFor(merchantId, merchantEvents) }
            .let { plans -> plans.map { toSubscription(it, plans, today) } }
            .sortedWith(compareBy<Subscription> { !it.isLive }.thenBy { it.nextRenewal ?: LocalDate.MAX })

    private fun plansFor(merchantId: String, events: List<PaymentEvent>): List<Plan> {
        val name = events.last().merchantName
        val known = catalog[merchantId]
        val eligibleDebits = events.filterIsInstance<Debit>().filter { patternEligible(merchantId, known, it) }
        val series = RecurrenceDetector.detect(eligibleDebits, merchantId in catalog)
        val mandates = events.filterIsInstance<MandateCreated>().sortedWith(compareBy({ it.receivedOn }, { it.receivedAt }))
        // A "welcome to Premium" mail dates and names a plan but is no evidence of one on its
        // own; it joins a plan that a payment, a mandate or a notice establishes.
        val payable = mandates.filterNot { it.announcementOnly }
        val notices = events.filterIsInstance<PreDebitNotice>()
        val revocations = events.filterIsInstance<MandateRevoked>()
        val debits = events.filterIsInstance<Debit>()

        if (known?.sharedBilling == true) return channelPlans(merchantId, name, series, mandates, notices, revocations, debits)

        val plans = if (series.isEmpty()) {
            // No usable history: a mandate on its own is still a subscription (new plan or trial).
            // A lone notice is too, since the bank only sends them for registered mandates.
            val mandate = payable.lastOrNull()
            if (mandate == null && notices.isEmpty()) return emptyList()
            listOf(Plan(merchantId, name, null, mandate, notices, mandates, mandateDebits = ownDebits(payable, debits)))
        } else {
            // Match the latest mandate and each notice to the series with the closest amount.
            fun closest(amount: Money?): RecurringSeries =
                if (amount == null) series.maxBy { it.debits.last().date }
                else series.minBy { if (it.latestAmount.currency != amount.currency) BigDecimal(Long.MAX_VALUE) else (it.latestAmount.amount - amount.amount).abs() }

            val latestMandate = payable.lastOrNull()
            val mandateSeries = latestMandate?.let { closest(it.amount) }
            val noticesBySeries = notices.groupBy { closest(it.amount) }
            // With one plan, every mandate is its history (an old price included); with several,
            // a mandate belongs to the plan whose amount it is closest to.
            val mandatesBySeries = if (series.size == 1) mapOf(series.single() to mandates) else mandates.groupBy { closest(it.amount) }
            series.map { s ->
                Plan(
                    merchantId, name, s, latestMandate.takeIf { mandateSeries === s }, noticesBySeries[s].orEmpty(),
                    mandates = mandatesBySeries[s].orEmpty(),
                )
            }
        }
        return attributeRevocations(plans, revocations, exactOnly = false)
    }

    /**
     * A billing channel (Google Play, the App Store) bills for many apps under one payee, so a
     * different amount is a different app: one plan per amount, with nothing attached across
     * amounts. Keeping only the newest mandate, as for an ordinary merchant, hid every other app
     * and lent their texts to the newest one as its "proof".
     */
    private fun channelPlans(
        merchantId: String,
        name: String,
        series: List<RecurringSeries>,
        mandates: List<MandateCreated>,
        notices: List<PreDebitNotice>,
        revocations: List<MandateRevoked>,
        debits: List<Debit>,
    ): List<Plan> {
        val amounts = (mandates.map { it.amount } + series.map { it.latestAmount } + notices.map { it.amount })
            .map { it?.let(::bucket) }
            .distinct()
        val plans = amounts.mapNotNull { amount ->
            val own = mandates.filter { it.amount?.let(::bucket) == amount }
            val ownSeries = series.filter { bucket(it.latestAmount) == amount }.maxByOrNull { it.debits.last().date }
            val ownNotices = notices.filter { it.amount?.let(::bucket) == amount }
            if (own.isEmpty() && ownSeries == null && ownNotices.isEmpty()) return@mapNotNull null
            val latest = own.lastOrNull()
            Plan(
                merchantId, name, ownSeries, latest, ownNotices, own,
                mandateDebits = if (ownSeries == null) ownDebits(own, debits) else emptyList(),
            )
        }
        return attributeRevocations(plans, revocations, exactOnly = true)
    }

    /**
     * Gives each plan the revocations that are provably its own. A revocation that states an
     * amount belongs to the plan with that amount. One that does not can only be placed when the
     * merchant has a single plan; on a channel with several it is left out rather than guessed,
     * since guessing would cancel the wrong app. A revocation carrying the number of one plan's
     * mandate belongs to that plan, before any of this. For an ordinary merchant ([exactOnly] false) an
     * amount that matches no plan still applies to all, so a rounding mismatch between the
     * mandate and its revocation text cannot keep a real cancellation from counting.
     */
    private fun attributeRevocations(plans: List<Plan>, revocations: List<MandateRevoked>, exactOnly: Boolean): List<Plan> {
        if (plans.size == 1) return listOf(plans.single().copy(revocations = revocations))
        val refsOf = plans.map { plan -> plan.mandates.mapNotNull { it.mandateRef }.toSet() }
        fun owns(index: Int, plan: Plan, r: MandateRevoked): Boolean {
            val stated = r.amount?.let(::bucket)
            val planAmount = plan.amount?.let(::bucket)
            // The mandate's own number settles it: the revocation carries the number of the
            // mandate it ends, whatever amount or payee the text states.
            val owners = r.mandateRef?.let { ref -> plans.indices.filter { ref in refsOf[it] } }.orEmpty().takeIf { it.size == 1 }.orEmpty()
            return when {
                owners.isNotEmpty() -> index in owners
                stated != null && stated == planAmount -> true
                exactOnly -> false
                stated == null -> true
                else -> plans.none { it.amount?.let(::bucket) == stated }
            }
        }
        val claimed = revocations.filter { r -> plans.indices.any { owns(it, plans[it], r) } }.toSet()
        // Only a revocation with no amount could have been any of the plans'; one that states another
        // amount is provably some other mandate's.
        val unplaced = revocations.filter { it !in claimed && it.amount == null }
        return plans.mapIndexed { index, plan ->
            plan.copy(revocations = revocations.filter { owns(index, plan, it) }, unplaced = unplaced)
        }
    }

    /**
     * A plan's own debits: the amount of its mandates, from the day the first was set up. Counting
     * from the newest instead lost the first month's payment of anyone who cancelled and then
     * subscribed again.
     */
    private fun ownDebits(mandates: List<MandateCreated>, debits: List<Debit>): List<Debit> {
        val first = mandates.minByOrNull { it.receivedOn } ?: return emptyList()
        val amounts = mandates.mapNotNull { it.amount?.let(::bucket) }.toSet()
        if (amounts.isEmpty()) return emptyList()
        return debits.filter { !it.date.isBefore(first.receivedOn) && bucket(it.amount) in amounts && !it.peerToPeer }
            .sortedBy { it.date }
    }

    /** Whole rupees in the same currency: "1999", "1999.00" and "1,999" are one amount. */
    private fun bucket(m: Money): Money = Money(m.amount.setScale(0, java.math.RoundingMode.HALF_UP), m.currency)

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

    private fun toSubscription(plan: Plan, all: List<Plan>, today: LocalDate): Subscription {
        val known = catalog[plan.merchantId]
        val charges = (plan.series?.debits.orEmpty() + plan.mandateDebits).map { Charge(it.date, it.amount) }.distinct()
        val lastCharge = charges.maxOfOrNull { it.date }
        // The service's own mail says what plan it sold; the bank's mandate frequency comes next
        // ("as presented" says nothing), then the rhythm of past charges.
        val mailFrequency = plan.mandates.filter { it.fromMail }.mapNotNull { it.frequency?.takeIf { f -> f.hasSchedule } }.lastOrNull()
        val statedFrequency = mailFrequency ?: plan.mandate?.frequency?.takeIf { it.hasSchedule } ?: plan.series?.frequency
        val frequency = statedFrequency ?: Frequency.MONTHLY
        val samePlanCount = all.count { it.merchantId == plan.merchantId }
        // A channel's plans are always keyed by amount: whether other apps' plans exist changes
        // over time, and a key that flips with it loses whatever the user saved under it.
        val key = if (samePlanCount == 1 && known?.sharedBilling != true) plan.merchantId
        else "${plan.merchantId}#${plan.amount?.amount?.setScale(0, java.math.RoundingMode.HALF_UP) ?: 0}"

        val prediction = predict(plan, frequency, statedFrequency, lastCharge, known, today)
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
            events = plan.events,
            activeUntil = prediction.activeUntil,
            missedChargeOn = prediction.missedOn,
            chargedAfterCancel = chargedAfterCancel(plan),
            unplacedRevocationOn = plan.unplaced.takeIf { prediction.status.let { s -> s == SubscriptionStatus.ACTIVE || s == SubscriptionStatus.TRIAL } }
                ?.filter { revocation -> plan.mandate?.let { revocation.followsMandate(it) } ?: true }
                ?.maxOfOrNull { it.receivedOn },
        )
    }

    /**
     * The first charge dated after one of this plan's revocations with no mandate set up again in
     * between. A new mandate after a revocation is the user subscribing again, not a charge they
     * did not agree to.
     */
    private fun chargedAfterCancel(plan: Plan): LocalDate? {
        val debits = (plan.series?.debits.orEmpty() + plan.mandateDebits).map { it.date }.sorted()
        for (revocation in plan.revocations) {
            val after = debits.firstOrNull { it.isAfter(revocation.receivedOn) } ?: continue
            val resubscribed = plan.mandates.any { !it.receivedOn.isBefore(revocation.receivedOn) && !it.receivedOn.isAfter(after) &&
                (it.receivedOn != revocation.receivedOn || (it.receivedAt != null && revocation.receivedAt != null && it.receivedAt.isAfter(revocation.receivedAt))) }
            if (!resubscribed) return after
        }
        return null
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
        val activeUntil: LocalDate? = null,
        val missedOn: LocalDate? = null,
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
        statedFrequency: Frequency?,
        lastCharge: LocalDate?,
        known: KnownMerchant?,
        today: LocalDate,
    ): Prediction {
        val mandate = plan.mandate
        val lastActivity = listOfNotNull(lastCharge, mandate?.receivedOn).maxOrNull()

        // Cancelled: a revocation newer than the latest mandate or charge. On the day of the latest
        // mandate the order of the two decides it (a plan change or a retried setup sends "revoked"
        // and "created" within minutes); with no times to go on, a same-day pair counts as the
        // mandate replacing the old one. On a charge day, a revocation still cancels.
        val revoked = plan.revocations.maxWithOrNull(compareBy({ it.receivedOn }, { it.receivedAt }))
        if (revoked != null && revocationEndsPlan(revoked, mandate, lastCharge, lastActivity)) {
            // The AutoPay is off, but what was already paid for still runs to the end of its period.
            val end = paidUntil(revoked, mandate, statedFrequency, lastCharge, known)
            val stillPaid = end?.isAfter(today) ?: (lastCharge != null && !lastCharge.isBefore(today.minusDays(UNKNOWN_PERIOD_GRACE_DAYS)))
            return if (stillPaid) Prediction(null, RenewalSource.MANDATE, Confidence.HIGH, SubscriptionStatus.ENDING, activeUntil = end)
            else Prediction(null, RenewalSource.MANDATE, Confidence.HIGH, SubscriptionStatus.CANCELLED)
        }
        if (mandate?.validTill?.isBefore(today) == true) {
            return Prediction(null, RenewalSource.MANDATE, Confidence.HIGH, SubscriptionStatus.LAPSED)
        }
        missedCharge(plan, statedFrequency, lastCharge, today)?.let { missed ->
            return Prediction(null, RenewalSource.MANDATE, Confidence.MEDIUM, SubscriptionStatus.LAPSED, missedOn = missed)
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

        // 5. A mandate with no date and no history: most likely a free trial — unless it has
        // already been paid for, which no free trial is.
        if (mandate != null) {
            if (lastCharge == null) known?.trialDays?.let { days ->
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
     * The charge a mandate should have produced and did not. Every UPI AutoPay debit is announced
     * by a pre-debit notice at least a day ahead and reported once made, so a stated billing date
     * passing [MISSED_CHARGE_GRACE_DAYS] ago with neither means the mandate has stopped — most
     * often replaced by another one (a plan change to the same app) whose revocation text never
     * came. Only for mandates whose period is actually stated; nothing is inferred from habit.
     */
    private fun missedCharge(plan: Plan, statedFrequency: Frequency?, lastCharge: LocalDate?, today: LocalDate): LocalDate? {
        val mandate = plan.mandate ?: return null
        val frequency = statedFrequency?.takeIf { it.hasSchedule } ?: return null
        // A plan that simply ends on a stated date is handled as a plan end, not a missed charge.
        if (mandate.validTill != null && !mandate.autoRenews) return null
        val expected = when {
            lastCharge != null -> frequency.nextOnOrAfter(lastCharge, lastCharge.plusDays(1))
            // A trial: the first charge is the day it converts.
            mandate.startDate?.isAfter(mandate.receivedOn) == true -> mandate.startDate
            // Otherwise the first payment is usually taken when the mandate is set up and often
            // never reported on its own (Claude Pro's ₹1,999 had no debit text), so the first
            // charge that can be missed is one period later.
            else -> (mandate.startDate ?: mandate.receivedOn).let { frequency.nextOnOrAfter(it, it.plusDays(1)) }
        }
        if (!expected.isBefore(today.minusDays(MISSED_CHARGE_GRACE_DAYS))) return null
        val announced = plan.notices.any { !it.debitDate.isBefore(expected.minusDays(1)) }
        return expected.takeUnless { announced }
    }

    /**
     * The last day a cancelled plan is still paid for, in order of trust: the date the cancellation
     * itself states ("Premium until 12 Nov"); the next billing date after the last charge, when
     * the period is actually stated somewhere; for a trial never charged, the day it would have
     * converted. Null when none of these is known — the period is never guessed from habit.
     */
    private fun paidUntil(
        revoked: MandateRevoked,
        mandate: MandateCreated?,
        statedFrequency: Frequency?,
        lastCharge: LocalDate?,
        known: KnownMerchant?,
    ): LocalDate? {
        revoked.activeUntil?.let { return it }
        if (lastCharge != null) {
            return statedFrequency?.takeIf { it.hasSchedule }?.nextOnOrAfter(lastCharge, lastCharge.plusDays(1))
        }
        return mandate?.takeIf { isTrialMandate(it, known) }?.startDate?.takeIf { it.isAfter(mandate.receivedOn) }
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

        /**
         * A cancelled plan with a recent charge but no stated period is shown as still paid for
         * (end date unknown, so the user is asked) for this long after that charge — the shortest
         * common paid period. Past it, nothing suggests it is still running.
         */
        private const val UNKNOWN_PERIOD_GRACE_DAYS = 31L

        /** Banks and services do not always charge on the exact day; this much late is still on time. */
        private const val MISSED_CHARGE_GRACE_DAYS = 3L
    }
}

/**
 * This subscription as no longer renewing — because the user said they cancelled it, or because
 * they told the app how long a cancelled plan's period is. What was paid for still runs to the
 * end of its period ([SubscriptionStatus.ENDING]) when that end is known and still ahead;
 * otherwise it is simply cancelled.
 */
fun Subscription.stoppedRenewing(today: LocalDate): Subscription {
    val end = activeUntil ?: lastCharged?.takeIf { frequencyKnown && frequency.hasSchedule }
        ?.let { frequency.nextOnOrAfter(it, it.plusDays(1)) }
    return if (end != null && end.isAfter(today)) copy(status = SubscriptionStatus.ENDING, activeUntil = end, nextRenewal = null)
    else copy(status = SubscriptionStatus.CANCELLED, activeUntil = null, nextRenewal = null)
}

/**
 * Where a name or mark the user saved under a bare channel key ("googleplay") belongs, now that a
 * channel's apps are separate plans keyed by amount ("googleplay#1999"). The user named the plan
 * the app showed then, which was always the one with the channel's newest mandate. Returns
 * [bareKey] itself when that key still exists (a channel with a single plan), and null while no
 * plan for the channel exists yet, e.g. before the SMS have been read again. (Channel plans are
 * now always keyed by amount, so the bare key only survives for data saved by older versions.)
 */
fun channelRekeyTarget(bareKey: String, subs: List<Subscription>): String? {
    if (subs.any { it.key == bareKey }) return bareKey
    return subs.filter { it.merchantId == bareKey }
        .maxByOrNull { sub ->
            sub.events.filterIsInstance<PaymentEvent.MandateCreated>()
                .maxOfOrNull { it.receivedAt ?: it.receivedOn.atStartOfDay() } ?: java.time.LocalDateTime.MIN
        }?.key
}

/** A newer AutoPay on a billing channel next to an older live one: an upgrade, or a second app? */
data class ReplacementQuestion(val older: Subscription, val newer: Subscription)

/**
 * On a billing channel a plan change (Netflix ₹499 monthly → ₹1,200 yearly through Google Play)
 * looks exactly like a second app: a new mandate at a new amount. When the old one's revocation
 * text does arrive, the old plan ends on its own; when it does not, only the user can say. One
 * question per older live plan, against the channel's newest live plan.
 */
fun List<Subscription>.replacementQuestions(): List<ReplacementQuestion> =
    filter { it.sharedBilling && it.isLive }
        .groupBy { it.merchantId }
        .values
        .filter { it.size >= 2 }
        .flatMap { plans ->
            val newest = plans.maxBy { it.newestMandateAt() }
            plans.filter { it !== newest }.map { ReplacementQuestion(older = it, newer = newest) }
        }

/** Dated after the mandate, or the same day and (when both times are known) later than it. */
private fun MandateRevoked.followsMandate(mandate: MandateCreated): Boolean = when {
    receivedOn.isAfter(mandate.receivedOn) -> true
    receivedOn.isBefore(mandate.receivedOn) -> false
    else -> receivedAt == null || mandate.receivedAt == null || receivedAt.isAfter(mandate.receivedAt)
}

/**
 * A live plan on a billing channel next to a cancellation text nothing could place. One question
 * per plan; only the user can say whether it was this AutoPay.
 */
fun List<Subscription>.revocationQuestions(): List<Subscription> =
    filter { it.sharedBilling && it.isLive && it.unplacedRevocationOn != null }

private fun Subscription.newestMandateAt(): java.time.LocalDateTime =
    events.filterIsInstance<PaymentEvent.MandateCreated>().maxOfOrNull { it.receivedAt ?: it.receivedOn.atStartOfDay() }
        ?: java.time.LocalDateTime.MIN

/**
 * What the app lists. An ended plan on a billing channel is an anonymous "Google Play ₹139,
 * cancelled": the bank text never named the app, and there is nothing left to act on. The engine
 * still reports it, so its status stays testable and its events stay stored.
 */
fun List<Subscription>.withoutEndedChannelPlans(): List<Subscription> =
    // A plan whose charge went unseen stays, so the user can say whether it is still active.
    filterNot { it.sharedBilling && !it.isInUse && it.missedChargeOn == null }

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
    filter { (it.status == SubscriptionStatus.CANCELLED || it.status == SubscriptionStatus.ENDING) && it.amountKnown && it.amount.currency == Money.INR }
        .fold(BigDecimal.ZERO) { acc, s -> acc + s.monthlyCost.amount.multiply(BigDecimal(12)) }
