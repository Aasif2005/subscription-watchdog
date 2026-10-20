package com.aasif.watchdog.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aasif.watchdog.core.engine.DEFAULT_LEAD_DAYS
import com.aasif.watchdog.core.engine.MAX_LEAD_DAYS
import com.aasif.watchdog.data.GmailAccountStatus
import com.aasif.watchdog.ui.components.SectionLabel
import com.aasif.watchdog.ui.components.SegmentedChoice
import com.aasif.watchdog.ui.components.SoftCard
import com.aasif.watchdog.ui.components.TextAction
import com.aasif.watchdog.ui.components.TonalButton
import com.aasif.watchdog.ui.theme.Spacing
import com.aasif.watchdog.ui.theme.tabular
import com.aasif.watchdog.ui.theme.watchdog
import java.math.BigDecimal

@Composable
fun ProfileScreen(
    accountEmail: String?,
    onBack: () -> Unit,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    reminderLeadDays: Int,
    onReminderLeadDays: (Int) -> Unit,
    onlineLogos: Boolean,
    onOnlineLogos: (Boolean) -> Unit,
    gmailImportEnabled: Boolean,
    gmailAccounts: List<GmailAccountStatus>,
    onConnectGmail: () -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (String) -> Unit,
    /** What cancelled plans would have cost over a year; nothing is shown at zero. */
    yearlySavings: BigDecimal,
    appVersion: String,
    onOpenAppSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    val w = MaterialTheme.watchdog
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = Spacing.xs, top = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(WatchdogIcons.Back, contentDescription = "Back") }
                Text("Profile", style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            // Only a signed-in account reaches this screen.
            if (accountEmail != null) AccountPill(accountEmail)

            if (yearlySavings > BigDecimal.ZERO) {
                SoftCard(Modifier.fillMaxWidth(), container = MaterialTheme.colorScheme.primaryContainer, outlined = false, contentPadding = Spacing.lg) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Saved by cancelling", style = MaterialTheme.typography.labelMedium, color = w.brandText)
                            Text(
                                "${formatRupees(yearlySavings.setScale(0, java.math.RoundingMode.HALF_UP))} a year",
                                style = MaterialTheme.typography.headlineMedium.tabular(),
                            )
                        }
                        Icon(WatchdogIcons.Check, contentDescription = null, tint = w.brandText, modifier = Modifier.size(28.dp))
                    }
                }
            }

            Group("Appearance") {
                Column(Modifier.padding(Spacing.md)) {
                    SegmentedChoice(
                        ThemeMode.entries, themeMode,
                        { when (it) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" } },
                        onThemeMode,
                    )
                }
                Divider()
                Row(
                    Modifier.fillMaxWidth().toggleable(onlineLogos, role = Role.Switch, onValueChange = onOnlineLogos)
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Find logos online", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "For services Watchdog doesn't know. Sends only the service's name to Google Play, never amounts or your email.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = onlineLogos, onCheckedChange = null)
                }
            }

            Group("Renewal reminders") {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("Warn me this many days before", style = MaterialTheme.typography.titleSmall)
                    SegmentedChoice((DEFAULT_LEAD_DAYS..MAX_LEAD_DAYS).toList(), reminderLeadDays, { "$it" }, onReminderLeadDays)
                    Text(
                        if (reminderLeadDays > DEFAULT_LEAD_DAYS) "Then again 3 days before, the evening before, and on the day."
                        else "Then again the evening before, and on the day.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Group("Gmail") {
                if (gmailImportEnabled) {
                    gmailAccounts.forEachIndexed { k, account ->
                        if (k > 0) Divider()
                        val (status, tone) = when {
                            account.needsConsent -> "Needs your permission again" to w.amber
                            account.importing -> "Still reading your mail history" to w.amber
                            else -> "Up to date" to MaterialTheme.colorScheme.primary
                        }
                        val attention = account.needsConsent || account.importing
                        Row(
                            Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            IconTile(WatchdogIcons.Mail, if (attention) w.amberSoft else MaterialTheme.colorScheme.primaryContainer, if (attention) w.amber else w.brandText)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(account.email, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(status, style = MaterialTheme.typography.bodySmall, color = tone)
                            }
                            // Two actions would squeeze the address, so a lapsed account gets its own row below.
                            if (!account.needsConsent) TextAction("Remove", { onRemoveAccount(account.email) }, color = w.danger)
                        }
                        if (account.needsConsent) {
                            Row(
                                Modifier.padding(start = 64.dp, bottom = Spacing.sm),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TonalButton("Fix", onAddAccount)
                                TextAction("Remove", { onRemoveAccount(account.email) }, color = w.danger)
                            }
                        }
                    }
                    Divider()
                    Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
                        TonalButton("Add another account", onAddAccount, leading = WatchdogIcons.Add)
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        IconTile(WatchdogIcons.Mail, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Not connected", style = MaterialTheme.typography.titleSmall)
                            Text("Finds subscriptions that only send a receipt", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TonalButton("Connect", onConnectGmail)
                    }
                }
            }

            Group("About") {
                Row(
                    Modifier.fillMaxWidth().padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    IconTile(WatchdogIcons.Shield, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Watchdog", style = MaterialTheme.typography.titleSmall)
                        Text("Version $appVersion", style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Divider()
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenAppSettings).heightIn(min = 56.dp).padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    IconTile(WatchdogIcons.Lock, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("App permissions", style = MaterialTheme.typography.titleSmall)
                        Text("Review or turn off access in Android settings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(WatchdogIcons.External, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                Divider()
                Text(
                    "Everything Watchdog finds stays on this phone. Mail and texts are read here; nothing is uploaded.",
                    Modifier.padding(Spacing.md),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (accountEmail != null) {
                TextAction("Sign out", onSignOut, Modifier.fillMaxWidth(), color = w.danger)
            }
            Spacer(Modifier.size(Spacing.md))
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(title, Modifier.padding(start = 4.dp))
        SoftCard(Modifier.fillMaxWidth(), contentPadding = 0.dp) { content() }
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(horizontal = Spacing.md), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun IconTile(icon: ImageVector, soft: Color, tint: Color) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(soft), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
    }
}
