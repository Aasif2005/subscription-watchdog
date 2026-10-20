package com.aasif.watchdog.core.usage

import com.aasif.watchdog.core.merchant.MerchantNormalizer
import com.aasif.watchdog.core.model.PaymentEvent
import com.aasif.watchdog.core.model.Subscription
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** How much a kind of app is expected to be used; the floor is what "barely used" means for it, per 30 days on this device. */
enum class UsageCategory(val floorMinutesPer30Days: Int) {
    /** Video streaming. About a fifth of the lowest published monthly average for an Indian user (about five hours). */
    VIDEO(60),

    /** AI assistants, professional networks and the like: used in short sessions. */
    PRODUCTIVITY(30),

    /**
     * Music. Measured as time the app's playback service ran, not time its screen was open: music plays with the screen off, so
     * screen time would call a daily listener unused. A player's service can outlive a pause, so this over-counts a little; it
     * can never make an app look less used than it was.
     */
    AUDIO(60),
}

/** A subscription whose use can be watched: which merchant it is, and the app packages that count as using it. */
data class TrackedApp(val merchantId: String, val packages: List<String>, val category: UsageCategory) {
    /** Whether use is read as playback-service time rather than screen time. */
    val measuresPlayback: Boolean get() = category == UsageCategory.AUDIO
}

/**
 * The short, curated list of subscription apps whose use is read. Music apps are read as playback-service time (see
 * [UsageCategory.AUDIO]), since their screen time would always look unused.
 * Package names that could not be checked against a store listing are best effort; one that does not exist on a phone
 * simply means "not installed", and the plain reminder is used.
 */
object TrackedApps {
    val all: List<TrackedApp> = listOf(
        TrackedApp("netflix", listOf("com.netflix.mediaclient"), UsageCategory.VIDEO),
        TrackedApp("prime", listOf("com.amazon.avod.thirdpartyclient"), UsageCategory.VIDEO),
        TrackedApp("hotstar", listOf("in.startv.hotstar", "in.startv.hotstar.dplus"), UsageCategory.VIDEO),
        TrackedApp("sonyliv", listOf("com.sonyliv"), UsageCategory.VIDEO),
        TrackedApp("zee5", listOf("com.graymatrix.did"), UsageCategory.VIDEO),
        TrackedApp("youtube", listOf("com.google.android.youtube"), UsageCategory.VIDEO),
        TrackedApp("chatgpt", listOf("com.openai.chatgpt"), UsageCategory.PRODUCTIVITY),
        TrackedApp("claude", listOf("com.anthropic.claude"), UsageCategory.PRODUCTIVITY),
        TrackedApp("linkedin", listOf("com.linkedin.android"), UsageCategory.PRODUCTIVITY),
        TrackedApp("spotify", listOf("com.spotify.music"), UsageCategory.AUDIO),
        TrackedApp("applemusic", listOf("com.apple.android.music"), UsageCategory.AUDIO),
    )

    fun forMerchant(merchantId: String): TrackedApp? = all.firstOrNull { it.merchantId == merchantId }

    val packages: List<String> get() = all.flatMap { it.packages }.distinct()

    /** The packages whose time is read as playback-service time. */
    val playbackPackages: Set<String> get() = all.filter { it.measuresPlayback }.flatMap { it.packages }.toSet()
}

private val merchantNames = MerchantNormalizer()

/**
 * The tracked app this subscription's use can be read from, if any. A subscription the user added by hand has no merchant of
 * its own (its id is "manual:..."), so the name they typed is matched against the known services instead.
 */
fun Subscription.trackedApp(): TrackedApp? =
    TrackedApps.forMerchant(merchantId)
        ?: if (merchantId.startsWith("manual:")) merchantNames.findKnownIn(name)?.let { TrackedApps.forMerchant(it.id) } else null

/**
 * The app packages whose screen time is worth saving: those of subscriptions that will be charged again ([Subscription.isLive])
 * and renew on a schedule, for a service on the tracked list. A subscription that is cancelled, lapsed or unknown has none.
 */
fun usagePackagesToWatch(subscriptions: Collection<Subscription>, playbackSupported: Boolean = true): Set<String> =
    subscriptions.filter { it.isLive && it.frequency.hasSchedule }
        .mapNotNull { it.trackedApp() }
        // Without playback-service time (Android 10 and later) a music app would read as never used.
        .filter { playbackSupported || !it.measuresPlayback }
        .flatMap { it.packages }
        .toSet()

/** What the screen time since the last renewal says about whether the subscription is worth keeping. */
sealed interface UsageVerdict {
    /** Not enough watched days to say anything, or the app is not on this device. The plain reminder is used. */
    data object NoData : UsageVerdict

    data object Ok : UsageVerdict

    /**
     * The app was installed and never opened over the days that could be seen. [partial] says Android no longer held
     * the start of the period, so [from] is later than the renewal and the result may not describe all of it.
     */
    data class Unused(val from: LocalDate, val to: LocalDate, val partial: Boolean = false) : UsageVerdict

    /** Used, but less than the floor for that kind of app. [minutes] is what was seen from [from] to [to]; see [Unused.partial]. */
    data class Low(val minutes: Long, val from: LocalDate, val to: LocalDate, val partial: Boolean = false) : UsageVerdict
}

/**
 * How much an app was used over the watched days of a period, said as a plain figure. Makes no claim about whether it is enough.
 * [from] is the first day actually counted: later than the period's start when Android no longer held the days before it ([partial]).
 */
data class UsageSummary(val minutes: Long, val days: Int, val from: LocalDate, val to: LocalDate, val playback: Boolean, val partial: Boolean = false)

/** What was read for one subscription: when its current period began, the figure so far (null until a full day has passed) and whether it is low. */
data class UsageReading(val start: LocalDate, val summary: UsageSummary?, val verdict: UsageVerdict)

/**
 * The day the subscription's current period began, which is where its usage is counted from: the later of its last charge and the day its
 * newest real plan was taken (a plan taken on the 10th with no debit yet starts on the 10th), and never more than one billing period before
 * the next renewal. Null when nothing says.
 *
 * The one-period limit is left off for a plan that has not been charged yet: it has been in effect since the day it was taken, and its
 * renewal is only the first debit (ChatGPT Go taken on 5 Nov 2025 with its first debit on 5 Nov 2026 is counted from 5 Nov 2025, not
 * from a month before the debit). Once it has a charge, counting restarts at each debit.
 */
fun usagePeriodStart(sub: Subscription, renewal: LocalDate? = sub.nextRenewal): LocalDate? {
    val taken = sub.events.filterIsInstance<PaymentEvent.MandateCreated>().filterNot { it.announcementOnly }.maxOfOrNull { it.receivedOn }
    if (sub.lastCharged == null && taken != null) return taken
    val start = listOfNotNull(sub.lastCharged, taken).maxOrNull()
    val floor = renewal?.takeIf { sub.frequency.hasSchedule }?.let { sub.frequency.previous(it) }
    return when {
        start == null -> floor
        floor == null -> start
        else -> maxOf(start, floor)
    }
}

/** The total over the days that were watched from [periodStart] to yesterday (whatever their number, unlike [usageVerdict]); null when none were. */
fun usageSummary(spans: List<UsageSpan>, periodStart: LocalDate, today: LocalDate, playback: Boolean): UsageSummary? {
    val to = today.minusDays(1)
    if (to.isBefore(periodStart)) return null
    val kept = countedSpans(spans, periodStart, to)
    if (kept.isEmpty()) return null
    val covered = kept.sumOf { it.days }
    val period = ChronoUnit.DAYS.between(periodStart, to) + 1
    return UsageSummary(
        minutes = kept.sumOf { it.millis } / 60_000,
        days = covered,
        from = kept.minOf { it.firstDay },
        to = kept.maxOf { it.lastDay },
        playback = playback,
        partial = covered < period - 1,
    )
}

/** Fewest watched days before anything is claimed. */
const val MIN_USAGE_DAYS = 14

/** At least this share of the days between the first and last watched day must have been watched, so a phone that was off does not read as "unused". */
private const val MIN_COVERAGE = 0.8

/**
 * One stretch of watched time: [days] days starting at [firstDay] (a single finished day, or a total Android kept for
 * a week or a month), with the foreground time seen over all of it. A one-day span is a day row; longer ones come from
 * [residualSpans].
 */
data class UsageSpan(val firstDay: LocalDate, val days: Int, val millis: Long) {
    val lastDay: LocalDate get() = firstDay.plusDays(days - 1L)
}

/** [UsageSpan] for a stretch that began at [startMillis] and ended at [endMillis]: the day it belongs to and how many days it is. */
fun usageSpan(startMillis: Long, endMillis: Long, millis: Long, zone: java.time.ZoneId): UsageSpan {
    val days = Math.round((endMillis - startMillis) / DAY_MILLIS.toDouble()).toInt().coerceAtLeast(1)
    return UsageSpan(usageDayLabel(startMillis, zone), days, millis)
}

/**
 * The whole period since the last renewal, not a recent stretch against a usual one: the total minutes on the
 * watched days from [lastRenewal] to yesterday, against the category's floor scaled to the days actually watched.
 * [spans] hold only time that was watched, so a stretch Android no longer had is unknown, never zero; a zero day for
 * an app that is installed is a real zero. A span that does not lie inside the period, or that overlaps a finer one
 * already counted, is left out so no minute is counted twice.
 *
 * When the start of the period could not be seen, the verdict covers the days that could be, and says so ([partial]).
 */
fun usageVerdict(
    spans: List<UsageSpan>,
    lastRenewal: LocalDate,
    today: LocalDate,
    category: UsageCategory,
    minDays: Int = MIN_USAGE_DAYS,
): UsageVerdict {
    val to = today.minusDays(1)
    if (to.isBefore(lastRenewal)) return UsageVerdict.NoData
    val kept = countedSpans(spans, lastRenewal, to)
    val covered = kept.sumOf { it.days }
    if (kept.isEmpty() || covered < minDays) return UsageVerdict.NoData
    val from = kept.minOf { it.firstDay }
    val last = kept.maxOf { it.lastDay }
    val window = ChronoUnit.DAYS.between(from, last) + 1
    if (covered < window * MIN_COVERAGE) return UsageVerdict.NoData
    val period = ChronoUnit.DAYS.between(lastRenewal, to) + 1
    val partial = covered < period - 1
    // Kept in milliseconds: rounding each day to minutes would turn a little real use into "no use".
    val total = kept.sumOf { it.millis }
    if (total == 0L) return UsageVerdict.Unused(from, last, partial)
    val floor = category.floorMinutesPer30Days * 60_000L * covered / 30.0
    return if (total < floor) UsageVerdict.Low(maxOf(1L, total / 60_000), from, last, partial) else UsageVerdict.Ok
}

/** The spans that count: inside [from]..[to], finest first, none sharing a day with one already taken. */
internal fun countedSpans(spans: List<UsageSpan>, from: LocalDate, to: LocalDate): List<UsageSpan> {
    val taken = HashSet<LocalDate>()
    val kept = ArrayList<UsageSpan>()
    for (span in spans.sortedBy { it.days }) {
        // A stretch longer than a day cannot be split, so it counts only when all of it lies inside the period (its first day is
        // already the one that holds most of its first bucket, see usageDayLabel).
        if (span.firstDay.isBefore(from) || span.lastDay.isAfter(to)) continue
        val days = (0 until span.days).map { span.firstDay.plusDays(it.toLong()) }
        if (days.any { it in taken }) continue
        taken += days
        kept += span
    }
    return kept
}
