package com.aasif.watchdog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.ui.components.DateField
import com.aasif.watchdog.ui.components.ManualFrequencies
import com.aasif.watchdog.ui.components.PrimaryButton
import com.aasif.watchdog.ui.components.fieldColors
import com.aasif.watchdog.ui.components.manualDateRange
import com.aasif.watchdog.ui.components.SegmentedChoice
import com.aasif.watchdog.ui.components.SoftCard
import com.aasif.watchdog.ui.theme.Spacing
import java.math.BigDecimal
import java.time.LocalDate

@Composable
fun AddManualScreen(
    onBack: () -> Unit,
    onSave: (name: String, amount: BigDecimal, frequency: Frequency, firstCharge: LocalDate, isTrial: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf(Frequency.MONTHLY) }
    var date by remember { mutableStateOf<LocalDate?>(LocalDate.now()) }
    var isTrial by remember { mutableStateOf(false) }

    val parsedAmount = amount.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
    val amountError = amount.isNotEmpty() && parsedAmount == null
    val range = remember { manualDateRange() }
    val dateOk = date?.let { it in range } == true
    val valid = name.isNotBlank() && parsedAmount != null && dateOk

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = Spacing.xs, top = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(WatchdogIcons.Close, contentDescription = "Close") }
                Text("Add a subscription", style = MaterialTheme.typography.titleLarge)
            }
        },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = Spacing.gutter, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                // A disabled button alone does not say what is missing.
                if (!valid) {
                    Text(
                        when {
                            name.isBlank() && parsedAmount == null -> "Enter a name and an amount to save."
                            name.isBlank() -> "Enter a name to save."
                            parsedAmount == null -> "Enter an amount to save."
                            else -> "Pick a date within a year of today."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PrimaryButton(
                    "Save",
                    { onSave(name.trim(), parsedAmount!!, frequency, date!!, isTrial) },
                    Modifier.fillMaxWidth(),
                    enabled = valid,
                )
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            OutlinedTextField(
                name, { name = it }, Modifier.fillMaxWidth(),
                label = { Text("Name (e.g. Gym, Netflix)") }, singleLine = true, shape = MaterialTheme.shapes.medium, colors = fieldColors(),
            )
            OutlinedTextField(
                amount, { amount = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.fillMaxWidth(),
                label = { Text("Amount (₹)") }, singleLine = true, shape = MaterialTheme.shapes.medium, colors = fieldColors(),
                isError = amountError,
                supportingText = if (amountError) ({ Text("Enter an amount like 199 or 199.50") }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("How often", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SegmentedChoice(ManualFrequencies, frequency, { it.label }, { frequency = it })
            }
            DateField(if (isTrial) "Trial ends on" else "Date of a charge", date, { date = it }, range = range)
            SoftCard(Modifier.fillMaxWidth(), contentPadding = Spacing.md) {
                Row(
                    Modifier.fillMaxWidth().toggleable(isTrial, role = Role.Switch, onValueChange = { isTrial = it }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("This is a free trial", style = MaterialTheme.typography.titleSmall)
                        Text("The date becomes when the trial ends", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = isTrial, onCheckedChange = null)
                }
            }
        }
    }
}
