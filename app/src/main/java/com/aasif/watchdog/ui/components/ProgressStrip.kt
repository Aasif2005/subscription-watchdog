package com.aasif.watchdog.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.aasif.watchdog.ui.WatchdogIcons
import com.aasif.watchdog.ui.theme.tabular
import com.aasif.watchdog.ui.theme.watchdog

/**
 * The Gmail import strip: a title, a percentage when one is known, a bar that eases to each new value and a
 * line of detail ("1,860 of ~3,000 emails · 4 found").
 */
@Composable
fun ImportProgressStrip(title: String, detail: String, fraction: Float?, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "import-pulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "import-pulse-alpha",
    )
    val shown by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), tween(800), label = "import-fraction")
    val tint = MaterialTheme.watchdog.brandText
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp).semantics(mergeDescendants = true) {
                if (fraction != null) {
                    progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                    stateDescription = "${(shown * 100).toInt()} percent. $detail"
                }
            },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(WatchdogIcons.Mail, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp).alpha(pulse))
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = tint)
                if (fraction != null) {
                    Text("${(shown * 100).toInt()}%", style = MaterialTheme.typography.labelLarge.tabular(), color = tint)
                }
            }
            Box(
                Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
            ) {
                if (fraction != null) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(CircleShape).background(tint))
                }
            }
            Text(detail, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
