package com.aasif.watchdog.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One parsed payment SMS. Only the extracted fields are stored, never the SMS text.
 * Dates and amounts are kept as ISO / plain strings so no type converters are needed.
 */
@Entity(tableName = "events")
data class EventEntity(
    /** sender | received day | body hash: the same SMS seen by the live receiver and a later inbox scan dedupes. */
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
)

private const val MANDATE = "MANDATE"
private const val PRE_DEBIT = "PRE_DEBIT"
private const val DEBIT = "DEBIT"
private const val REVOKED = "REVOKED"

fun PaymentEvent.toEntity(smsKey: String, source: String, receivedAtMillis: Long): EventEntity = when (this) {
    is PaymentEvent.MandateCreated -> EventEntity(
        smsKey, MANDATE, merchantId, merchantName, receivedOn.toString(), amount?.amount?.toPlainString(),
        date = null, frequency = frequency?.name, startDate = startDate?.toString(), validTill = validTill?.toString(), viaMandate = true,
        source = source, receivedAtMillis = receivedAtMillis, currency = amount?.currency ?: Money.INR, autoRenews = autoRenews,
    )
    is PaymentEvent.PreDebitNotice -> EventEntity(
        smsKey, PRE_DEBIT, merchantId, merchantName, receivedOn.toString(), amount?.amount?.toPlainString(),
        date = debitDate.toString(), frequency = null, startDate = null, validTill = null, viaMandate = true,
        source = source, receivedAtMillis = receivedAtMillis, currency = amount?.currency ?: Money.INR,
    )
    is PaymentEvent.Debit -> EventEntity(
        smsKey, DEBIT, merchantId, merchantName, receivedOn.toString(), amount.amount.toPlainString(),
        date = date.toString(), frequency = null, startDate = null, validTill = null, viaMandate = viaMandate,
        source = source, receivedAtMillis = receivedAtMillis, currency = amount.currency, peerToPeer = peerToPeer,
    )
    is PaymentEvent.MandateRevoked -> EventEntity(
        smsKey, REVOKED, merchantId, merchantName, receivedOn.toString(), amount = null,
        date = null, frequency = null, startDate = null, validTill = null, viaMandate = true,
        source = source, receivedAtMillis = receivedAtMillis,
    )
}

fun EventEntity.toEvent(): PaymentEvent? {
    val received = LocalDate.parse(receivedOn)
    val amt = amount?.let { Money(BigDecimal(it), currency) }
    return when (type) {
        MANDATE -> PaymentEvent.MandateCreated(
            merchantId, merchantName, received, amt,
            frequency?.let(Frequency::valueOf), startDate?.let(LocalDate::parse), validTill?.let(LocalDate::parse), autoRenews,
        )
        PRE_DEBIT -> PaymentEvent.PreDebitNotice(merchantId, merchantName, received, amt, LocalDate.parse(date ?: return null))
        DEBIT -> PaymentEvent.Debit(merchantId, merchantName, received, amt ?: return null, LocalDate.parse(date ?: return null), viaMandate, peerToPeer)
        REVOKED -> PaymentEvent.MandateRevoked(merchantId, merchantName, received)
        else -> null
    }
}
