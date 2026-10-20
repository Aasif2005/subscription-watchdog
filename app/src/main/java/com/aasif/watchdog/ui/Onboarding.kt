package com.aasif.watchdog.ui

import com.aasif.watchdog.core.merchant.BrandApps
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aasif.watchdog.ui.components.AppTile
import com.aasif.watchdog.ui.components.PrimaryButton
import com.aasif.watchdog.ui.components.SectionLabel
import com.aasif.watchdog.ui.components.SoftCard
import com.aasif.watchdog.ui.components.TextAction
import com.aasif.watchdog.ui.components.TonalButton
import com.aasif.watchdog.ui.theme.Spacing
import com.aasif.watchdog.ui.theme.tabular
import com.aasif.watchdog.ui.theme.watchdog

/**
 * First screen of a fresh install. "Sign in" and "Sign up" are one action with Google: the account is chosen on the phone and
 * approved for read-only Gmail, then becomes the inbox the mail analyzer reads. Nothing is sent to a server.
 *
 * Three views share this entry point: the welcome page, an explainer of what Gmail access is used for (shown before Google's own
 * screens so nothing there is a surprise), and, if connecting Gmail did not finish, an inline card with Try again and Continue.
 */
@Composable
fun SignInScreen(
    issue: ConsentIssue?,
    onContinueWithGoogle: () -> Unit,
    onTryAgain: () -> Unit,
    onContinueWithoutGmail: () -> Unit,
    startOnExplainer: Boolean = false,
) {
    var explainer by rememberSaveable { mutableStateOf(startOnExplainer) }
    BackHandler(enabled = explainer && issue == null) { explainer = false }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            issue != null -> ConsentIssueView(issue, onTryAgain, onContinueWithoutGmail)
            explainer -> ConsentExplainer(onBack = { explainer = false }, onContinue = onContinueWithGoogle)
            else -> Welcome(onStart = { explainer = true })
        }
    }
}

@Composable
private fun BrandMark() {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(WatchdogIcons.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Welcome(onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = Spacing.gutter)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            Spacer(Modifier.height(Spacing.md))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrandMark()
                Text("Watchdog", style = MaterialTheme.typography.titleMedium)
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("Know before you're charged.", style = MaterialTheme.typography.displaySmall)
                Text(
                    "Watchdog finds your subscriptions in Gmail, bank texts and payment alerts, then warns you before each one renews.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StackedPreview()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TrustLine("Reads receipts, never your other mail")
                TrustLine("Everything it finds stays on this phone")
                TrustLine("Every permission is optional")
            }
            Spacer(Modifier.height(Spacing.xs))
        }
        Column(Modifier.padding(top = Spacing.sm, bottom = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            GoogleButton(onStart)
            Text(
                "New or returning, it's the same button. Pick the Google account whose Gmail gets your receipts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrustLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(WatchdogIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A decorative sample of what a renewal looks like. Hidden from screen readers: it is not the user's data. */
@Composable
private fun StackedPreview() {
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    Box(Modifier.fillMaxWidth().height(116.dp).clearAndSetSemantics {}) {
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp).height(72.dp).offset(y = 28.dp).alpha(0.55f),
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, border = border,
        ) {}
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 11.dp).height(72.dp).offset(y = 14.dp).alpha(0.8f),
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, border = border,
        ) {}
        SoftCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AppTile("Netflix", size = 42.dp, brandId = "netflix", appPackage = BrandApps.packages["netflix"])
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Netflix", style = MaterialTheme.typography.titleSmall)
                    Text("Renews in 2 days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.watchdog.danger)
                }
                Text("₹499", style = MaterialTheme.typography.titleSmall.tabular())
            }
        }
    }
}

@Composable
private fun ConsentExplainer(onBack: () -> Unit, onContinue: () -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = Spacing.gutter)) {
        IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
            Icon(WatchdogIcons.Back, contentDescription = "Back")
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Spacer(Modifier.height(Spacing.xs))
            IconTile(WatchdogIcons.Mail, tint = MaterialTheme.watchdog.brandText, background = MaterialTheme.colorScheme.primaryContainer)
            Text("Next, Google asks if Watchdog can read your Gmail", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Here is exactly what that access is used for, so the next screen holds no surprises.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SoftCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SectionLabel("Watchdog looks for", color = MaterialTheme.watchdog.brandText)
                    ConsentLine("Payment receipts and invoices", yes = true)
                    ConsentLine("Renewal, trial-ending and price-change mail", yes = true)
                    ConsentLine("Google Play order confirmations", yes = true)
                    Spacer(Modifier.height(Spacing.hair))
                    SectionLabel("It never")
                    ConsentLine("Sends, deletes or changes mail", yes = false)
                    ConsentLine("Uploads your mail anywhere. Parsing runs on this phone.", yes = false)
                }
            }
            Spacer(Modifier.height(Spacing.xs))
        }
        PrimaryButton("Continue to Google", onContinue, Modifier.fillMaxWidth().padding(top = Spacing.sm, bottom = Spacing.xl))
    }
}

@Composable
private fun ConsentLine(text: String, yes: Boolean) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Icon(
            if (yes) WatchdogIcons.Check else WatchdogIcons.Close,
            contentDescription = if (yes) "Yes:" else "Never:",
            tint = if (yes) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (yes) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IconTile(icon: ImageVector, tint: Color, background: Color, size: Int = 56) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(16.dp)).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size / 2).dp))
    }
}

@Composable
private fun ConsentIssueView(issue: ConsentIssue, onTryAgain: () -> Unit, onContinue: () -> Unit) {
    val body = when (issue) {
        ConsentIssue.Declined -> "Access wasn't granted on Google's screen, so nothing was read from that account. That's fine; you can still go on."
        ConsentIssue.Unavailable -> "Couldn't reach Google Play services to connect Gmail. Check your connection and try again."
        ConsentIssue.Unidentified -> "Couldn't tell which Google account was approved. Please try again."
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = Spacing.gutter)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Spacer(Modifier.height(Spacing.xxl))
            IconTile(WatchdogIcons.Mail, tint = MaterialTheme.watchdog.amber, background = MaterialTheme.watchdog.amberSoft)
            Text("Gmail isn't connected", style = MaterialTheme.typography.headlineMedium)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SoftCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("Without Gmail", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Icon(WatchdogIcons.Warning, null, tint = MaterialTheme.watchdog.amber, modifier = Modifier.size(18.dp))
                        Text(
                            "Receipts and Google Play orders come from Gmail, so those subscriptions may be missed.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Icon(WatchdogIcons.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text(
                            "The other sources you allow next can still find many of them.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Column(Modifier.padding(top = Spacing.sm, bottom = Spacing.xl), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Try again", onTryAgain, Modifier.fillMaxWidth())
            TonalButton("Continue without Gmail", onContinue, Modifier.fillMaxWidth())
        }
    }
}

/** The white "Continue with Google" pill; the G is drawn in the four brand colours, not copied from Google's artwork. */
@Composable
private fun GoogleButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1F1F1F)),
        border = BorderStroke(1.dp, Color(0xFFDADCE0)),
    ) {
        Text(
            "G",
            style = TextStyle(
                brush = Brush.linearGradient(listOf(Color(0xFF4285F4), Color(0xFFEA4335), Color(0xFFFBBC05), Color(0xFF34A853))),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(Modifier.width(12.dp))
        Text("Continue with Google", style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp))
    }
}

/** One permission the app can use. [short] is the one-line reason shown on the dashboard and the summary; [reason] is the full one. */
data class PermissionItem(
    val id: String,
    val title: String,
    val reason: String,
    val short: String,
    val allowed: Boolean,
    val onAllow: () -> Unit,
)

private fun iconFor(id: String): ImageVector = when (id) {
    "sms" -> WatchdogIcons.Sms
    "gmail" -> WatchdogIcons.Mail
    "notification-access" -> WatchdogIcons.Card
    "notifications" -> WatchdogIcons.Bell
    else -> WatchdogIcons.Clock
}

/** A made-up example of what the permission lets Watchdog see, so the page shows the benefit and not just the request. */
private fun exampleFor(id: String): Pair<String, String>? = when (id) {
    "sms" -> "Your bank · SMS" to "AutoPay of ₹499 set up for Netflix"
    "gmail" -> "Google Play · receipt" to "Your subscription renewed · ₹299"
    "notification-access" -> "PhonePe · alert" to "AutoPay ₹119 for Spotify on 26 Oct"
    "notifications" -> "Watchdog · reminder" to "Netflix renews in 2 days · ₹499"
    "usage" -> "Watchdog · insight" to "Netflix: 42 min in the last 30 days"
    else -> null
}

/**
 * Asks for each permission that is not on yet, one per page. Allow opens the system's own prompt or settings and the page moves on when
 * it comes back granted; Deny moves on at once and never blocks anything. After the last page everything still off is listed with its
 * one-line reason; if nothing is off the screen ends by itself.
 */
@Composable
fun PermissionsScreen(
    accountEmail: String?,
    items: List<PermissionItem>,
    onContinue: () -> Unit,
) {
    // Fixed when the screen opens, so a permission granted on its page does not make the next page jump.
    val pages = rememberSaveable { ArrayList(items.filter { !it.allowed }.map { it.id }) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    val current = items.firstOrNull { it.id == pages.getOrNull(step) }
    // Comes back from Settings granted: nothing more to ask on this page.
    LaunchedEffect(current?.id, current?.allowed) { if (current?.allowed == true) step++ }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = Spacing.gutter)) {
            if (current != null) {
                StepHeader(accountEmail, step, pages.size)
                PermissionPage(current, onDeny = { step++ })
            } else {
                PermissionSummary(accountEmail, items, onContinue)
            }
        }
    }
}

@Composable
private fun StepHeader(accountEmail: String?, step: Int, total: Int) {
    Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(total) { k ->
                    Box(
                        Modifier.weight(1f).height(4.dp).clip(CircleShape).background(
                            if (k <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    )
                }
            }
            Text("${step + 1}/$total", style = MaterialTheme.typography.labelMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (accountEmail != null) AccountChip(accountEmail)
    }
}

@Composable
private fun AccountChip(email: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(email.first().uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.watchdog.brandText)
        }
        Text(email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PermissionPage(item: PermissionItem, onDeny: () -> Unit) {
    val example = exampleFor(item.id)
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Spacer(Modifier.height(Spacing.xs))
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                IconTile(iconFor(item.id), tint = MaterialTheme.watchdog.brandText, background = MaterialTheme.colorScheme.surface)
                if (example != null) {
                    Column(Modifier.clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionLabel("Example", color = MaterialTheme.watchdog.brandText)
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(example.first, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(example.second, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }
        }
        Text(item.title, style = MaterialTheme.typography.headlineMedium)
        Text(item.reason, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(WatchdogIcons.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(
                "Optional. Saying no only turns this feature off; you can change it later in Profile.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.xs))
    }
    Column(Modifier.padding(top = Spacing.sm, bottom = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        PrimaryButton("Allow", item.onAllow, Modifier.fillMaxWidth())
        TextAction("Not now", onDeny, Modifier.fillMaxWidth())
    }
}

/** Everything still off, one line each with its own Allow; with nothing off there is nothing to show. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PermissionSummary(
    accountEmail: String?,
    items: List<PermissionItem>,
    onContinue: () -> Unit,
) {
    val missing = items.filter { !it.allowed }
    if (missing.isEmpty()) {
        LaunchedEffect(Unit) { onContinue() }
        return
    }
    val on = items.filter { it.allowed }
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Spacer(Modifier.height(Spacing.md))
        if (accountEmail != null) AccountChip(accountEmail)
        Text("${on.size} of ${items.size} on", style = MaterialTheme.typography.headlineMedium)
        Text(
            "These features stay off until you allow them. You can do it any time from the dashboard or Profile.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (on.isNotEmpty()) {
            SoftCard(Modifier.fillMaxWidth(), contentPadding = 0.dp) {
                on.forEachIndexed { k, item ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Icon(WatchdogIcons.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text("On", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.watchdog.brandText)
                    }
                    if (k < on.lastIndex) androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        missing.forEach { item ->
            SoftCard(Modifier.fillMaxWidth(), contentPadding = Spacing.md) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    IconTile(iconFor(item.id), tint = MaterialTheme.colorScheme.onSurfaceVariant, background = MaterialTheme.colorScheme.surfaceVariant, size = 40)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        Text(item.short, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TonalButton("Allow", item.onAllow)
                }
            }
        }
        Spacer(Modifier.height(Spacing.xs))
    }
    PrimaryButton("Continue", onContinue, Modifier.fillMaxWidth().padding(top = Spacing.sm, bottom = Spacing.xl))
}

/** The signed-in account: an initial in a circle and its address. Whether its Gmail is linked is shown where Gmail is managed. */
@Composable
fun AccountPill(email: String, modifier: Modifier = Modifier) {
    SoftCard(modifier.fillMaxWidth(), contentPadding = Spacing.md) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(email.first().uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.watchdog.brandText)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(email, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Signed in with Google", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
