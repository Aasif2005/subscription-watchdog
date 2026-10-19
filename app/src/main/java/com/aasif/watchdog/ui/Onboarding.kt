package com.aasif.watchdog.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * First screen of a fresh install. "Sign in" and "Sign up" are one action with Google: the account is chosen on the phone and
 * approved for read-only Gmail, then becomes the inbox the mail analyzer reads. Nothing is sent to a server.
 */
@Composable
fun SignInScreen(onContinueWithGoogle: () -> Unit) {
    var signUp by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text("W", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(20.dp))
            Text("Subscription Watchdog", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Finds your subscriptions and tells you before they renew.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(36.dp))
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !signUp, onClick = { signUp = false }, label = { Text("Sign in") })
                        FilterChip(selected = signUp, onClick = { signUp = true }, label = { Text("Sign up") })
                    }
                    Text(
                        if (signUp) "Create your account" else "Welcome back",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (signUp) "Use your Google account. We link its Gmail so renewal and receipt mail can be found, then you choose what else to allow."
                        else "Sign in with the Google account whose Gmail has your receipts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    GoogleButton(onContinueWithGoogle)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Watchdog only gets read access to Gmail, looks for receipts and renewal mail, and keeps what it finds on this phone. " +
                    "You can disconnect at any time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The white "Continue with Google" pill; the G is drawn in the four brand colours, not copied from Google's artwork. */
@Composable
private fun GoogleButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color(0xFF1F1F1F)),
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
        Text("Continue with Google", fontWeight = FontWeight.Medium, fontSize = 16.sp)
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
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp, vertical = 24.dp)) {
            if (current != null) {
                PermissionPage(accountEmail, current, step + 1, pages.size, onDeny = { step++ })
            } else {
                PermissionSummary(accountEmail, items, onContinue)
            }
        }
    }
}

@Composable
private fun ColumnScope.PermissionPage(accountEmail: String?, item: PermissionItem, number: Int, total: Int, onDeny: () -> Unit) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(12.dp))
        if (accountEmail != null) AccountPill(accountEmail)
        Text("Step $number of $total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(item.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(item.reason, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Optional. Saying no only turns this feature off; you can change it later in Profile.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(onClick = item.onAllow, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp)) {
        Text("Allow", fontSize = 16.sp)
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onDeny, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp)) {
        Text("Deny", fontSize = 16.sp)
    }
}

/** Everything still off, one line each with its own Allow; with nothing off there is nothing to show. */
@Composable
private fun ColumnScope.PermissionSummary(accountEmail: String?, items: List<PermissionItem>, onContinue: () -> Unit) {
    val missing = items.filter { !it.allowed }
    if (missing.isEmpty()) {
        LaunchedEffect(Unit) { onContinue() }
        return
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(12.dp))
        if (accountEmail != null) AccountPill(accountEmail)
        Text("Still turned off", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "These features stay off until you allow them. You can do it any time from the dashboard or Profile.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        missing.forEach { item ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(item.short, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = item.onAllow) { Text("Allow") }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp)) {
        Text("Continue", fontSize = 16.sp)
    }
}

/** The signed-in account: an initial in a circle and its address. Whether its Gmail is linked is shown where Gmail is managed. */
@Composable
fun AccountPill(email: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Text(email.first().uppercase(), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
        Column {
            Text(email, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text("Signed in with Google", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
