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
    ) : PaymentEvent

    /** RBI-mandated notice sent ~24h before a recurring debit, carrying the exact date. */
    data class PreDebitNotice(
        override val merchantId: String,
        override val merchantName: String,
        override val receivedOn: LocalDate,
        val amount: Money?,
        val debitDate: LocalDate,
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
    ) : PaymentEvent

    /** The mandate was revoked, cancelled or paused. */
    data class MandateRevoked(
        override val merchantId: String,
        override val merchantName: String,
        override val receivedOn: LocalDate,
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
) {
    val lastCharged: LocalDate? get() = charges.maxOfOrNull { it.date }
    val monthlyCost: Money get() = Money(frequency.monthlyEquivalent(amount.amount), amount.currency)
    val isLive: Boolean get() = status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.TRIAL

    /** Something the app cannot work out on its own and only the user can supply. */
    val needsInput: Boolean get() = isLive && (!amountKnown || !frequencyKnown || nextRenewal == null)
}
