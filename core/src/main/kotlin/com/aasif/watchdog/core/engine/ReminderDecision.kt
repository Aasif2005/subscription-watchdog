package com.aasif.watchdog.core.engine

import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.Subscription
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** What the user said when a renewal reminder asked whether they still want the subscription. */
enum class ReminderAnswer {
    /** "Keep it": no more reminders for this renewal. */
    KEEP,

    /** "Cancel it": the user means to cancel before the renewal, and the app checks that it worked. */
    CANCEL_PLANNED,
}

/** Everything remembered about one renewal's reminder. */
data class ReminderState(
    val answer: ReminderAnswer? = null,
    /** "Remind me tomorrow": silent until this date. */
    val snoozedUntil: LocalDate? = null,
    /** The latest [reminderStage] a notification was already shown for; null if none was. */
    val lastStage: Int? = null,
    /** The one same-day follow-up for a dismissed final reminder has already been sent. */
    val followedUp: Boolean = false,
)

/**
 * How urgent the reminder is: 1 while the renewal is still two or more days away, 2 the day
 * before, 3 on the day. A swiped notification is not an answer, so the reminder comes back at each
 * later stage until the user answers it.
 */
fun reminderStage(renewal: LocalDate, today: LocalDate): Int = when {
    ChronoUnit.DAYS.between(today, renewal) <= 0 -> FINAL_STAGE
    ChronoUnit.DAYS.between(today, renewal) == 1L -> DAY_BEFORE_STAGE
    else -> 1
}

/** The day-before reminder is held until this hour, so it lands in the evening, with the whole night left to act. */
const val EVENING_HOUR = 19

/**
 * Whether to show the renewal reminder now: not once the user has answered it, not while it is
 * snoozed, and once per stage otherwise. A pass that was missed (phone off) still shows the
 * reminder once, for whichever stage it has reached by then.
 *
 * The day-before stage waits for [EVENING_HOUR]: UPI AutoPay often debits early in the morning,
 * so a 9:00 reminder on the day before leaves less time to act than one the evening before.
 */
fun shouldRemind(now: LocalDateTime, renewal: LocalDate, state: ReminderState): Boolean {
    val today = now.toLocalDate()
    val stage = reminderStage(renewal, today)
    if (stage == DAY_BEFORE_STAGE && now.toLocalTime() < LocalTime.of(EVENING_HOUR, 0)) return false
    return state.answer == null &&
        (state.snoozedUntil == null || !today.isBefore(state.snoozedUntil)) &&
        (state.lastStage == null || stage > state.lastStage)
}

/**
 * Whether to nudge "verify that you have cancelled the AutoPay": the user said it was cancelled for [claimedRenewal],
 * and now it is the evening before that renewal. The last useful moment, since the bank's pre-debit notice comes a
 * day ahead and a mandate can be revoked until the debit. Once per renewal; later in the evening still counts, but
 * not once the renewal day has begun.
 */
fun shouldVerifyCancel(now: LocalDateTime, claimedRenewal: LocalDate, alreadySent: Boolean): Boolean =
    !alreadySent && now.toLocalDate() == claimedRenewal.minusDays(1) && now.toLocalTime() >= LocalTime.of(EVENING_HOUR, 0)

/**
 * Subscriptions whose renewal reminder, if one is still on screen, no longer applies: the renewal is no longer
 * within [leadDays], which is what a debit does (the next renewal moves a cycle on), and it is never "due" once
 * the AutoPay is cancelled or ended. A reminder the user has answered is taken down by the answer itself.
 */
fun List<Subscription>.reminderKeysToWithdraw(today: LocalDate, leadDays: Int): List<String> {
    val due = dueForReminder(today, leadDays).map { it.key }.toSet()
    return filter { it.key !in due }.map { it.key }
}

/** The reminder stage for the day before the renewal. */
const val DAY_BEFORE_STAGE = 2

/** The last stage: the renewal is today. */
const val FINAL_STAGE = 3

/**
 * Whether the final-day reminder should be repeated once because it was swiped away unanswered.
 * Only on the renewal day itself, only once per renewal, and never after an answer or during a
 * snooze: a second swipe is the user saying they have seen it, and nagging further is how an app
 * ends up with its notifications switched off.
 */
fun shouldFollowUp(today: LocalDate, renewal: LocalDate, state: ReminderState): Boolean =
    renewal == today &&
        state.answer == null &&
        (state.snoozedUntil == null || !today.isBefore(state.snoozedUntil)) &&
        state.lastStage == FINAL_STAGE &&
        !state.followedUp

/**
 * How long to wait before the follow-up: four hours, but never past 20:00, so it does not arrive
 * late at night. Null when too little of the evening is left to be worth sending.
 */
fun followUpDelay(now: LocalDateTime): Duration? {
    val untilEvening = Duration.between(now, now.toLocalDate().atTime(LocalTime.of(20, 0)))
    if (untilEvening < Duration.ofMinutes(30)) return null
    return minOf(Duration.ofHours(4), untilEvening)
}

/**
 * The user planned to cancel before [plannedFor] and a charge has still landed. A charge a day
 * earlier than the renewal date counts, since banks and apps do not always agree on the day; one
 * from the previous cycle is a month or more earlier and does not.
 *
 * The status is deliberately not consulted. "I've cancelled it" marks the subscription cancelled,
 * and a charge that lands after that is exactly the one worth warning about; the charges still
 * come from the bank and mail, whatever the mark says.
 */
fun chargedDespiteCancelPlan(subscription: Subscription, plannedFor: LocalDate): Boolean =
    subscription.lastCharged?.let { !it.isBefore(plannedFor.minusDays(1)) } == true

/**
 * Whether "remind me tomorrow" still leaves time to act. With the renewal today or tomorrow, the
 * snoozed reminder would come after the charge: UPI AutoPay often debits early in the morning,
 * and the daily reminder pass runs at 9:00.
 */
fun canSnoozeReminder(renewal: LocalDate, today: LocalDate): Boolean = ChronoUnit.DAYS.between(today, renewal) >= 2

/** The question is still open: not answered, and not snoozed past today. Whether a notification was shown is irrelevant. */
fun isAwaitingAnswer(today: LocalDate, state: ReminderState): Boolean =
    state.answer == null && (state.snoozedUntil == null || !today.isBefore(state.snoozedUntil))

/**
 * The subscriptions the dashboard should still be asking about: renewing within [leadDays] and
 * unanswered. Unlike [shouldRemind] this stays true after the notification has been shown, so a
 * reminder that was swiped away or ignored is not lost.
 */
fun List<Subscription>.pendingQuestions(
    today: LocalDate,
    leadDays: Int,
    stateOf: (Subscription, LocalDate) -> ReminderState,
): List<Subscription> =
    dueForReminder(today, leadDays).filter { sub -> sub.nextRenewal?.let { isAwaitingAnswer(today, stateOf(sub, it)) } == true }

/**
 * The user said this was cancelled, yet a pre-debit notice came in after that day, or a debit is dated after
 * it: the AutoPay may still be live. Returns the first such date. Strictly after, so a charge already in
 * flight on the day of the answer is not read as contradicting it.
 */
fun Subscription.evidenceAfterCancelClaim(claimedOn: LocalDate): LocalDate? =
    events.mapNotNull { event ->
        when (event) {
            is PaymentEvent.PreDebitNotice -> event.receivedOn
            is PaymentEvent.Debit -> event.date
            else -> null
        }
    }.filter { it.isAfter(claimedOn) }.minOrNull()
