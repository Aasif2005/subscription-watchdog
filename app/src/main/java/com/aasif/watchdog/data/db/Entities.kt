package com.aasif.watchdog.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import java.math.BigDecimal
import java.time.LocalDate
import com.aasif.watchdog.data.toLocalDateTime

/**
 * One parsed payment SMS. Only the extracted fields are stored, never the SMS text.
 * Dates and amounts are kept as ISO / plain strings so no type converters are needed.
 */
@Entity(tableName = "events")
data class EventEntity(
    /** sender | received day | body hash: the same message read twice (a re-sync, a re-posted notification) dedupes. */
    @PrimaryKey val smsKey: String,
    val type: String,
    val merchantId: String,
    val merchantName: String,
    val receivedOn: String,
    val amount: String?,
    /** Debit date, or the announced date for a pre-debit notice. */
    val date: String?,
    val frequency: String?,
    val startDate: String?,
    val validTill: String?,
    val viaMandate: Boolean,
    /** `channel:origin`, see [com.aasif.watchdog.data.EventSource]. Shown, never matched on. */
    val source: String = "",
    /** Arrival time. [receivedOn] keeps only the day, which is all the engine needs. 0 if unknown. */
    val receivedAtMillis: Long = 0,
    /** ISO 4217 code [amount] was stated in. Rows written before currencies were told apart are INR. */
    val currency: String = Money.INR,
    /** Paid to a person, not a business — never a subscription, whatever else it looks like. */
    val peerToPeer: Boolean = false,
    /** The mail said this plan renews by itself, so its [validTill] is a charge, not just an end. */
    val autoRenews: Boolean = false,
    /** Short hash of the mandate number in the text, see [PaymentEvent.MandateCreated.mandateRef]. */
    val mandateRef: String? = null,
    /** What the service's own receipt calls the plan, see [PaymentEvent.MandateCreated.planName]. */
    val planName: String? = null,
)

/** Foreground minutes of one tracked app on one finished day, kept because Android only holds about a week. */
@Entity(tableName = "app_usage_daily", primaryKeys = ["packageName", "day"])
data class AppUsageDayEntity(
    val packageName: String,
    /** ISO date, in the phone's time zone. */
    val day: String,
    val foregroundMillis: Long,
)

/**
 * Foreground time of one tracked app over a stretch longer than a day, taken once from the weekly, monthly or yearly totals
 * Android still held when watching began (the part of them no daily bucket covers).
 */
@Entity(tableName = "app_usage_span", primaryKeys = ["packageName", "startMillis"])
data class AppUsageSpanEntity(
    val packageName: String,
    val startMillis: Long,
    val endMillis: Long,
    val foregroundMillis: Long,
)

@Entity(tableName = "manual_subscriptions")
data class ManualSubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amount: String,
    val frequency: String,
    val firstCharge: String,
    val isTrial: Boolean,
)

/** The user's verdict on a detected subscription, keyed by [com.aasif.watchdog.core.model.Subscription.key]. */
@Entity(tableName = "user_marks")
data class UserMarkEntity(
    @PrimaryKey val subscriptionKey: String,
    val mark: String,
) {
    companion object {
        const val CANCELLED = "CANCELLED"
        const val HIDDEN = "HIDDEN"

        /**
         * The user said a newer AutoPay on the same billing channel replaces this one (a plan
         * change to the same app), so this is not a second subscription.
         */
        const val REPLACED = "REPLACED"
    }
}

/**
 * A fact the user supplied for one subscription from the "Needs your input" section, because
 * nothing on the device could find it — the running example being a plan whose renewal date
 * lives only inside its own app (NordVPN), never in an SMS or an e-mail.
 */
@Entity(tableName = "user_facts")
data class UserFactEntity(
    @PrimaryKey val subscriptionKey: String,
    val frequency: String?,
    /** ISO date. */
    val nextRenewal: String?,
    val amount: String?,
    val currency: String?,
    /** "This isn't right, but don't ask me about it again" — distinct from marking it not a subscription. */
    val dismissed: Boolean = false,
    /** What the user says this is. For a Google Play charge the bank text cannot name the app behind it. */
    val name: String? = null,
)

private const val MANDATE = "MANDATE"
private const val PRE_DEBIT = "PRE_DEBIT"
private const val DEBIT = "DEBIT"
private const val REVOKED = "REVOKED"

fun PaymentEvent.toEntity(smsKey: String, source: String, receivedAtMillis: Long): EventEntity = when (this) {
    is PaymentEvent.MandateCreated -> EventEntity(
        smsKey, MANDATE, merchantId, merchantName, receivedOn.toString(), amount?.amount?.toPlainString(),
        date = null, frequency = frequency?.name, startDate = startDate?.toString(), validTill = validTill?.toString(), viaMandate = true,
        source = source, receivedAtMillis = receivedAtMillis, currency = amount?.currency ?: Money.INR, autoRenews = autoRenews, mandateRef = mandateRef, planName = planName,
    )
    is PaymentEvent.PreDebitNotice -> EventEntity(
        smsKey, PRE_DEBIT, merchantId, merchantName, receivedOn.toString(), amount?.amount?.toPlainString(),
        date = debitDate.toString(), frequency = null, startDate = null, validTill = null, viaMandate = true,
        source = source, receivedAtMillis = receivedAtMillis, currency = amount?.currency ?: Money.INR, mandateRef = mandateRef,
    )
    is PaymentEvent.Debit -> EventEntity(
        smsKey, DEBIT, merchantId, merchantName, receivedOn.toString(), amount.amount.toPlainString(),
        date = date.toString(), frequency = null, startDate = null, validTill = null, viaMandate = viaMandate,
        source = source, receivedAtMillis = receivedAtMillis, currency = amount.currency, peerToPeer = peerToPeer, mandateRef = mandateRef,
    )
    // The revocation's own end-of-access date is kept in validTill, which a revocation otherwise
    // has no use for, so no migration was needed to add it.
    is PaymentEvent.MandateRevoked -> EventEntity(
        smsKey, REVOKED, merchantId, merchantName, receivedOn.toString(), amount?.amount?.toPlainString(),
        date = null, frequency = null, startDate = null, validTill = activeUntil?.toString(), viaMandate = true,
        source = source, receivedAtMillis = receivedAtMillis, currency = amount?.currency ?: Money.INR, mandateRef = mandateRef,
    )
}

fun EventEntity.toEvent(): PaymentEvent? {
    val received = LocalDate.parse(receivedOn)
    val amt = amount?.let { Money(BigDecimal(it), currency) }
    return when (type) {
        MANDATE -> PaymentEvent.MandateCreated(
            merchantId, merchantName, received, amt,
            frequency?.let(Frequency::valueOf), startDate?.let(LocalDate::parse), validTill?.let(LocalDate::parse), autoRenews,
            receivedAt = receivedAtMillis.takeIf { it > 0 }?.toLocalDateTime(),
            // Where it was read is already stored, so no column is needed for it.
            fromMail = source.startsWith("gmail:"),
            mandateRef = mandateRef,
            planName = planName,
        )
        PRE_DEBIT -> PaymentEvent.PreDebitNotice(merchantId, merchantName, received, amt, LocalDate.parse(date ?: return null), mandateRef)
        DEBIT -> PaymentEvent.Debit(merchantId, merchantName, received, amt ?: return null, LocalDate.parse(date ?: return null), viaMandate, peerToPeer, mandateRef)
        REVOKED -> PaymentEvent.MandateRevoked(
            merchantId, merchantName, received, receivedAtMillis.takeIf { it > 0 }?.toLocalDateTime(),
            amount = amt, activeUntil = validTill?.let(LocalDate::parse), mandateRef = mandateRef,
        )
        else -> null
    }
}
