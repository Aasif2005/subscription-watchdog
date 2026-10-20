package com.aasif.watchdog.ui

import com.aasif.watchdog.core.merchant.brandPackage
import com.aasif.watchdog.core.merchant.brandId
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.aasif.watchdog.core.engine.daysUntil
import com.aasif.watchdog.core.engine.renewalProgress
import com.aasif.watchdog.core.merchant.PlanPrices
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.Subscription
import com.aasif.watchdog.core.model.SubscriptionStatus
import com.aasif.watchdog.core.usage.trackedApp
import com.aasif.watchdog.data.ChargeSource
import com.aasif.watchdog.data.DayUsage
import com.aasif.watchdog.core.usage.formatUsageMinutes
import com.aasif.watchdog.data.EventSource
import com.aasif.watchdog.data.EvidenceItem
import com.aasif.watchdog.data.WatchdogRepository
import com.aasif.watchdog.ui.components.AppTile
import com.aasif.watchdog.ui.components.PillTone
import com.aasif.watchdog.ui.components.PrimaryButton
import com.aasif.watchdog.ui.components.ProvideShimmer
import com.aasif.watchdog.ui.components.SectionLabel
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
import java.time.LocalDate

@Composable
fun DetailScreen(
    sub: Subscription,
    isManual: Boolean,
    chargeSources: Map<String, ChargeSource>,
    evidence: List<EvidenceItem>,
    onName: (String) -> Unit,
    onBack: () -> Unit,
    onMarkCancelled: () -> Unit,
    userCancelled: Boolean,
    onUndoCancel: () -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit,
    plannedCancelDate: LocalDate?,
    onKeepAfterAll: (plannedFor: LocalDate) -> Unit,
    onPlanLength: (Frequency) -> Unit,
    onStillActive: () -> Unit,
    autopayAsk: Boolean,
    onAutopayCancelled: (Subscription) -> Unit,
    onAutopayStillActive: (Subscription) -> Unit,
    usageFigure: UsageFigure? = null,
    /** The payments' sources and the proof are still being read; the rest of the page is already known. */
    loading: Boolean = false,
    /** The last 30 days of use, oldest first, for the chart. */
    usageDays: List<DayUsage> = emptyList(),
) {
    val uriHandler = LocalUriHandler.current
    val phase = rememberSkeletonPhase(loading)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { DetailTopBar(onBack) },
    ) { padding ->
        ProvideShimmer {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (plannedCancelDate != null && sub.isLive) {
                    CancellationGuide(sub, plannedCancelDate, onMarkCancelled, onKeep = { onKeepAfterAll(plannedCancelDate) })
                }
                DetailHeader(sub)

                sub.changedFrom?.takeIf { sub.isInUse }?.let { before ->
                    val was = listOfNotNull(formatMoney(before.amount), before.frequency?.label).joinToString(" · ")
                    Callout(WatchdogIcons.Swap, "Changed on ${formatDate(before.changedOn)} from $was")
                }
                sub.priceChange?.takeIf { sub.isInUse }?.let { change ->
                    Callout(
                        WatchdogIcons.TrendUp,
                        "Price went from ${formatMoney(change.from)} to ${formatMoney(change.to)} on ${formatDate(change.on)}",
                        tint = MaterialTheme.watchdog.amber, soft = MaterialTheme.watchdog.amberSoft,
                    )
                }

                if (autopayAsk) AutopayStrip(sub, onAutopayCancelled, onAutopayStillActive)
                if (sub.status == SubscriptionStatus.ENDING && sub.activeUntil == null) {
                    PlanLengthQuestion(sub, onPlanLength)
                }
                sub.missedChargeOn?.takeIf { sub.status == SubscriptionStatus.LAPSED }?.let { missed ->
                    QuestionCard(
                        icon = WatchdogIcons.Question,
                        accent = MaterialTheme.watchdog.amber,
                        accentSoft = MaterialTheme.watchdog.amberSoft,
                        title = "Is this still active?",
                        body = "Its AutoPay was due on ${formatDate(missed)}, but no pre-debit notice and no charge arrived. " +
                            "It may have stopped, or been replaced by a new plan.",
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TonalButton("It stopped", onMarkCancelled, Modifier.weight(1f))
                            TonalButton("Still active", onStillActive, Modifier.weight(1f))
                        }
                    }
                }

                RenewalCard(sub)

                usageFigure?.let { figure -> UsageCard(figure, usageDays, sub.trackedApp()?.measuresPlayback == true) }

                if (sub.charges.isNotEmpty()) PaymentsTimeline(sub, chargeSources, phase, loading)

                if (sub.sharedBilling) {
                    var typedName by remember(sub.key) { mutableStateOf("") }
                    SoftCard(Modifier.fillMaxWidth(), contentPadding = Spacing.lg) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Text("Which app is this?", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Your bank only shows the store that collects the money, not the app behind it. " +
                                    "Open the store's subscriptions list to see which app this ${formatMoney(sub.amount)} belongs to, then name it here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (sub.cancelUrl != null) {
                                TonalButton(
                                    "Open the store's subscriptions", { uriHandler.openUri(sub.cancelUrl!!) },
                                    Modifier.fillMaxWidth(), leading = WatchdogIcons.External,
                                )
                            }
                            OutlinedTextField(
                                value = typedName,
                                onValueChange = { typedName = it },
                                label = { Text("Name, e.g. Claude Pro") },
                                singleLine = true,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            PrimaryButton("Save name", { onName(typedName) }, Modifier.fillMaxWidth(), enabled = typedName.isNotBlank())
                        }
                    }
                }

                EvidenceAccordion(evidence, phase, loading)

                Column(Modifier.padding(top = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    if (sub.isLive && sub.cancelUrl != null && !sub.sharedBilling) {
                        PrimaryButton(
                            "Open ${sub.name}'s cancel page", { uriHandler.openUri(sub.cancelUrl!!) },
                            Modifier.fillMaxWidth(), leading = WatchdogIcons.External,
                        )
                    }
                    if (sub.status == SubscriptionStatus.CANCELLED || userCancelled) {
                        TonalButton("Still active? Undo", onUndoCancel, Modifier.fillMaxWidth())
                    }
                    if (isManual) {
                        TextAction("Delete", onDelete, Modifier.fillMaxWidth(), color = MaterialTheme.watchdog.danger)
                    } else {
                        TextAction("Not a subscription — hide it", onHide, Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(Spacing.md))
            }
        }
    }
}

@Composable
private fun DetailTopBar(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = Spacing.xs, top = Spacing.xs)) {
        IconButton(onClick = onBack) { Icon(WatchdogIcons.Back, contentDescription = "Back") }
    }
}

/** Tile, name, status tags and the price: everything that is known the moment the screen opens. */
@Composable
private fun DetailHeader(sub: Subscription) {
    val w = MaterialTheme.watchdog
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AppTile(sub.name, size = 56.dp, unknown = sub.sharedBilling, brandId = sub.brandId(), appPackage = sub.brandPackage())
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(sub.name, style = MaterialTheme.typography.headlineMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val (label, tone) = when (sub.status) {
                        SubscriptionStatus.ACTIVE -> "Active" to PillTone.Brand
                        SubscriptionStatus.TRIAL -> "Free trial" to PillTone.Info
                        SubscriptionStatus.ENDING -> "Ending" to PillTone.Amber
                        SubscriptionStatus.LAPSED -> "Maybe stopped" to PillTone.Amber
                        SubscriptionStatus.CANCELLED -> "Cancelled" to PillTone.Neutral
                    }
                    StatusPill(label, tone)
                    StatusPill(sub.category, PillTone.Neutral)
                }
            }
        }
        val amountLabel = if (sub.amountKnown) formatMoney(sub.amount) else "Amount unknown"
        val frequencyLabel = if (sub.frequencyKnown) sub.frequency.label else "frequency unknown"
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(amountLabel, style = MaterialTheme.typography.displayMedium.tabular())
                Text(frequencyLabel, Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (sub.amountKnown) {
                val yearly = Money(sub.monthlyCost.amount.multiply(BigDecimal(12)), sub.monthlyCost.currency)
                Text(
                    "about ${formatMoney(sub.monthlyCost)} a month, ${formatMoney(yearly)} a year",
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    color = w.dim,
                )
            }
        }
    }
}

@Composable
private fun Callout(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    soft: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    SoftCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(soft), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The last 30 days of use as one figure, a bar per day and two short facts. A day nothing was recorded for is a faint stub,
 * not a zero, so a gap in the data never reads as "not used".
 */
@Composable
private fun UsageCard(figure: UsageFigure, days: List<DayUsage>, playback: Boolean) {
    val w = MaterialTheme.watchdog
    val recorded = days.filter { it.minutes != null }
    val total = recorded.sumOf { it.minutes!! }
    val used = recorded.count { it.minutes!! > 0 }
    val busiest = recorded.maxByOrNull { it.minutes!! }?.takeIf { it.minutes!! > 0 }
    val bar = if (figure.barelyUsed) w.amber else MaterialTheme.colorScheme.primary
    val max = (recorded.maxOfOrNull { it.minutes!! } ?: 0L).coerceAtLeast(1L)
    SoftCard(Modifier.fillMaxWidth(), contentPadding = Spacing.lg) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(if (playback) "Listening, last 30 days" else "Screen time, last 30 days", Modifier.weight(1f))
                if (figure.barelyUsed) StatusPill("Barely used", PillTone.Amber) else StatusPill("This phone", PillTone.Neutral)
            }
            if (recorded.isEmpty()) {
                Text("No use recorded yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text(formatUsageMinutes(total), style = MaterialTheme.typography.headlineMedium.tabular())
            Row(
                Modifier.fillMaxWidth().height(64.dp).semantics(mergeDescendants = true) {
                    contentDescription = "Used on $used of ${recorded.size} recorded days, ${formatUsageMinutes(total)} in total"
                },
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                days.forEach { day ->
                    val minutes = day.minutes
                    val (height, colour) = when {
                        minutes == null -> 0.06f to MaterialTheme.colorScheme.outlineVariant
                        minutes == 0L -> 0.06f to MaterialTheme.colorScheme.surfaceVariant
                        else -> (minutes.toFloat() / max).coerceIn(0.12f, 1f) to bar
                    }
                    Box(Modifier.weight(1f).fillMaxHeight(height).clip(RoundedCornerShape(2.dp)).background(colour))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(days.first().date.format(dayMonth), style = MaterialTheme.typography.labelSmall, color = w.dim)
                Text("Today", style = MaterialTheme.typography.labelSmall, color = w.dim)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl), modifier = Modifier.padding(top = 4.dp)) {
                UsageFact("Days used", "$used of ${recorded.size}")
                if (busiest != null) UsageFact("Busiest day", "${formatUsageMinutes(busiest.minutes!!)} · ${busiest.date.format(dayMonth)}")
            }
        }
    }
}

private val dayMonth = java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH)

@Composable
private fun UsageFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.watchdog.dim)
        Text(value, style = MaterialTheme.typography.titleSmall.tabular())
    }
}

/** When the next charge is, how it was worked out, and how far through the period it is. */
@Composable
private fun RenewalCard(sub: Subscription) {
    val w = MaterialTheme.watchdog
    val today = LocalDate.now()
    val next = sub.nextRenewal
    val soon = sub.isLive && next != null && daysUntil(next, today) in 0..3
    val container = if (soon) w.dangerSoft else MaterialTheme.colorScheme.surface
    val accent = if (soon) w.danger else MaterialTheme.colorScheme.onSurface
    // Rose when a charge is close, otherwise the brand indigo; never a black bar.
    val bar = if (soon) w.danger else MaterialTheme.colorScheme.primary
    SoftCard(Modifier.fillMaxWidth(), container = container, outlined = !soon, contentPadding = Spacing.lg) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(sub.renewalLine(), style = MaterialTheme.typography.titleMedium, color = accent)
            // A trial's length is not the billing period, so only a paying plan shows how far through its period it is.
            val progress = if (sub.status == SubscriptionStatus.ACTIVE && next != null && sub.frequencyKnown) renewalProgress(next, sub.frequency, today) else null
            if (progress != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(bar.copy(alpha = 0.16f))
                            .semantics { stateDescription = "${progress.elapsed} of ${progress.length} days of this period" },
                    ) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.fraction).clip(CircleShape).background(bar))
                    }
                }
            }
            if (sub.isLive && next != null) {
                Text(
                    sub.renewalSource.explanation() + (sub.movedFrom?.let { " (moved from ${formatDate(it)})" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Every payment as a dated step on a line; where each was spotted fills in once it has been read. */
@Composable
private fun PaymentsTimeline(sub: Subscription, chargeSources: Map<String, ChargeSource>, phase: SkeletonPhase, loading: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Payments detected", Modifier.padding(start = 4.dp))
        SoftCard(Modifier.fillMaxWidth(), contentPadding = 0.dp) {
            val charges = sub.charges.sortedByDescending { it.date }
            charges.forEachIndexed { i, charge ->
                val source = chargeSources[WatchdogRepository.chargeKey(charge.date, charge.amount)]
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.padding(end = 14.dp).size(10.dp).clip(CircleShape).background(if (i == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(formatDateTime(charge.date, source?.atMillis), style = MaterialTheme.typography.bodyLarge)
                        when {
                            source != null -> Text("via ${source.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            loading && phase == SkeletonPhase.Skeleton -> SkeletonBlock(Modifier.width(120.dp).height(10.dp), stagger = i * 0.1f)
                            loading -> Spacer(Modifier.height(10.dp))
                        }
                    }
                    Text(formatMoney(charge.amount), style = MaterialTheme.typography.titleSmall.tabular())
                }
                if (i < charges.lastIndex) HorizontalDivider(Modifier.padding(horizontal = Spacing.lg), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** "Why this is here": the proof, folded away until asked for, with a skeleton while it is read. */
@Composable
private fun EvidenceAccordion(evidence: List<EvidenceItem>, phase: SkeletonPhase, loading: Boolean) {
    if (evidence.isEmpty() && !loading) return
    var open by remember { mutableStateOf(false) }
    SoftCard(Modifier.fillMaxWidth().animateContentSize(), contentPadding = 0.dp) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = evidence.isNotEmpty()) { open = !open }.padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(WatchdogIcons.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Why this is here", style = MaterialTheme.typography.titleSmall)
                if (evidence.isNotEmpty()) {
                    Text(
                        "${evidence.size} signal${if (evidence.size == 1) "" else "s"} from your texts, mail and alerts",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (phase == SkeletonPhase.Skeleton) {
                    SkeletonBlock(Modifier.width(150.dp).height(10.dp))
                } else {
                    Spacer(Modifier.height(10.dp))
                }
            }
            if (evidence.isNotEmpty()) {
                Icon(
                    if (open) WatchdogIcons.ChevronDown else WatchdogIcons.ChevronRight,
                    contentDescription = if (open) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
                )
            }
        }
        AnimatedVisibility(open && evidence.isNotEmpty()) {
            Column {
                evidence.forEach { item ->
                    HorizontalDivider(Modifier.padding(horizontal = Spacing.lg), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(item.kind, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${item.source} · ${formatDateTime(item.date, item.atMillis)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        item.amount?.let { Text(formatMoney(it), style = MaterialTheme.typography.titleSmall.tabular()) }
                    }
                }
                Text(
                    "Only these details are kept. The message text itself is never stored.",
                    Modifier.padding(horizontal = Spacing.lg).padding(bottom = Spacing.md, top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A cancelled plan that was paid for, but nothing said for how long: the user is the only source
 * left, and the answer is what dates the end of their access.
 */
@Composable
private fun PlanLengthQuestion(sub: Subscription, onAnswer: (Frequency) -> Unit) {
    // Only a suggestion: the user's tap is what is saved.
    val suggestion = if (sub.amountKnown) PlanPrices.suggest(sub.merchantId, sub.amount) else null
    val lengths = listOf(Frequency.MONTHLY to "1 month", Frequency.QUARTERLY to "3 months", Frequency.YEARLY to "1 year")
    QuestionCard(
        icon = WatchdogIcons.Clock,
        accent = MaterialTheme.watchdog.amber,
        accentSoft = MaterialTheme.watchdog.amberSoft,
        title = "How long was this plan?",
        body = "The AutoPay is cancelled, but your last payment${sub.lastCharged?.let { " on ${formatDate(it)}" } ?: ""} " +
            "still covers some time. Tell the app the plan length to see when it ends." +
            (suggestion?.let { " ${formatMoney(sub.amount)} matches ${sub.name} ${it.label} (${it.frequency.label.lowercase()}). Is that it?" } ?: ""),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            lengths.forEach { (f, label) ->
                TonalButton(label, { onAnswer(f) }, Modifier.weight(1f), leading = if (f == suggestion?.frequency) WatchdogIcons.Check else null)
            }
        }
    }
}

/**
 * What "Cancel" on a reminder leads to: the steps that actually stop the charge, in order. Only
 * steps the app can really offer are shown; with none, it says so rather than showing dead buttons.
 */
@Composable
private fun CancellationGuide(sub: Subscription, before: LocalDate, onCancelled: () -> Unit, onKeep: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    // Only apps that are installed, and only the payment ones that hold AutoPay mandates.
    val upiApps = remember {
        EventSource.paymentApps.filter { (pkg, _) -> context.packageManager.getLaunchIntentForPackage(pkg) != null }
    }
    SoftCard(Modifier.fillMaxWidth(), contentPadding = Spacing.lg) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text("You planned to cancel before ${formatDate(before)}", style = MaterialTheme.typography.titleLarge)
            val cancelUrl = sub.cancelUrl
            GuideStep(1, last = false) {
                if (cancelUrl != null) {
                    Text("Cancel it with ${if (sub.sharedBilling) "the store" else sub.name}", style = MaterialTheme.typography.titleSmall)
                    TonalButton(
                        if (sub.sharedBilling) "Open the store's subscriptions" else "Open ${sub.name}'s cancel page",
                        { uriHandler.openUri(cancelUrl) }, Modifier.fillMaxWidth(), leading = WatchdogIcons.External,
                    )
                } else {
                    Text("Cancel it inside the ${sub.name} app or website", style = MaterialTheme.typography.titleSmall)
                    Text("The app doesn't know its cancel page.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            GuideStep(2, last = false) {
                Text("Stop the AutoPay, if it is paid that way", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Cancelling in the service does not always revoke the UPI mandate. Open AutoPay in your UPI app and revoke it there.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                upiApps.forEach { (pkg, label) ->
                    TonalButton(
                        "Open $label",
                        { context.packageManager.getLaunchIntentForPackage(pkg)?.let(context::startActivity) },
                        Modifier.fillMaxWidth(), leading = WatchdogIcons.External,
                    )
                }
            }
            GuideStep(3, last = true) {
                Text("Tell the app", style = MaterialTheme.typography.titleSmall)
                PrimaryButton("I've cancelled the AutoPay", onCancelled, Modifier.fillMaxWidth())
                TextAction("Changed my mind — keep it", onKeep, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun GuideStep(number: Int, last: Boolean, content: @Composable () -> Unit) {
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).clearAndSetSemantics { contentDescription = "Step $number" },
                contentAlignment = Alignment.Center,
            ) { Text("$number", style = MaterialTheme.typography.labelLarge.tabular(), color = MaterialTheme.watchdog.brandText) }
            if (!last) Box(Modifier.padding(vertical = 4.dp).width(2.dp).weight(1f).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Column(Modifier.weight(1f).padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(Spacing.xs), content = { content() })
    }
}

// ---- Loading --------------------------------------------------------------------------------------------

/** What shows when the list itself has not loaded yet (a notification opened the app cold): the shape of the page, shimmering. */
@Composable
fun DetailSkeleton(onBack: () -> Unit) {
    // Shown only if loading outlasts a moment, so a quick load never flashes it.
    val phase = rememberSkeletonPhase(loading = true)
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { DetailTopBar(onBack) }) { padding ->
        if (phase != SkeletonPhase.Skeleton) return@Scaffold
        ProvideShimmer {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.gutter).clearAndSetSemantics { contentDescription = "Loading" },
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SkeletonBlock(Modifier.size(56.dp), RoundedCornerShape(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SkeletonBlock(Modifier.width(160.dp).height(22.dp), RoundedCornerShape(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SkeletonBlock(Modifier.width(52.dp).height(22.dp), RoundedCornerShape(6.dp), 0.05f)
                            SkeletonBlock(Modifier.width(78.dp).height(22.dp), RoundedCornerShape(6.dp), 0.1f)
                        }
                    }
                }
                SkeletonBlock(Modifier.width(190.dp).height(40.dp), RoundedCornerShape(10.dp), 0.1f)
                SkeletonBlock(Modifier.fillMaxWidth().height(110.dp), RoundedCornerShape(16.dp), 0.15f)
                SkeletonBlock(Modifier.fillMaxWidth().height(170.dp), RoundedCornerShape(16.dp), 0.2f)
            }
        }
    }
}
