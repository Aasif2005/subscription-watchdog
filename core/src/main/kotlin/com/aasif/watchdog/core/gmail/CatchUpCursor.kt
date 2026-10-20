package com.aasif.watchdog.core.gmail

/**
 * Where one account's "mail since the last sync" read stands.
 *
 * Gmail lists newest first, so a read that is throttled or hits its cap has covered only the
 * newest part of the window. Restarting from the newest each time would re-read that same part
 * forever once a window holds more messages than one run can fetch. Instead the read walks
 * backwards, the way the history backfill does: [upperBound] is the oldest point reached so far,
 * and [target] is the cursor to adopt once the whole window is covered.
 */
data class CatchUpCursor(
    val lastSync: Long,
    val upperBound: Long? = null,
    val target: Long? = null,
)

sealed interface CatchUpStep {
    /** The window is covered; [lastSync] is the new cursor. */
    data class Done(val lastSync: Long) : CatchUpStep

    /** More of the window is left; read again below [upperBound], then adopt [target]. */
    data class Continue(val upperBound: Long, val target: Long) : CatchUpStep
}

/**
 * The next step after a read of [CatchUpCursor]'s window at [now]. [oldestScanned] is the oldest
 * message the read looked at, parsed or not.
 */
fun CatchUpCursor.next(now: Long, exhausted: Boolean, oldestScanned: Long?): CatchUpStep {
    val adopt = target ?: now
    if (exhausted) return CatchUpStep.Done(adopt)
    // Forced to move strictly backwards, so the walk always ends; a run that looked at nothing
    // has nowhere to continue from, and ending there beats re-reading the same messages forever.
    val nextBound = oldestScanned?.let { minOf(it, (upperBound ?: now) - 1_000L) }
    if (nextBound == null || nextBound <= lastSync) return CatchUpStep.Done(adopt)
    return CatchUpStep.Continue(nextBound, adopt)
}
