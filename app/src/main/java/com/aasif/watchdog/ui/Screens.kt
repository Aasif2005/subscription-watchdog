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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aasif.watchdog.core.engine.monthlyTotal
import com.aasif.watchdog.core.model.Confidence
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Subscription
import com.aasif.watchdog.core.model.SubscriptionStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

// ---- Onboarding ---------------------------------------------------------------------------------

@Composable
fun OnboardingScreen(onResult: (smsGranted: Boolean) -> Unit) {
    val permissions = buildList {
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        onResult(result[Manifest.permission.READ_SMS] == true)
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text("Find every autopay you forgot about", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Subscription Watchdog reads the payment SMS from your bank and UPI apps to list every " +
                    "recurring charge (OTT, music, cloud storage, free trials that turn paid) and warns you before each renewal.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Your SMS never leave this phone", fontWeight = FontWeight.SemiBold)
                    Text("• Messages are read and understood on the device. Nothing is uploaded.")
                    Text("• Only payment details are kept: merchant, amount and date. The SMS text is never stored.")
                    Text("• Personal chats and OTPs are ignored.")
                }
            }
            Spacer(Modifier.weight(1f, fill = false))
            Button(onClick = { launcher.launch(permissions) }, modifier = Modifier.fillMaxWidth()) {
                Text("Allow SMS access")
            }
            TextButton(onClick = { onResult(false) }, modifier = Modifier.fillMaxWidth()) {
                Text("Skip — I'll add subscriptions myself")
            }
        }
    }
}

// ---- Dashboard ----------------------------------------------------------------------------------

@Composable
fun DashboardScreen(
    subscriptions: List<Subscription>?,
    hasSmsAccess: Boolean,
    hasNotificationAccess: Boolean,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onRescan: () -> Unit,
    onGrantAccess: () -> Unit,
    onGrantNotificationAccess: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subscriptions") },
                actions = { if (hasSmsAccess) TextButton(onClick = onRescan) { Text("Rescan") } },
            )
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onAdd) { Text("Add manually") } },
    ) { padding ->
        if (subscriptions == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val live = subscriptions.filter { it.isLive }
        val ended = subscriptions.filterNot { it.isLive }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SummaryCard(subscriptions) }
            if (!hasSmsAccess) {
                item {
                    Card(Modifier.fillMaxWidth().clickable(onClick = onGrantAccess)) {
                        Text(
                            "SMS access is off, so only subscriptions you add are shown. Tap to turn on automatic detection.",
                            Modifier.padding(16.dp),
                        )
                    }
                }
            }
            if (!hasNotificationAccess) {
                item {
                    Card(Modifier.fillMaxWidth().clickable(onClick = onGrantNotificationAccess)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Catch AutoPay alerts from UPI apps", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Some banks send the renewal notice only in GPay, PhonePe or Paytm, not by SMS. " +
                                    "Allow notification access so those are caught too. Only payment apps are read, on this phone.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            if (subscriptions.isEmpty()) {
                item {
                    Text(
                        if (hasSmsAccess) "No recurring payments found yet. We keep watching new SMS, and you can add one manually."
                        else "Nothing here yet.",
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
                Text(formatRupees(sub.amount), style = MaterialTheme.typography.titleMedium)
                Text(sub.frequency.label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ---- Detail -------------------------------------------------------------------------------------

@Composable
fun DetailScreen(
    sub: Subscription,
    isManual: Boolean,
    onBack: () -> Unit,
    onMarkCancelled: () -> Unit,
    onUndoCancel: () -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit,
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
            Text("${formatRupees(sub.amount)} · ${sub.frequency.label}", style = MaterialTheme.typography.headlineSmall)
            Text("${sub.category} · about ${formatRupees(sub.monthlyCost)} a month, ${formatRupees(sub.monthlyCost.multiply(BigDecimal(12)))} a year")

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
                Text("Payments found in your SMS", style = MaterialTheme.typography.titleSmall)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        sub.charges.sortedByDescending { it.date }.forEachIndexed { i, charge ->
                            if (i > 0) HorizontalDivider()
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text(formatDate(charge.date), Modifier.weight(1f))
                                Text(formatRupees(charge.amount))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            if (sub.isLive && sub.cancelUrl != null) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
