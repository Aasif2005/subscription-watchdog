@file:OptIn(ExperimentalMaterial3Api::class)

package com.aasif.watchdog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aasif.watchdog.ui.components.PrimaryButton
import com.aasif.watchdog.ui.components.TextAction
import com.aasif.watchdog.ui.theme.Spacing

/**
 * Shown once. Allowing Usage Access in Android Settings is the switch, so this only explains what is read and points
 * there; either answer is final and nothing about usage is shown afterwards unless it matters at renewal time.
 */
@Composable
fun UsageDisclosureSheet(onOpenSettings: () -> Unit, onNotNow: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onNotNow,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.padding(horizontal = Spacing.gutter).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("Tell you when you barely use a subscription?", style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(
                    "To spot a subscription you hardly use, Watchdog can read how many minutes each day the app of a subscription you " +
                        "have was open (or, for Spotify and Apple Music, how long it was playing), if it is one of Netflix, YouTube, Prime Video, JioHotstar, SonyLIV, ZEE5, ChatGPT, Claude, LinkedIn, Spotify or Apple Music, on " +
                        "this device only, and the older daily, weekly and monthly totals Android still keeps for them. Nothing else on your phone is read and nothing leaves it. It is only used to say, before a renewal, " +
                        "that you barely used the app. Android calls the switch \"Usage access\": allow it for Watchdog to start, and " +
                        "turn it off there at any time, which also deletes what was saved.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(WatchdogIcons.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(
                    "Only apps you subscribe to are counted. Totals stay on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.padding(bottom = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                PrimaryButton("Open Settings", onOpenSettings, Modifier.fillMaxWidth())
                TextAction("Not now", onNotNow, Modifier.fillMaxWidth())
            }
        }
    }
}
