package com.aasif.watchdog.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aasif.watchdog.ui.theme.watchdog

enum class PillTone { Brand, Info, Amber, Rose, Neutral }

/** A compact status tag: "Trial", "Barely used", "Name it". Colour carries meaning, the text always says it too. */
@Composable
fun StatusPill(text: String, tone: PillTone, modifier: Modifier = Modifier) {
    val w = MaterialTheme.watchdog
    val (bg, fg) = when (tone) {
        PillTone.Brand -> MaterialTheme.colorScheme.primaryContainer to w.brandText
        PillTone.Info -> w.infoSoft to w.info
        PillTone.Amber -> w.amberSoft to w.amber
        PillTone.Rose -> w.dangerSoft to w.danger
        PillTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        maxLines = 1,
    )
}
