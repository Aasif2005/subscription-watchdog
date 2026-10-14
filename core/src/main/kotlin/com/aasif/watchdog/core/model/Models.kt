package com.aasif.watchdog.core.model

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * An amount with the currency it was stated in. Services billing from outside India (NordVPN,
 * GitHub) charge in dollars, and treating those figures as rupees would make the monthly total
 * nonsense, so the currency travels with the number instead of being assumed.
 */
data class Money(val amount: BigDecimal, val currency: String = INR) {
    operator fun compareTo(other: BigDecimal) = amount.compareTo(other)

    companion object {
        const val INR = "INR"
    }
}

/** A raw SMS as read from the device inbox. */
data class Sms(
    val sender: String,
    val body: String,
    val receivedAt: LocalDateTime,
    /**
     * For mail, the domain that actually signed the message (DKIM `header.d`). `From` can say
     * anything, so the signed domain is what the merchant is read from. Null for SMS and
     * notifications, which have no equivalent, and for mail with no usable signature.
     */
    val verifiedDomain: String? = null,
)

/**
 * Something the parser understood from one payment SMS. Every event carries the canonical
 * [merchantId] (from [com.aasif.watchdog.core.merchant.MerchantNormalizer]) so events for the
 * same service can be grouped even when banks spell the name differently.
 */
sealed interface PaymentEvent {
    val merchantId: String
    val merchantName: String
    val receivedOn: LocalDate

    /** A UPI AutoPay / card e-mandate / standing instruction was set up. */
    data class MandateCreated(
        override val merchantId: String,
        override val merchantName: String,
        override val receivedOn: LocalDate,
        val amount: Money?,
        val frequency: Frequency?,
        /** First scheduled debit. For free trials this is usually the day the trial converts. */
        val startDate: LocalDate?,
        /**
         * When the plan stops being paid for. Read from an explicit "valid till", or worked out
         * from a stated plan length ("3 months plan"), which is often all a receipt gives.
         */
        val validTill: LocalDate?,
        /** The mail said the plan renews by itself, so [validTill] is a charge, not just an end. */
        val autoRenews: Boolean = false,
        /** Exact arrival time when known; orders a mandate against a revocation received the same day. */
        val receivedAt: LocalDateTime? = null,
        /**
         * Read from a mail (a receipt, or a "welcome to Premium" note), not the bank. The plan
         * length a service states about its own plan is trusted over a bank's mandate frequency.
         */
        val fromMail: Boolean = false,
        /**
         * The mandate's own number (a UPI UMN) as a short hash, never the number itself. The same
         * number is on the text that creates, charges and revokes a mandate, which is the only
         * way to say which of several mandates to one payee a text is about.
         */
        val mandateRef: String? = null,
    ) : PaymentEvent {
        /** A "subscription started" mail with no amount: names and dates a plan, never makes one. */
        val announcementOnly: Boolean get() = fromMail && amount == null
    }

    /** RBI-mandated notice sent ~24h before a recurring debit, carrying the exact date. */
    data class PreDebitNotice(
        override val merchantId: String,
        override val merchantName: String,
        override val receivedOn: LocalDate,
        val amount: Money?,
        val debitDate: LocalDate,
        /**
         * The mandate's own number (a UPI UMN) as a short hash, never the number itself. The same
         * number is on the text that creates, charges and revokes a mandate, which is the only
         * way to say which of several mandates to one payee a text is about.
         */
        val mandateRef: String? = null,
    ) : PaymentEvent

    /** Money actually left the account. */
    data class Debit(
        override val merchantId: String,
        override val merchantName: String,
        override val receivedOn: LocalDate,
        val amount: Money,
        val date: LocalDate,
        /** The SMS explicitly said mandate / AutoPay / SI / recurring, or carried a UMN. */
        val viaMandate: Boolean,
        /** Paid to a person rather than a business, so it can never be a subscription. */
        val peerToPeer: Boolean = false,
        /**
         * The mandate's own number (a UPI UMN) as a short hash, never the number itself. The same
         * number is on the text that creates, charges and revokes a mandate, which is the only
         * way to say which of several mandates to one payee a text is about.
         */
        val mandateRef: String? = null,
    ) : PaymentEvent

    /** The mandate was revoked, cancelled or paused. */
    data class MandateRevoked(
        override val merchantId: String,
        override val merchantName: String,
        override val receivedOn: LocalDate,
        /** Exact arrival time when known, see [MandateCreated.receivedAt]. */
        val receivedAt: LocalDateTime? = null,
        /**
         * The mandate's amount, when the text states it. Banks usually repeat it ("mandate of
         * Rs 1,999 revoked"), and it is the only way to tell which of several mandates to the
         * same payee — every Google Play app shares one — was the one cancelled.
         */
        val amount: Money? = null,
        /** "Your Premium will end on 12 Nov": paid access that outlives the cancelled AutoPay. */
        val activeUntil: LocalDate? = null,
        /**
         * The mandate's own number (a UPI UMN) as a short hash, never the number itself. The same
         * number is on the text that creates, charges and revokes a mandate, which is the only
         * way to say which of several mandates to one payee a text is about.
         */
        val mandateRef: String? = null,
    ) : PaymentEvent
}

enum class Confidence { LOW, MEDIUM, HIGH }

/** Where the predicted renewal date came from, strongest first. */
enum class RenewalSource {
    /** The user told us, from a screen only they can see. Beats every guess. */
    USER_SUPPLIED,
    PRE_DEBIT_NOTICE,
    MANDATE,
    PAYMENT_HISTORY,

    /** A stated plan length ("3 months plan") ran out on this date. */
    PLAN_EXPIRY,
    TRIAL_ESTIMATE,
    MANUAL,
}

enum class SubscriptionStatus {
    ACTIVE,

    /** Still in a free trial; the first paid charge is [Subscription.nextRenewal]. */
    TRIAL,

    /** No charge for more than two periods, so the plan has probably ended. */
    LAPSED,
    CANCELLED,

    /**
     * The AutoPay was cancelled, but the period already paid for has not run out: still in use,
     * never charged again. [Subscription.activeUntil] is when it ends, if anything says so.
     */
    ENDING,
}

data class Charge(val date: LocalDate, val amount: Money)

data class Subscription(
    /** Stable key: merchant id plus amount bucket, so one merchant can have several plans. */
    val key: String,
    val merchantId: String,
    val name: String,
    val category: String,
    val amount: Money,
    val frequency: Frequency,
    val nextRenewal: LocalDate?,
    val renewalSource: RenewalSource,
    val confidence: Confidence,
    val status: SubscriptionStatus,
    val charges: List<Charge>,
    val cancelUrl: String?,
    /**
     * Whether [amount] and [frequency] were actually stated anywhere, or are the placeholders the
     * engine has to put in the fields. A plan we know nothing about used to appear as a confident
     * "₹0 monthly"; these two flags are what let the dashboard ask the user instead of pretending.
     */
    val amountKnown: Boolean = true,
    val frequencyKnown: Boolean = true,
    /**
     * The payee is a billing channel (Google Play, the App Store) that bills for many different
     * apps, so the bank's text cannot say which one this is.
     */
    val sharedBilling: Boolean = false,
    /**
     * The events this subscription was built from, and only those. The detail screen's "Why this
     * is here" list shows exactly these, so another plan's texts can never pass as its proof.
     */
    val events: List<PaymentEvent> = emptyList(),
    /** For an [SubscriptionStatus.ENDING] plan: the last day of the paid period, when known. */
    val activeUntil: LocalDate? = null,
    /**
     * A charge that landed after this plan's AutoPay was cancelled, with no new mandate in
     * between: money taken for something the user had stopped.
     */
    val chargedAfterCancel: LocalDate? = null,
    /**
     * The billing date its mandate stated, which passed with no pre-debit notice and no charge:
     * the plan has probably stopped, and the user is asked rather than told.
     */
    val missedChargeOn: LocalDate? = null,
    /**
     * A bank text after this plan's mandate said "an AutoPay was cancelled" without an amount or
     * a mandate number, on a payee that bills several apps: it may have been this one. The plan is
     * not ended on a guess; the user is asked.
     */
    val unplacedRevocationOn: LocalDate? = null,
) {
    val lastCharged: LocalDate? get() = charges.maxOfOrNull { it.date }
    val monthlyCost: Money get() = Money(frequency.monthlyEquivalent(amount.amount), amount.currency)
    /** Will be charged again: what reminders, totals and "renews on" are about. */
    val isLive: Boolean get() = status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.TRIAL

    /** Still usable today, including a cancelled plan whose paid period has not run out. */
    val isInUse: Boolean get() = isLive || status == SubscriptionStatus.ENDING

    /** Something the app cannot work out on its own and only the user can supply. */
    val needsInput: Boolean get() = isLive && (!amountKnown || !frequencyKnown || nextRenewal == null)
}
