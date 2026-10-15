package com.aasif.watchdog.core.engine

import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PaymentEvent
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** A stored event and whether it was read from mail rather than a bank text or app notification. */
data class SourcedEvent(val event: PaymentEvent, val fromMail: Boolean)

/** How far apart a bank text and the mail about the same purchase can arrive. */
private const val NAMING_WINDOW_DAYS = 1L

/** A store's receipt follows the bank's mandate text within minutes; 30 leaves room for slow mail. */
private const val LINK_WINDOW_MINUTES = 30L

private const val UNKNOWN_MERCHANT = "u:unknown"

/**
 * A bank text can only name the billing channel ("Google Play"), never the app behind it. Mail
 * can: a Play receipt, or the app's own "welcome to Premium" note, around the same day says which
 * app it was. When exactly one app's mail fits, the bank event is filed under that app instead of
 * the channel, so the two stop appearing as a mystery "Google Play subscription" next to the real
 * one. Returns the events in the same order, renamed where a match was found.
 *
 * A mail that states an amount must state the same one: a ₹99 bank debit is not named after a
 * ₹1,999 receipt that arrived the same morning. A "welcome" mail states none, which is why it only
 * names a mandate when it is the one such mail in the window — two apps welcoming the user the same
 * day leave the bank text unnamed rather than guessed.
 */
fun nameChannelEvents(events: List<SourcedEvent>, channels: Set<String>): List<PaymentEvent> {
    val mail = events.filter { it.fromMail && it.event.merchantId !in channels }.map { it.event }
    val byTime = if (mail.isEmpty()) events.map { it.event } else nameByTime(events, channels, mail)
    return nameByMandate(byTime, channels)
}

/** Files a bank event under the app a mail names for the same moment, see [nameChannelEvents]. */
private fun nameByTime(events: List<SourcedEvent>, channels: Set<String>, mail: List<PaymentEvent>): List<PaymentEvent> {
    return events.map { sourced ->
        val event = sourced.event
        if (event.merchantId !in channels || sourced.fromMail) return@map event
        val candidates = mail.filter { m ->
            sameKind(m, event) &&
                abs(ChronoUnit.DAYS.between(event.receivedOn, m.receivedOn)) <= NAMING_WINDOW_DAYS &&
                amountsAgree(m, event)
        }
        val named = candidates.map { it.merchantId }.distinct().singleOrNull()
            ?: nearInTime(event, candidates)
            ?: return@map event
        event.withMerchant(named, candidates.first { it.merchantId == named }.merchantName)
    }
}

/**
 * Two apps at the same price on the same day: the receipt arrives minutes after the mandate it
 * belongs to, so the one app whose mail is within [LINK_WINDOW_MINUTES] wins. Only a tie-breaker:
 * time alone never names a text, and with no arrival times nothing is named.
 */
private fun nearInTime(event: PaymentEvent, candidates: List<PaymentEvent>): String? {
    val at = event.arrivedAt() ?: return null
    return candidates
        .filter { c -> c.arrivedAt()?.let { abs(java.time.Duration.between(at, it).toMinutes()) <= LINK_WINDOW_MINUTES } == true }
        .map { it.merchantId }.distinct().singleOrNull()
}

private fun PaymentEvent.arrivedAt(): java.time.LocalDateTime? = when (this) {
    is PaymentEvent.MandateCreated -> receivedAt
    is PaymentEvent.MandateRevoked -> receivedAt
    else -> null
}

/**
 * A bank text for the same mandate carries the same mandate number, so once one of them has been
 * named for an app the rest are too: a revocation says only "Google Play" and no mail names it,
 * but it ends the mandate whose creation text a mail did name.
 */
private fun nameByMandate(events: List<PaymentEvent>, channels: Set<String>): List<PaymentEvent> {
    val byRef = events.filter { it.mandateRef() != null }.groupBy { it.mandateRef() }
    // One mandate has one amount and one app. A number that turns up on several, such as an id a
    // bank prints in every text, says nothing about which mandate a text belongs to.
    val appByRef = byRef.mapNotNull { (ref, same) ->
        val apps = same.filter { it.merchantId !in channels && it.merchantId != UNKNOWN_MERCHANT }.distinctBy { it.merchantId }
        val amounts = same.mapNotNull { it.statedAmount() }.map { it.currency to it.amount.setScale(0, java.math.RoundingMode.HALF_UP) }.distinct()
        if (apps.size == 1 && amounts.size <= 1) ref!! to apps.single() else null
    }.toMap()
    if (appByRef.isEmpty()) return events
    return events.map { e ->
        // A text that names no payee at all (some banks' revocations) is placed the same way.
        val app = if (e.merchantId in channels || e.merchantId == UNKNOWN_MERCHANT) e.mandateRef()?.let(appByRef::get) else null
        if (app == null) e else e.withMerchant(app.merchantId, app.merchantName)
    }
}

fun PaymentEvent.mandateRef(): String? = when (this) {
    is PaymentEvent.MandateCreated -> mandateRef
    is PaymentEvent.PreDebitNotice -> mandateRef
    is PaymentEvent.Debit -> mandateRef
    is PaymentEvent.MandateRevoked -> mandateRef
}

private fun sameKind(a: PaymentEvent, b: PaymentEvent) = a::class == b::class

private fun PaymentEvent.statedAmount(): Money? = when (this) {
    is PaymentEvent.MandateCreated -> amount
    is PaymentEvent.PreDebitNotice -> amount
    is PaymentEvent.Debit -> amount
    is PaymentEvent.MandateRevoked -> amount
}

/** The mail says nothing about the amount, or says the same one (scale ignored: "199" = "199.00"). */
private fun amountsAgree(mail: PaymentEvent, bank: PaymentEvent): Boolean {
    val m = mail.statedAmount() ?: return true
    val b = bank.statedAmount() ?: return false
    return m.currency == b.currency && m.amount.compareTo(b.amount) == 0
}

fun PaymentEvent.withMerchant(id: String, name: String): PaymentEvent = when (this) {
    is PaymentEvent.MandateCreated -> copy(merchantId = id, merchantName = name)
    is PaymentEvent.PreDebitNotice -> copy(merchantId = id, merchantName = name)
    is PaymentEvent.Debit -> copy(merchantId = id, merchantName = name)
    is PaymentEvent.MandateRevoked -> copy(merchantId = id, merchantName = name)
}
