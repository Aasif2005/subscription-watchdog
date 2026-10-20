package com.aasif.watchdog.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aasif.watchdog.ui.theme.watchdog

private val TilePalette = listOf(
    Color(0xFF2B2F36), Color(0xFF203A6B), Color(0xFF8A2B22), Color(0xFF3F4A6B),
    Color(0xFF5B3A8C), Color(0xFF7A4A12), Color(0xFF0E5A6B), Color(0xFF3A3F4B),
)

/**
 * A rounded tile with the subscription's logo (see [rememberBrandIcon]), or while there is none, its initial coloured
 * by a stable hash of its name. [unknown] is for a shared-billing charge whose app the user hasn't named yet.
 */
@Composable
fun AppTile(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    unknown: Boolean = false,
    brandId: String? = null,
    appPackage: String? = null,
) {
    val shape = RoundedCornerShape(size * 0.29f)
    val icon = if (unknown) null else rememberBrandIcon(name, brandId, appPackage, LocalOnlineLogos.current)
    if (icon != null) {
        // The hairline keeps a white icon (ChatGPT, Google One) from dissolving into a light card.
        Image(
            icon, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        )
        return
    }
    val initial = if (unknown) "?" else name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    val colour = if (unknown) MaterialTheme.watchdog.dim else TilePalette[(name.lowercase().hashCode() and 0x7fffffff) % TilePalette.size]
    Box(
        modifier = modifier.size(size).clip(shape).background(colour),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}
