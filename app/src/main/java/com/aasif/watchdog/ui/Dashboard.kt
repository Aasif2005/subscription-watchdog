@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.aasif.watchdog.ui

import com.aasif.watchdog.core.merchant.brandPackage
import com.aasif.watchdog.core.merchant.brandId
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aasif.watchdog.core.engine.ReplacementQuestion
import com.aasif.watchdog.core.engine.canSnoozeReminder
import com.aasif.watchdog.core.engine.daysUntil
import com.aasif.watchdog.core.engine.monthlyTotal
import com.aasif.watchdog.core.merchant.PlanPrices
import com.aasif.watchdog.core.model.Confidence
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Subscription
import com.aasif.watchdog.core.model.SubscriptionStatus
import com.aasif.watchdog.core.usage.ON_THIS_DEVICE
import com.aasif.watchdog.data.GmailImportProgress
import com.aasif.watchdog.ui.components.AppTile
import com.aasif.watchdog.ui.components.DangerButton
import com.aasif.watchdog.ui.components.DateField
import com.aasif.watchdog.ui.components.HeroCard
import com.aasif.watchdog.ui.components.ImportProgressStrip
import com.aasif.watchdog.ui.components.ManualFrequencies
import com.aasif.watchdog.ui.components.manualDateRange
import com.aasif.watchdog.ui.components.PillTone
import com.aasif.watchdog.ui.components.ProvideShimmer
import com.aasif.watchdog.ui.components.SectionLabel
import com.aasif.watchdog.ui.components.SegmentedChoice
import com.aasif.watchdog.ui.components.SkeletonBlock
import com.aasif.watchdog.ui.components.SkeletonPhase
import com.aasif.watchdog.ui.components.SoftCard
import com.aasif.watchdog.ui.components.StatusPill
import com.aasif.watchdog.ui.components.TextAction
import com.aasif.watchdog.ui.components.TonalButton
import com.aasif.watchdog.ui.components.rememberSkeletonPhase
import com.aasif.watchdog.ui.theme.Spacing
import com.aasif.watchdog.ui.theme.tabular
import com.aasif.watchdog.ui.theme.watchdog
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.delay

private val Indian = NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN"))

@Composable
fun DashboardScreen(
    subscriptions: List<Subscription>?,
    hasSmsAccess: Boolean,
    missingPermissions: List<PermissionItem>,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onRescan: () -> Unit,
    gmailImportEnabled: Boolean,
    gmailImporting: Boolean,
    onOpenProfile: () -> Unit,
    onSaveUserFact: (key: String, frequency: Frequency?, nextRenewal: LocalDate?, amount: BigDecimal?, currency: String?) -> Unit,
    onDismissUserFact: (key: String) -> Unit,
    onHide: (key: String) -> Unit,
    pendingQuestions: List<Subscription>,
    onKeep: (Subscription) -> Unit,
    onPlanCancel: (Subscription) -> Unit,
    onSnooze: (Subscription) -> Unit,
    usageHints: Map<String, String>,
    usageFigures: Map<String, UsageFigure>,
    autopayDue: Set<String>,
    onAutopayCancelled: (Subscription) -> Unit,
    onAutopayStillActive: (Subscription) -> Unit,
    replacementQuestions: List<ReplacementQuestion>,
    onReplaces: (ReplacementQuestion) -> Unit,
    onDifferentApp: (ReplacementQuestion) -> Unit,
    revocationQuestions: List<Subscription>,
    onRevocationYes: (Subscription) -> Unit,
    onRevocationNo: (Subscription) -> Unit,
    accountEmail: String?,
    gmailProgress: GmailImportProgress?,
    rescanning: Boolean,
    online: Boolean,
) {
    val canRescan = gmailImportEnabled || hasSmsAccess
    val listState = rememberLazyListState()
    // Extended at the top of the list; once scrolled it shrinks to its icon so it covers as little of a row as possible.
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 } }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { DashboardTopBar(canRescan, rescanning, onRescan, accountEmail, onOpenProfile) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                expanded = fabExpanded,
                icon = { Icon(WatchdogIcons.Add, contentDescription = if (fabExpanded) null else "Add a subscription", modifier = Modifier.size(22.dp)) },
                text = { Text("Add", style = MaterialTheme.typography.labelLarge) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                elevation = FloatingActionButtonDefaults.elevation(2.dp, 2.dp, 2.dp, 2.dp),
            )
        },
    ) { padding ->
        val phase = rememberSkeletonPhase(loading = subscriptions == null)
        Crossfade(phase, Modifier.fillMaxSize().padding(padding), label = "dashboard-phase") { shown ->
            when (shown) {
                SkeletonPhase.Waiting -> Box(Modifier.fillMaxSize())
                SkeletonPhase.Skeleton -> DashboardSkeleton()
                SkeletonPhase.Content -> {
                    val list = subscriptions.orEmpty()
                    val body: @Composable () -> Unit = {
                        DashboardList(
                            listState = listState,
                            subscriptions = list,
                            missingPermissions = missingPermissions,
                            onOpen = onOpen,
                            onAdd = onAdd,
                            gmailImportEnabled = gmailImportEnabled,
                            gmailImporting = gmailImporting,
                            onSaveUserFact = onSaveUserFact,
                            onDismissUserFact = onDismissUserFact,
                            onHide = onHide,
                            pendingQuestions = pendingQuestions,
                            onKeep = onKeep,
                            onPlanCancel = onPlanCancel,
                            onSnooze = onSnooze,
                            usageHints = usageHints,
                            usageFigures = usageFigures,
                            autopayDue = autopayDue,
                            onAutopayCancelled = onAutopayCancelled,
                            onAutopayStillActive = onAutopayStillActive,
                            replacementQuestions = replacementQuestions,
                            onReplaces = onReplaces,
                            onDifferentApp = onDifferentApp,
                            revocationQuestions = revocationQuestions,
                            onRevocationYes = onRevocationYes,
                            onRevocationNo = onRevocationNo,
                            gmailProgress = gmailProgress,
                            offline = !online && gmailImportEnabled,
                            smsSupported = hasSmsAccess,
                        )
                    }
                    if (canRescan) PullToRefreshBox(isRefreshing = rescanning, onRefresh = onRescan, modifier = Modifier.fillMaxSize()) { body() }
                    else body()
                }
            }
        }
    }
}

// ---- Top bar ----------------------------------------------------------------------------------------

@Composable
private fun DashboardTopBar(
    canRescan: Boolean,
    rescanning: Boolean,
    onRescan: () -> Unit,
    accountEmail: String?,
    onOpenProfile: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(start = Spacing.gutter, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Subscriptions", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
        if (canRescan) RescanControl(rescanning, onRescan)
        Box(
            Modifier.size(Spacing.touch + 4.dp).clip(CircleShape).clickable(onClick = onOpenProfile).clearAndSetSemantics { contentDescription = "Profile" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Text(
                    accountEmail?.firstOrNull()?.uppercase() ?: "•",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.watchdog.brandText,
                )
            }
        }
    }
}

/** Idle, spinning while a scan or read runs, then "Updated" for three seconds so the tap is never answered with silence. */
@Composable
private fun RescanControl(rescanning: Boolean, onRescan: () -> Unit) {
    var updated by remember { mutableStateOf(false) }
    var wasRescanning by remember { mutableStateOf(rescanning) }
    LaunchedEffect(rescanning) {
        if (wasRescanning && !rescanning) {
            updated = true
            delay(3_000)
            updated = false
        }
        wasRescanning = rescanning
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (updated) {
            Row(
                Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(WatchdogIcons.Check, null, tint = MaterialTheme.watchdog.brandText, modifier = Modifier.size(14.dp))
                Text("Updated", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.watchdog.brandText)
            }
        }
        val turn by rememberInfiniteTransition(label = "rescan").animateFloat(
            0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "rescan-angle",
        )
        IconButton(onClick = onRescan, enabled = !rescanning) {
            Icon(
                if (rescanning) WatchdogIcons.Spinner else WatchdogIcons.Refresh,
                contentDescription = if (rescanning) "Rescanning" else "Rescan for subscriptions",
                tint = if (rescanning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp).then(if (rescanning) Modifier.rotate(turn) else Modifier),
            )
        }
    }
}

// ---- List ----------------------------------------------------------------------------------------------

@Composable
private fun DashboardList(
    listState: LazyListState,
    subscriptions: List<Subscription>,
    missingPermissions: List<PermissionItem>,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    gmailImportEnabled: Boolean,
    gmailImporting: Boolean,
    onSaveUserFact: (key: String, frequency: Frequency?, nextRenewal: LocalDate?, amount: BigDecimal?, currency: String?) -> Unit,
    onDismissUserFact: (key: String) -> Unit,
    onHide: (key: String) -> Unit,
    pendingQuestions: List<Subscription>,
    onKeep: (Subscription) -> Unit,
    onPlanCancel: (Subscription) -> Unit,
    onSnooze: (Subscription) -> Unit,
    usageHints: Map<String, String>,
    usageFigures: Map<String, UsageFigure>,
    autopayDue: Set<String>,
    onAutopayCancelled: (Subscription) -> Unit,
    onAutopayStillActive: (Subscription) -> Unit,
    replacementQuestions: List<ReplacementQuestion>,
    onReplaces: (ReplacementQuestion) -> Unit,
    onDifferentApp: (ReplacementQuestion) -> Unit,
    revocationQuestions: List<Subscription>,
    onRevocationYes: (Subscription) -> Unit,
    onRevocationNo: (Subscription) -> Unit,
    gmailProgress: GmailImportProgress?,
    offline: Boolean,
    smsSupported: Boolean,
) {
    // A plan whose AutoPay is off but whose paid time has not run out is still in use.
    val live = subscriptions.filter { it.isInUse }
    val ended = subscriptions.filterNot { it.isInUse }
    // Cleared once a fact is saved (renewalSource flips to USER_SUPPLIED), so a card leaves
    // this list the moment it stops needing anything, without a separate "resolved" flag.
    val needsInput = live.filter { it.needsInput }
    val needsYou = pendingQuestions.size + replacementQuestions.size + revocationQuestions.size + needsInput.size
    val today = remember { LocalDate.now() }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.xs, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (offline) item(key = "offline") { OfflineBanner(smsSupported) }
        if (gmailImporting) {
            // A backfill that genuinely takes several minutes would look identical to a stuck one; this says so.
            item(key = "import") {
                val found = subscriptions.size
                val read = gmailProgress?.scanned?.takeIf { it > 0 }?.let { n ->
                    val total = gmailProgress.estimate?.takeIf { it >= n }
                    "Read ${Indian.format(n)}${total?.let { " of about ${Indian.format(it)}" } ?: ""} emails"
                }
                val found2 = if (found > 0) "$found found so far" else "Nothing found yet"
                ImportProgressStrip(
                    title = "Reading your Gmail history",
                    detail = listOfNotNull(read, found2).joinToString(" · ") +
                        ". Google Play payments appear once your mail shows which app they are for.",
                    fraction = gmailProgress?.fraction,
                )
            }
        }
        if (subscriptions.isEmpty()) {
            item(key = "empty") { EmptyState(missingPermissions, onAdd) }
            return@LazyColumn
        }
        item(key = "hero") { SpendHero(subscriptions, today) }
        if (needsYou > 0) {
            item(key = "section:needs") {
                SectionLabel("Needs you · $needsYou", Modifier.padding(top = Spacing.xs, start = 4.dp), color = MaterialTheme.watchdog.amber)
            }
            // First, because this is the point of the app: a charge is coming, and it is not too late.
            // Not "ask:": a renewal that is due and still needs input is in both lists, and a
            // shared key makes LazyColumn throw.
            items(pendingQuestions, key = { "question:${it.key}" }) { sub -> RenewalQuestionCard(sub, usageHints[sub.key], onKeep, onPlanCancel, onSnooze) }
            items(replacementQuestions, key = { "replace:${it.older.key}|${it.newer.key}" }) { q -> ReplacementCard(q, onReplaces, onDifferentApp) }
            items(revocationQuestions, key = { "revoked:${it.key}" }) { sub -> RevocationCard(sub, onRevocationYes, onRevocationNo) }
            items(needsInput, key = { "ask:${it.key}" }) { sub ->
                NeedsInputCard(
                    sub = sub,
                    onSave = { frequency, next, amount, currency -> onSaveUserFact(sub.key, frequency, next, amount, currency) },
                    onNotASubscription = { onHide(sub.key) },
                    onDismiss = { onDismissUserFact(sub.key) },
                )
            }
        }
        if (missingPermissions.isNotEmpty()) {
            item(key = "permissions") { TurnedOffCard(missingPermissions) }
        }
        if (live.isNotEmpty()) {
            item(key = "section:active") { SectionLabel("Active · ${live.size}", Modifier.padding(top = Spacing.xs, start = 4.dp)) }
        }
        items(live, key = { it.key }) { sub ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SubscriptionRow(sub, onClick = { onOpen(sub.key) }, barelyUsed = usageFigures[sub.key]?.barelyUsed == true)
                // Only close to a renewal, so the question is not on the dashboard all the time.
                if (sub.key in autopayDue) AutopayStrip(sub, onAutopayCancelled, onAutopayStillActive)
            }
        }
        if (gmailImporting) {
            // The rows still to come: the import is not finished, so the list below is not either.
            items(2, key = { "importing-row:$it" }) { ProvideShimmer { SkeletonRow(stagger = it * 0.1f) } }
        }
        if (ended.isNotEmpty()) {
            item(key = "section:ended") { SectionLabel("Ended · ${ended.size}", Modifier.padding(top = Spacing.xs, start = 4.dp)) }
        }
        items(ended, key = { it.key }) { SubscriptionRow(it, onClick = { onOpen(it.key) }) }
    }
}

// ---- Hero, week, rows -------------------------------------------------------------------------------

@Composable
private fun SpendHero(subscriptions: List<Subscription>, today: LocalDate) {
    val active = subscriptions.count { it.status == SubscriptionStatus.ACTIVE }
    val trials = subscriptions.count { it.status == SubscriptionStatus.TRIAL }
    val thisWeek = subscriptions.count { s -> s.isLive && s.nextRenewal?.let { daysUntil(it, today) in 0..7 } == true }
    val monthly = subscriptions.monthlyTotal()
    // Whole rupees: yearly plans divide into paise, and a figure that long pushes "/ month" onto a second line.
    val wholeMonthly = monthly.setScale(0, java.math.RoundingMode.HALF_UP)
    val wholeYearly = monthly.multiply(BigDecimal(12)).setScale(0, java.math.RoundingMode.HALF_UP)
    val w = MaterialTheme.watchdog
    HeroCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("You spend", style = MaterialTheme.typography.bodyMedium, color = w.heroInk2)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(formatRupees(wholeMonthly), style = MaterialTheme.typography.displayLarge.tabular(), color = w.heroInk, maxLines = 1, softWrap = false)
                    Text("/ month", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodyLarge, color = w.heroInk2, maxLines = 1, softWrap = false)
                }
                Text("${formatRupees(wholeYearly)} a year", style = MaterialTheme.typography.bodySmall.tabular(), color = w.heroInk2)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroChip("$active active")
                if (trials > 0) HeroChip("$trials free trial${if (trials > 1) "s" else ""}")
                if (thisWeek > 0) HeroChip("$thisWeek renewing this week")
            }
            val next = subscriptions.filter { it.isLive && it.nextRenewal?.isBefore(today) == false }.minByOrNull { it.nextRenewal!! }
            if (next != null) {
                androidx.compose.material3.HorizontalDivider(color = w.heroChip)
                val soon = daysUntil(next.nextRenewal!!, today) <= 3
                // Stacked, so a long name never pushes the "when" off the end.
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Next charge", style = MaterialTheme.typography.labelMedium, color = w.heroInk2)
                    Text(
                        "${next.name}${if (next.amountKnown) " · ${formatMoney(next.amount)}" else ""} · ${relativeDays(next.nextRenewal!!, today)}",
                        style = MaterialTheme.typography.titleSmall.tabular(),
                        // On the night band the usual rose is too dark to read; this is its light counterpart.
                        color = if (soon) Color(0xFFFFB3BF) else w.heroInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroChip(text: String) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.watchdog.heroChip).padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.watchdog.heroInk,
    )
}

@Composable
internal fun SubscriptionRow(sub: Subscription, onClick: () -> Unit, barelyUsed: Boolean = false) {
    val w = MaterialTheme.watchdog
    val renewsSoon = sub.isLive && sub.nextRenewal?.let { daysUntil(it, LocalDate.now()) in 0..3 } == true
    val ended = !sub.isInUse
    val lineColor = when {
        renewsSoon -> w.danger
        sub.status == SubscriptionStatus.TRIAL -> w.amber
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    SoftCard(Modifier.fillMaxWidth().alpha(if (ended) 0.78f else 1f), onClick = onClick, contentPadding = Spacing.md) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            AppTile(sub.name, unknown = sub.sharedBilling, brandId = sub.brandId(), appPackage = sub.brandPackage())
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(sub.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (sub.status == SubscriptionStatus.TRIAL) StatusPill("Trial", PillTone.Info)
                    if (sub.sharedBilling) StatusPill("Name it", PillTone.Amber)
                }
                Text(
                    sub.renewalLine() + if (sub.confidence == Confidence.LOW && sub.isLive) " (estimate)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = lineColor,
                )
                if (barelyUsed) {
                    Text("Barely used $ON_THIS_DEVICE", style = MaterialTheme.typography.labelSmall, color = w.amber)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    if (sub.amountKnown) formatMoney(sub.amount) else "?",
                    style = MaterialTheme.typography.titleSmall.tabular(),
                    textDecoration = if (ended) TextDecoration.LineThrough else null,
                )
                Text(if (sub.frequencyKnown) sub.frequency.label else "Unknown", style = MaterialTheme.typography.bodySmall, color = w.dim)
            }
        }
    }
}

// ---- Question cards -----------------------------------------------------------------------------------

/** One look for every question the app asks: a tinted icon says what kind it is, the actions are always at the bottom. */
@Composable
internal fun QuestionCard(
    icon: ImageVector,
    accent: Color,
    accentSoft: Color,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    SoftCard(modifier.fillMaxWidth(), contentPadding = Spacing.md) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(accentSoft), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            actions()
        }
    }
}

/** The dashboard's copy of the renewal reminder, so a notification that was ignored is not lost. */
@Composable
private fun RenewalQuestionCard(
    sub: Subscription,
    usageHint: String?,
    onKeep: (Subscription) -> Unit,
    onPlanCancel: (Subscription) -> Unit,
    onSnooze: (Subscription) -> Unit,
) {
    val today = LocalDate.now()
    val renewal = sub.nextRenewal ?: return
    val (title, _) = sub.reminderText(renewal)
    val price = if (sub.amountKnown) " · ${formatMoney(sub.amount)}" else ""
    val w = MaterialTheme.watchdog
    QuestionCard(
        icon = WatchdogIcons.Clock,
        accent = w.danger,
        accentSoft = w.dangerSoft,
        title = "$title$price",
        // Screen time says it was barely used: show that instead of the plain question.
        body = usageHint?.let { "$it You can still cancel before it is charged." } ?: "Still using it? You can still cancel before it is charged.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalButton("Keep", { onKeep(sub) }, Modifier.weight(1f))
                DangerButton("Cancel", { onPlanCancel(sub) }, Modifier.weight(1f))
            }
            // Snoozing past the charge would only hide the warning that matters.
            if (canSnoozeReminder(renewal, today)) TextAction("Tomorrow", { onSnooze(sub) }, Modifier.fillMaxWidth())
        }
    }
}

/**
 * A new AutoPay on Google Play next to an older live one. The bank texts look the same for a plan
 * change and for a second app, so the user is asked once instead of being shown two charges for
 * one subscription.
 */
@Composable
private fun ReplacementCard(q: ReplacementQuestion, onReplaces: (ReplacementQuestion) -> Unit, onDifferentApp: (ReplacementQuestion) -> Unit) {
    val newer = if (q.newer.amountKnown) formatMoney(q.newer.amount) else "a new amount"
    val older = if (q.older.amountKnown) formatMoney(q.older.amount) else "the older one"
    QuestionCard(
        icon = WatchdogIcons.Swap,
        accent = MaterialTheme.watchdog.brandText,
        accentSoft = MaterialTheme.colorScheme.primaryContainer,
        title = "New ${q.newer.name} AutoPay of $newer",
        body = "Does it replace the $older AutoPay (you changed your plan), or is it a different app?",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TonalButton("Replaces it", { onReplaces(q) }, Modifier.weight(1f))
            TonalButton("Different app", { onDifferentApp(q) }, Modifier.weight(1f))
        }
    }
}

/**
 * A bank text said an AutoPay was cancelled but not which, and this is one of the live ones on that
 * payee. Neither the mandate number, the amount nor a mail could place it, so the user decides.
 */
@Composable
private fun RevocationCard(sub: Subscription, onYes: (Subscription) -> Unit, onNo: (Subscription) -> Unit) {
    val on = sub.unplacedRevocationOn ?: return
    val price = if (sub.amountKnown) " (${formatMoney(sub.amount)})" else ""
    QuestionCard(
        icon = WatchdogIcons.Cancelled,
        accent = MaterialTheme.watchdog.info,
        accentSoft = MaterialTheme.watchdog.infoSoft,
        title = "Did you cancel ${sub.name}$price?",
        body = "Your bank texted on ${formatDate(on)} that an AutoPay was cancelled, but not which one.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TonalButton("Yes, cancelled", { onYes(sub) }, Modifier.weight(1f))
            TonalButton("Still active", { onNo(sub) }, Modifier.weight(1f))
        }
    }
}

/** Nothing in the texts or mail says this subscription's AutoPay ended, so ask: on Detail always, on the dashboard in the last days. */
@Composable
internal fun AutopayStrip(sub: Subscription, onCancelled: (Subscription) -> Unit, onStillActive: (Subscription) -> Unit) {
    SoftCard(Modifier.fillMaxWidth(), contentPadding = Spacing.md) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("Have you cancelled the AutoPay?", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TonalButton("Yes, cancelled", { onCancelled(sub) })
                TextAction("No, still active", { onStillActive(sub) })
            }
        }
    }
}

/**
 * A subscription the app is confident is real but can't fully describe — the running example
 * being a plan whose renewal date lives only inside its own app (NordVPN), never in an SMS or
 * e-mail. Collapsed to one line until tapped, so a full inbox of these doesn't take over the
 * dashboard.
 */
@Composable
private fun NeedsInputCard(
    sub: Subscription,
    onSave: (frequency: Frequency?, nextRenewal: LocalDate?, amount: BigDecimal?, currency: String?) -> Unit,
    onNotASubscription: () -> Unit,
    onDismiss: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var amountText by remember { mutableStateOf(if (sub.amountKnown) sub.amount.amount.toPlainString() else "") }
    var renewal by remember { mutableStateOf(sub.nextRenewal) }
    // Nothing stated the period: the price list may suggest one, for the user to check. Without
    // a suggestion, monthly is only the default chip, said as such below.
    val suggestion = if (!sub.frequencyKnown && sub.amountKnown) PlanPrices.suggest(sub.merchantId, sub.amount) else null
    var frequency by remember { mutableStateOf(if (sub.frequencyKnown) sub.frequency else suggestion?.frequency ?: Frequency.MONTHLY) }

    val missing = buildString {
        if (sub.nextRenewal == null) append("Renewal date")
        if (!sub.amountKnown) { if (isNotEmpty()) append(" and "); append("amount") }
    }.ifEmpty { "Details" }

    val w = MaterialTheme.watchdog
    SoftCard(Modifier.fillMaxWidth(), contentPadding = 0.dp) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(w.amberSoft), contentAlignment = Alignment.Center) {
                Icon(WatchdogIcons.Question, contentDescription = null, tint = w.amber, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(sub.name, style = MaterialTheme.typography.titleSmall)
                Text("$missing unknown — tap to fill in", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                if (expanded) WatchdogIcons.ChevronDown else WatchdogIcons.ChevronRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            Column(Modifier.padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    amountText, { amountText = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.fillMaxWidth(),
                    label = { Text("Amount (${sub.amount.currency})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = MaterialTheme.shapes.medium,
                )
                Text("How often", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!sub.frequencyKnown) {
                    Text(
                        suggestion?.let { "${formatMoney(sub.amount)} matches ${sub.name} ${it.label} (${it.frequency.label.lowercase()}) — check it's yours." }
                            ?: "Nothing said how often this is charged. Pick the plan you chose.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SegmentedChoice(ManualFrequencies, frequency, { it.label }, { frequency = it })
                DateField("Next renewal", renewal, { renewal = it }, range = remember { manualDateRange() })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TonalButton("Save", {
                        val amt = amountText.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
                        onSave(frequency, renewal, amt, sub.amount.currency)
                        expanded = false
                    })
                    TextAction("Not a subscription", onNotASubscription)
                }
                TextAction("Don't ask me about this", onDismiss)
            }
        }
    }
}

// ---- Banners, empty state, permissions ------------------------------------------------------------------

@Composable
private fun OfflineBanner(smsSupported: Boolean) {
    val w = MaterialTheme.watchdog
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = w.amberSoft) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Icon(WatchdogIcons.WifiOff, contentDescription = null, tint = w.amber, modifier = Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("You're offline", style = MaterialTheme.typography.titleSmall, color = w.amber)
                Text(
                    "What's shown is what was found last time. " +
                        (if (smsSupported) "Bank texts still scan; Gmail" else "Gmail") + " picks up when you're back online.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Nothing found yet: say so plainly and offer each source that is still off, instead of a bare sentence. */
@Composable
private fun EmptyState(missing: List<PermissionItem>, onAdd: () -> Unit) {
    val sources = missing.filter { it.id in setOf("gmail", "sms", "notification-access") }
    Column(Modifier.fillMaxWidth().padding(top = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text("Nothing found yet", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (sources.isEmpty()) "Subscriptions appear here as bank texts, receipts and payment alerts arrive. You can also add one yourself."
                else "Give Watchdog somewhere to look. Each source finds different subscriptions.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sources.isNotEmpty()) {
            SoftCard(Modifier.fillMaxWidth(), contentPadding = 0.dp) {
                sources.forEachIndexed { k, item ->
                    PermissionRow(item)
                    if (k < sources.lastIndex) androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        TonalButton("Add one yourself", onAdd, Modifier.fillMaxWidth(), leading = WatchdogIcons.Add)
    }
}

@Composable
private fun PermissionRow(item: PermissionItem) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = item.onAllow).heightIn(min = 64.dp).padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall)
            Text(item.short, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(WatchdogIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TurnedOffCard(missing: List<PermissionItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Turned off", Modifier.padding(start = 4.dp, top = Spacing.xs))
        SoftCard(Modifier.fillMaxWidth(), contentPadding = 0.dp) {
            missing.forEachIndexed { k, item ->
                PermissionRow(item)
                if (k < missing.lastIndex) androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

// ---- Skeletons --------------------------------------------------------------------------------------------

/** Shaped like the loaded dashboard (hero, week strip, rows) so nothing moves when the real list replaces it. */
@Composable
private fun DashboardSkeleton() {
    ProvideShimmer {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = false).padding(horizontal = Spacing.gutter, vertical = Spacing.xs)
                .clearAndSetSemantics { contentDescription = "Loading your subscriptions" },
            verticalArrangement = Arrangement.spacedBy(Spacing.xl - 4.dp),
        ) {
            HeroCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SkeletonBlock(Modifier.width(72.dp).height(12.dp), onHero = true)
                    SkeletonBlock(Modifier.width(176.dp).height(36.dp), RoundedCornerShape(10.dp), onHero = true)
                    SkeletonBlock(Modifier.width(110.dp).height(12.dp), onHero = true, stagger = 0.05f)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        SkeletonBlock(Modifier.width(70.dp).height(28.dp), RoundedCornerShape(14.dp), onHero = true, stagger = 0.1f)
                        SkeletonBlock(Modifier.width(56.dp).height(28.dp), RoundedCornerShape(14.dp), onHero = true, stagger = 0.15f)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SkeletonBlock(Modifier.padding(start = 4.dp).width(90.dp).height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(7) { SkeletonBlock(Modifier.weight(1f).height(78.dp), RoundedCornerShape(12.dp), stagger = it * 0.06f) }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SkeletonBlock(Modifier.padding(start = 4.dp).width(110.dp).height(12.dp))
                repeat(5) { SkeletonRow(stagger = it * 0.09f) }
            }
        }
    }
}

/** One placeholder row; call inside [ProvideShimmer]. */
@Composable
private fun SkeletonRow(stagger: Float) {
    SoftCard(Modifier.fillMaxWidth().clearAndSetSemantics {}, contentPadding = Spacing.md) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SkeletonBlock(Modifier.size(38.dp), RoundedCornerShape(11.dp), stagger)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBlock(Modifier.fillMaxWidth(0.5f).height(13.dp), stagger = stagger)
                SkeletonBlock(Modifier.fillMaxWidth(0.72f).height(10.dp), stagger = stagger)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBlock(Modifier.width(52.dp).height(13.dp), stagger = stagger)
                SkeletonBlock(Modifier.width(38.dp).height(10.dp), stagger = stagger)
            }
        }
    }
}
