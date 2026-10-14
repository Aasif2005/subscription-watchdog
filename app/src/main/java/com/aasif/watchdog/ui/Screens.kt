@file:OptIn(ExperimentalMaterial3Api::class)

package com.aasif.watchdog.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aasif.watchdog.core.engine.ReplacementQuestion
import com.aasif.watchdog.core.engine.canSnoozeReminder
import com.aasif.watchdog.core.engine.monthlyTotal
import com.aasif.watchdog.core.merchant.PlanPrices
import com.aasif.watchdog.core.model.Confidence
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.Subscription
import com.aasif.watchdog.core.model.SubscriptionStatus
import com.aasif.watchdog.data.ChargeSource
import com.aasif.watchdog.data.EventSource
import com.aasif.watchdog.data.EvidenceItem
import com.aasif.watchdog.data.GmailAccountStatus
import com.aasif.watchdog.data.NotificationStat
import com.aasif.watchdog.data.toLocalDateTime
import com.aasif.watchdog.data.WatchdogRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

// ---- Dashboard ----------------------------------------------------------------------------------

@Composable
fun DashboardScreen(
    subscriptions: List<Subscription>?,
    smsSupported: Boolean,
    hasSmsAccess: Boolean,
    onGrantSms: () -> Unit,
    hasNotificationAccess: Boolean,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onRescan: () -> Unit,
    onGrantNotificationAccess: () -> Unit,
    gmailImportEnabled: Boolean,
    gmailNeedsConsent: Boolean,
    gmailBackfillComplete: Boolean,
    onConnectGmail: () -> Unit,
    onOpenProfile: () -> Unit,
    onSaveUserFact: (key: String, frequency: Frequency?, nextRenewal: LocalDate?, amount: BigDecimal?, currency: String?) -> Unit,
    onDismissUserFact: (key: String) -> Unit,
    onHide: (key: String) -> Unit,
    pendingQuestions: List<Subscription>,
    onKeep: (Subscription) -> Unit,
    onPlanCancel: (Subscription) -> Unit,
    onSnooze: (Subscription) -> Unit,
    replacementQuestions: List<ReplacementQuestion>,
    onReplaces: (ReplacementQuestion) -> Unit,
    onDifferentApp: (ReplacementQuestion) -> Unit,
    revocationQuestions: List<Subscription>,
    onRevocationYes: (Subscription) -> Unit,
    onRevocationNo: (Subscription) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subscriptions") },
                actions = {
                    if (gmailImportEnabled || hasSmsAccess) TextButton(onClick = onRescan) { Text("Rescan") }
                    TextButton(onClick = onOpenProfile) { Text("Profile") }
                },
            )
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onAdd) { Text("Add manually") } },
    ) { padding ->
        if (subscriptions == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        // A plan whose AutoPay is off but whose paid time has not run out is still in use.
        val live = subscriptions.filter { it.isInUse }
        val ended = subscriptions.filterNot { it.isInUse }
        // Cleared once a fact is saved (renewalSource flips to USER_SUPPLIED), so a card leaves
        // this list the moment it stops needing anything, without a separate "resolved" flag.
        val needsInput = live.filter { it.needsInput }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // First, because this is the point of the app: a charge is coming, and it is not too late.
            // Not "ask:": a renewal that is due and still needs input is in both lists, and a
            // shared key makes LazyColumn throw.
            items(pendingQuestions, key = { "question:${it.key}" }) { sub -> RenewalQuestionCard(sub, onKeep, onPlanCancel, onSnooze) }
            items(replacementQuestions, key = { "replace:${it.older.key}|${it.newer.key}" }) { q -> ReplacementCard(q, onReplaces, onDifferentApp) }
            items(revocationQuestions, key = { "revoked:${it.key}" }) { sub -> RevocationCard(sub, onRevocationYes, onRevocationNo) }
            item { SummaryCard(subscriptions) }
            if (smsSupported && !hasSmsAccess) {
                item {
                    Card(Modifier.fillMaxWidth().clickable(onClick = onGrantSms)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Read bank SMS for AutoPay", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Banks text you when an AutoPay mandate is set up, cancelled, or about to be charged " +
                                    "— the exact renewal date lives there. Messages are read on this phone only; only " +
                                    "merchant, amount and dates are kept. Optional.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            if (!hasNotificationAccess) {
                item {
                    Card(Modifier.fillMaxWidth().clickable(onClick = onGrantNotificationAccess)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Catch AutoPay alerts from UPI apps", fontWeight = FontWeight.SemiBold)
                            Text(
                                "AutoPay renewal notices from GPay, PhonePe, Paytm and your bank app show up here. " +
                                    "Allow notification access so those are caught too. Only payment apps are read, on this phone.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            if (!gmailImportEnabled || gmailNeedsConsent) {
                item {
                    Card(Modifier.fillMaxWidth().clickable(onClick = onConnectGmail)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (gmailNeedsConsent) "Gmail access needs to be approved again" else "Import subscriptions from Gmail",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (gmailNeedsConsent) {
                                    "Google asked us to confirm access again. Tap to continue — nothing you've already found is lost."
                                } else {
                                    "Card and app-store subscriptions often send only an email receipt. " +
                                        "This is the one feature that uses the internet: Google is asked for read-only " +
                                        "access to your mail, and receipts are read on this phone. You can turn it down."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            } else if (!gmailBackfillComplete) {
                // Once connected, the card above stops showing so it doesn't get stuck telling a
                // connected user to connect. Nothing replaced it, so a backfill that genuinely
                // takes several minutes looked identical to a stuck one — there was no way to tell
                // from inside the app. This says so.
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Importing your Gmail history", fontWeight = FontWeight.SemiBold)
                            Text(
                                "This can take a few minutes on a large mailbox. Subscriptions found so far " +
                                    "are already shown below.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            if (needsInput.isNotEmpty()) {
                item { SectionHeader("Needs your input") }
                items(needsInput, key = { "ask:${it.key}" }) { sub ->
                    NeedsInputCard(
                        sub = sub,
                        onSave = { frequency, next, amount, currency -> onSaveUserFact(sub.key, frequency, next, amount, currency) },
                        onNotASubscription = { onHide(sub.key) },
                        onDismiss = { onDismissUserFact(sub.key) },
                    )
                }
            }
            if (subscriptions.isEmpty()) {
                item {
                    Text(
                        "Nothing here yet. Connect Gmail, read bank SMS or allow notification access to find subscriptions, or add one manually.",
                        Modifier.fillMaxWidth().padding(top = 32.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (live.isNotEmpty()) item { SectionHeader("Active") }
            items(live, key = { it.key }) { SubscriptionRow(it, onClick = { onOpen(it.key) }) }
            if (ended.isNotEmpty()) item { SectionHeader("Ended") }
            items(ended, key = { it.key }) { SubscriptionRow(it, onClick = { onOpen(it.key) }) }
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
    var dateText by remember { mutableStateOf(sub.nextRenewal?.toString() ?: "") }
    // Nothing stated the period: the price list may suggest one, for the user to check. Without
    // a suggestion, monthly is only the default chip, said as such below.
    val suggestion = if (!sub.frequencyKnown && sub.amountKnown) PlanPrices.suggest(sub.merchantId, sub.amount) else null
    var frequency by remember { mutableStateOf(if (sub.frequencyKnown) sub.frequency else suggestion?.frequency ?: Frequency.MONTHLY) }

    val missing = buildString {
        if (sub.nextRenewal == null) append("Renewal date")
        if (!sub.amountKnown) { if (isNotEmpty()) append(" and "); append("amount") }
    }.ifEmpty { "Details" }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp)) {
            Text(sub.name, style = MaterialTheme.typography.titleMedium)
            Text("$missing unknown — tap to fill in", style = MaterialTheme.typography.bodyMedium)
        }
        if (expanded) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    amountText, { amountText = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.fillMaxWidth(),
                    label = { Text("Amount (${sub.amount.currency})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text("How often", style = MaterialTheme.typography.labelLarge)
                if (!sub.frequencyKnown) {
                    Text(
                        suggestion?.let { "${formatMoney(sub.amount)} matches ${sub.name} ${it.label} (${it.frequency.label.lowercase()}) — check it's yours." }
                            ?: "Nothing said how often this is charged. Pick the plan you chose.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    manualFrequencies.forEach { f ->
                        FilterChip(selected = frequency == f, onClick = { frequency = f }, label = { Text(f.label) })
                    }
                }
                OutlinedTextField(
                    dateText, { dateText = it }, Modifier.fillMaxWidth(),
                    label = { Text("Next renewal (YYYY-MM-DD)") }, singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val amt = amountText.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
                        val next = dateText.trim().takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                        onSave(frequency, next, amt, sub.amount.currency)
                        expanded = false
                    }) { Text("Save") }
                    TextButton(onClick = onNotASubscription) { Text("Not a subscription") }
                }
                TextButton(onClick = onDismiss) { Text("Don't ask me about this") }
            }
        }
    }
}

@Composable
private fun SummaryCard(subscriptions: List<Subscription>) {
    val today = LocalDate.now()
    val active = subscriptions.count { it.status == SubscriptionStatus.ACTIVE }
    val trials = subscriptions.count { it.status == SubscriptionStatus.TRIAL }
    val thisWeek = subscriptions.count { s ->
        s.isLive && s.nextRenewal?.let { ChronoUnit.DAYS.between(today, it) in 0..7 } == true
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("You spend", style = MaterialTheme.typography.labelLarge)
            Text(
                "${formatRupees(subscriptions.monthlyTotal())} / month",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(buildString {
                append("$active active")
                if (trials > 0) append(" · $trials free trial${if (trials > 1) "s" else ""}")
                if (thisWeek > 0) append(" · $thisWeek renewing this week")
            })
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SubscriptionRow(sub: Subscription, onClick: () -> Unit) {
    val renewsSoon = sub.isLive && sub.nextRenewal?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) in 0..3 } == true
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(sub.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    sub.renewalLine() + if (sub.confidence == Confidence.LOW && sub.isLive) " (estimate)" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (renewsSoon || sub.status == SubscriptionStatus.TRIAL) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (sub.amountKnown) formatMoney(sub.amount) else "?", style = MaterialTheme.typography.titleMedium)
                Text(if (sub.frequencyKnown) sub.frequency.label else "Unknown", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ---- Detail -------------------------------------------------------------------------------------

@Composable
fun DetailScreen(
    sub: Subscription,
    isManual: Boolean,
    chargeSources: Map<String, ChargeSource>,
    evidence: List<EvidenceItem>,
    onName: (String) -> Unit,
    onBack: () -> Unit,
    onMarkCancelled: () -> Unit,
    onUndoCancel: () -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit,
    plannedCancelDate: LocalDate?,
    onKeepAfterAll: (plannedFor: LocalDate) -> Unit,
    onPlanLength: (Frequency) -> Unit,
    onStillActive: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sub.name) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (plannedCancelDate != null && sub.isLive) {
                CancellationGuide(sub, plannedCancelDate, onMarkCancelled, onKeep = { onKeepAfterAll(plannedCancelDate) })
            }
            val amountLabel = if (sub.amountKnown) formatMoney(sub.amount) else "Amount unknown"
            val frequencyLabel = if (sub.frequencyKnown) sub.frequency.label else "frequency unknown"
            Text("$amountLabel · $frequencyLabel", style = MaterialTheme.typography.headlineSmall)
            if (sub.amountKnown) {
                val yearly = Money(sub.monthlyCost.amount.multiply(BigDecimal(12)), sub.monthlyCost.currency)
                Text("${sub.category} · about ${formatMoney(sub.monthlyCost)} a month, ${formatMoney(yearly)} a year")
            } else {
                Text(sub.category)
            }
            sub.changedFrom?.takeIf { sub.isInUse }?.let { before ->
                val was = listOfNotNull(formatMoney(before.amount), before.frequency?.label).joinToString(" · ")
                Text("Changed on ${formatDate(before.changedOn)} from $was", style = MaterialTheme.typography.bodyMedium)
            }

            if (sub.status == SubscriptionStatus.ENDING && sub.activeUntil == null) {
                PlanLengthQuestion(sub, onPlanLength)
            }
            sub.missedChargeOn?.takeIf { sub.status == SubscriptionStatus.LAPSED }?.let { missed ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Is this still active?", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Its AutoPay was due on ${formatDate(missed)}, but no pre-debit notice and no charge arrived. " +
                                "It may have stopped, or been replaced by a new plan.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onMarkCancelled) { Text("It stopped") }
                            OutlinedButton(onClick = onStillActive) { Text("Still active") }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(sub.renewalLine(), style = MaterialTheme.typography.titleMedium)
                    if (sub.isLive && sub.nextRenewal != null) {
                        Text(sub.renewalSource.explanation(), style = MaterialTheme.typography.bodyMedium)
                        Text("Confidence: ${sub.confidence.name.lowercase()}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (sub.charges.isNotEmpty()) {
                Text("Payments detected", style = MaterialTheme.typography.titleSmall)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        sub.charges.sortedByDescending { it.date }.forEachIndexed { i, charge ->
                            if (i > 0) HorizontalDivider()
                            val source = chargeSources[WatchdogRepository.chargeKey(charge.date, charge.amount)]
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(formatDateTime(charge.date, source?.atMillis))
                                    if (source != null) {
                                        Text(
                                            "via ${source.label}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Text(formatMoney(charge.amount))
                            }
                        }
                    }
                }
            }

            if (sub.sharedBilling) {
                var typedName by remember(sub.key) { mutableStateOf("") }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Which app is this?", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Your bank only shows the store that collects the money, not the app behind it. " +
                                "Open the store's subscriptions list to see which app this ${formatMoney(sub.amount)} belongs to, then name it here.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (sub.cancelUrl != null) {
                            OutlinedButton(onClick = { uriHandler.openUri(sub.cancelUrl!!) }, Modifier.fillMaxWidth()) {
                                Text("Open the store's subscriptions")
                            }
                        }
                        OutlinedTextField(
                            value = typedName,
                            onValueChange = { typedName = it },
                            label = { Text("Name, e.g. Claude Pro") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = { onName(typedName) }, enabled = typedName.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                            Text("Save name")
                        }
                    }
                }
            }

            if (evidence.isNotEmpty()) {
                Text("Why this is here", style = MaterialTheme.typography.titleSmall)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        evidence.forEachIndexed { i, item ->
                            if (i > 0) HorizontalDivider()
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.kind)
                                    Text(
                                        "${item.source} · ${formatDateTime(item.date, item.atMillis)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                item.amount?.let { Text(formatMoney(it)) }
                            }
                        }
                        Text(
                            "Only these details are kept. The message text itself is never stored.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            if (sub.isLive && sub.cancelUrl != null && !sub.sharedBilling) {
                Button(onClick = { uriHandler.openUri(sub.cancelUrl!!) }, Modifier.fillMaxWidth()) {
                    Text("Open ${sub.name}'s cancel page")
                }
            }
            if (sub.isLive) {
                OutlinedButton(onClick = onMarkCancelled, Modifier.fillMaxWidth()) { Text("I've cancelled this") }
                Text(
                    "Also revoke the AutoPay in your UPI app (PhonePe, GPay, Paytm → AutoPay) so no further debits happen.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (sub.status == SubscriptionStatus.CANCELLED) {
                OutlinedButton(onClick = onUndoCancel, Modifier.fillMaxWidth()) { Text("Still active? Undo") }
            }
            if (isManual) {
                TextButton(onClick = onDelete, Modifier.fillMaxWidth()) { Text("Delete") }
            } else {
                TextButton(onClick = onHide, Modifier.fillMaxWidth()) { Text("Not a subscription — hide it") }
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
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("How long was this plan?", fontWeight = FontWeight.SemiBold)
            Text(
                "The AutoPay is cancelled, but your last payment${sub.lastCharged?.let { " on ${formatDate(it)}" } ?: ""} " +
                    "still covers some time. Tell the app the plan length to see when it ends.",
                style = MaterialTheme.typography.bodyMedium,
            )
            // Only a suggestion: the user's tap is what is saved.
            val suggestion = if (sub.amountKnown) PlanPrices.suggest(sub.merchantId, sub.amount) else null
            val lengths = listOf(Frequency.MONTHLY to "1 month", Frequency.QUARTERLY to "3 months", Frequency.YEARLY to "1 year")
            if (suggestion != null) {
                Text(
                    "${formatMoney(sub.amount)} matches ${sub.name} ${suggestion.label} (${suggestion.frequency.label.lowercase()}). Is that it?",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lengths.forEach { (f, label) ->
                    if (f == suggestion?.frequency) Button(onClick = { onAnswer(f) }) { Text(label) }
                    else OutlinedButton(onClick = { onAnswer(f) }) { Text(label) }
                }
            }
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
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val newer = if (q.newer.amountKnown) formatMoney(q.newer.amount) else "a new amount"
            val older = if (q.older.amountKnown) formatMoney(q.older.amount) else "the older one"
            Text("New ${q.newer.name} AutoPay of $newer", fontWeight = FontWeight.SemiBold)
            Text(
                "Does it replace the $older AutoPay (you changed your plan), or is it a different app?",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onReplaces(q) }) { Text("Replaces it") }
                OutlinedButton(onClick = { onDifferentApp(q) }) { Text("Different app") }
            }
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
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val price = if (sub.amountKnown) " (${formatMoney(sub.amount)})" else ""
            Text("Did you cancel ${sub.name}$price?", fontWeight = FontWeight.SemiBold)
            Text(
                "Your bank texted on ${formatDate(on)} that an AutoPay was cancelled, but not which one.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onYes(sub) }) { Text("Yes, cancelled") }
                OutlinedButton(onClick = { onNo(sub) }) { Text("Still active") }
            }
        }
    }
}

/** The dashboard's copy of the renewal reminder, so a notification that was ignored is not lost. */
@Composable
private fun RenewalQuestionCard(
    sub: Subscription,
    onKeep: (Subscription) -> Unit,
    onPlanCancel: (Subscription) -> Unit,
    onSnooze: (Subscription) -> Unit,
) {
    val today = LocalDate.now()
    val renewal = sub.nextRenewal ?: return
    val (title, _) = sub.reminderText(renewal)
    val price = if (sub.amountKnown) " · ${formatMoney(sub.amount)}" else ""
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$title$price", fontWeight = FontWeight.SemiBold)
            Text("Still using it? You can still cancel before it is charged.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onKeep(sub) }) { Text("Keep") }
                OutlinedButton(onClick = { onPlanCancel(sub) }) { Text("Cancel") }
                // Snoozing past the charge would only hide the warning that matters.
                if (canSnoozeReminder(renewal, today)) TextButton(onClick = { onSnooze(sub) }) { Text("Tomorrow") }
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
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("You planned to cancel before ${formatDate(before)}", style = MaterialTheme.typography.titleMedium)
            val cancelUrl = sub.cancelUrl
            if (cancelUrl != null) {
                Text("1. Cancel it with ${if (sub.sharedBilling) "the store" else sub.name}", fontWeight = FontWeight.SemiBold)
                Button(onClick = { uriHandler.openUri(cancelUrl) }, Modifier.fillMaxWidth()) {
                    Text(if (sub.sharedBilling) "Open the store's subscriptions" else "Open ${sub.name}'s cancel page")
                }
            } else {
                Text(
                    "1. Cancel it inside the ${sub.name} app or website — the app doesn't know its cancel page.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text("2. Stop the AutoPay, if it is paid that way", fontWeight = FontWeight.SemiBold)
            Text(
                "Cancelling in the service does not always revoke the UPI mandate. Open AutoPay in your UPI app and revoke it there.",
                style = MaterialTheme.typography.bodyMedium,
            )
            upiApps.forEach { (pkg, label) ->
                OutlinedButton(
                    onClick = { context.packageManager.getLaunchIntentForPackage(pkg)?.let(context::startActivity) },
                    Modifier.fillMaxWidth(),
                ) { Text("Open $label") }
            }
            Text("3. Tell the app", fontWeight = FontWeight.SemiBold)
            Button(onClick = onCancelled, Modifier.fillMaxWidth()) { Text("I've cancelled it") }
            TextButton(onClick = onKeep, Modifier.fillMaxWidth()) { Text("Changed my mind — keep it") }
        }
    }
}

// ---- Profile --------------------------------------------------------------------------------------

@Composable
fun ProfileScreen(
    gmailImportEnabled: Boolean,
    gmailAccounts: List<GmailAccountStatus>,
    onBack: () -> Unit,
    onConnectGmail: () -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (String) -> Unit,
    smsSupported: Boolean,
    onForceFullRescan: () -> Unit,
    hasNotificationAccess: Boolean,
    notificationStats: List<NotificationStat>,
    onGrantNotificationAccess: () -> Unit,
    onOpenReminderSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Gmail", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (gmailImportEnabled) {
                        Text(
                            "Read-only access to find subscription receipts. Only merchant, amount, dates and " +
                                "frequency are kept — the e-mail text is never stored.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        gmailAccounts.forEach { account ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(account.email, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        when {
                                            account.needsConsent -> "Needs your permission again"
                                            account.importing -> "Still catching up on your mail history — this can take a few minutes."
                                            else -> "Up to date"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                TextButton(onClick = { onRemoveAccount(account.email) }) { Text("Remove") }
                            }
                        }
                        OutlinedButton(onClick = onAddAccount) { Text("Add another account") }
                    } else {
                        Text("Not connected", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Connect a Gmail account to also find subscriptions that only ever send an e-mail receipt.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = onConnectGmail) { Text("Connect Gmail") }
                    }
                }
            }

            Text("Renewal reminders", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Reminders appear as a banner over whatever you are doing. If they only reach the notification " +
                            "shade, check that banners and pop-ups are allowed for this app. Unanswered reminders also " +
                            "stay at the top of the dashboard.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onOpenReminderSettings) { Text("Open reminder settings") }
                }
            }

            Text("Payment app notifications", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasNotificationAccess) {
                        Text(
                            "Access is on. Counts only — what the notifications say is never stored.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (notificationStats.isEmpty()) {
                            Text("No notification from a payment app has arrived yet.", style = MaterialTheme.typography.bodyMedium)
                        }
                        notificationStats.forEach { stat ->
                            Column {
                                Text(EventSource.paymentApps[stat.packageName] ?: "Debug shell", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${stat.seen} seen, ${stat.found} new payment alert${if (stat.found == 1) "" else "s"} found" +
                                        if (stat.lastSeenMillis > 0) ", last " + stat.lastSeenMillis.toLocalDateTime().toLocalDate() else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    } else {
                        Text(
                            "Off. Allow notification access to catch AutoPay alerts that UPI apps show as notifications.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = onGrantNotificationAccess) { Text("Allow notification access") }
                    }
                }
            }

            if (smsSupported) {
                Text("SMS scan", style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Re-reads every SMS on the phone from scratch, so a fix to how a merchant is " +
                                "recognised applies to charges already scanned, not just new ones.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = onForceFullRescan) { Text("Force full rescan") }
                    }
                }
            }
        }
    }
}

// ---- Add manually -------------------------------------------------------------------------------

private val manualFrequencies = listOf(Frequency.WEEKLY, Frequency.MONTHLY, Frequency.QUARTERLY, Frequency.YEARLY)

@Composable
fun AddManualScreen(
    onBack: () -> Unit,
    onSave: (name: String, amount: BigDecimal, frequency: Frequency, firstCharge: LocalDate, isTrial: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf(Frequency.MONTHLY) }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var isTrial by remember { mutableStateOf(false) }

    val parsedAmount = amount.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
    val parsedDate = try { LocalDate.parse(date) } catch (_: DateTimeParseException) { null }
    val valid = name.isNotBlank() && parsedAmount != null && parsedDate != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add subscription") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name (e.g. Gym, Netflix)") }, singleLine = true)
            OutlinedTextField(
                amount, { amount = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.fillMaxWidth(),
                label = { Text("Amount (₹)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Text("How often", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                manualFrequencies.forEach { f ->
                    FilterChip(selected = frequency == f, onClick = { frequency = f }, label = { Text(f.label) })
                }
            }
            OutlinedTextField(
                date, { date = it }, Modifier.fillMaxWidth(),
                label = { Text(if (isTrial) "Trial ends on (YYYY-MM-DD)" else "A charge date (YYYY-MM-DD)") },
                singleLine = true,
                isError = parsedDate == null,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(isTrial, { isTrial = it })
                Text("This is a free trial")
            }
            Button(
                onClick = { onSave(name.trim(), parsedAmount!!, frequency, parsedDate!!, isTrial) },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}
