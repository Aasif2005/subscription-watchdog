package com.aasif.watchdog.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aasif.watchdog.ui.theme.Capsule
import com.aasif.watchdog.ui.theme.watchdog

private val ButtonPadding = PaddingValues(horizontal = 20.dp)
private val CompactPadding = PaddingValues(horizontal = 16.dp)

@Composable
private fun CapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    leading: ImageVector?,
    container: Color,
    content: Color,
    height: androidx.compose.ui.unit.Dp,
    padding: PaddingValues = ButtonPadding,
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = height),
        enabled = enabled,
        shape = Capsule,
        contentPadding = padding,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.watchdog.dim,
        ),
    ) {
        ButtonLabel(text, leading)
    }
}

@Composable
private fun RowScope.ButtonLabel(text: String, leading: ImageVector?) {
    if (leading != null) {
        Icon(leading, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge)
}

/** The one saturated action on a screen. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: ImageVector? = null,
) = CapsuleButton(
    text, onClick, modifier, enabled, leading,
    MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, 52.dp,
)

/** Mercury's tint-light pill: indigo at 10%, for every secondary action. */
@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: ImageVector? = null,
) = CapsuleButton(
    text, onClick, modifier, enabled, leading,
    MaterialTheme.colorScheme.primaryContainer, MaterialTheme.watchdog.brandText, 44.dp, CompactPadding,
)

@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: ImageVector? = null,
) = CapsuleButton(
    text, onClick, modifier, enabled, leading,
    MaterialTheme.watchdog.dangerSoft, MaterialTheme.watchdog.danger, 44.dp, CompactPadding,
)

/** A quiet text-only action, for "Not now" and the like. */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 44.dp),
        enabled = enabled,
        shape = Capsule,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
