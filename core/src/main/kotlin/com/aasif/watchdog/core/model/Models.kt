package com.aasif.watchdog.core.model

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** A raw SMS as read from the device inbox. */
data class Sms(
    val sender: String,
    val body: String,
    val receivedAt: LocalDateTime,
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
        val amount: BigDecimal?,
        val frequency: Frequency?,
        /** First scheduled debit. For free trials this is usually the day the trial converts. */
        val startDate: LocalDate?,
        val validTill: LocalDate?,
    ) : PaymentEvent

    /** RBI-mandated notice sent ~24h before a recurring debit, carrying the exact date. */
    data class PreDebitNotice(
        override val merchantId: String,
        override val merchantName: String,
        override val receivedOn: LocalDate,
        val amount: BigDecimal?,
        val debitDate: LocalDate,
    ) : PaymentEvent

    /** Money actually left the account. */
    data class Debit(
        override val merchantId: String,
        override val merchantName: String,
        override val receivedOn: LocalDate,
        val amount: BigDecimal,
        val date: LocalDate,
        /** The SMS explicitly said mandate / AutoPay / SI / recurring. */
        val viaMandate: Boolean,
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
enum class RenewalSource { PRE_DEBIT_NOTICE, MANDATE, PAYMENT_HISTORY, TRIAL_ESTIMATE, MANUAL }

enum class SubscriptionStatus {
    ACTIVE,

    /** Still in a free trial; the first paid charge is [Subscription.nextRenewal]. */
    TRIAL,

    /** No charge for more than two periods, so the plan has probably ended. */
    LAPSED,
    CANCELLED,
}

data class Charge(val date: LocalDate, val amount: BigDecimal)

data class Subscription(
    /** Stable key: merchant id plus amount bucket, so one merchant can have several plans. */
    val key: String,
    val merchantId: String,
    val name: String,
    val category: String,
    val amount: BigDecimal,
    val frequency: Frequency,
    val nextRenewal: LocalDate?,
    val renewalSource: RenewalSource,
    val confidence: Confidence,
    val status: SubscriptionStatus,
    val charges: List<Charge>,
    val cancelUrl: String?,
) {
    val lastCharged: LocalDate? get() = charges.maxOfOrNull { it.date }
    val monthlyCost: BigDecimal get() = frequency.monthlyEquivalent(amount)
    val isLive: Boolean get() = status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.TRIAL
}
