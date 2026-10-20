@file:OptIn(ExperimentalMaterial3Api::class)

package com.aasif.watchdog.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.ui.WatchdogIcons
import com.aasif.watchdog.ui.formatDate
import com.aasif.watchdog.ui.relativeDays
import com.aasif.watchdog.ui.theme.watchdog
import java.time.LocalDate
import java.time.ZoneOffset

/** The periods a person can pick by hand; the others only ever come from what a bank or receipt states. */
val ManualFrequencies = listOf(Frequency.WEEKLY, Frequency.MONTHLY, Frequency.QUARTERLY, Frequency.YEARLY)

/** A small row of mutually exclusive choices in one lavender track, the selected one lifted. */
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            Surface(
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).selectable(on, onClick = { onSelect(option) }, role = Role.RadioButton),
                shape = RoundedCornerShape(12.dp),
                color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = if (on) 1.dp else 0.dp,
            ) {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label(option),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * A date picked from a calendar instead of typed as YYYY-MM-DD. Shows the date and how far away it is; tapping opens
 * Material's date picker. [date] null shows a prompt.
 */
/**
 * The dates a person can pick for a plan they add or complete by hand: a year and a fortnight either way of [today]. A
 * charge from decades back says nothing useful about the next one, and a typo there used to put renewals years out.
 */
fun manualDateRange(today: LocalDate = LocalDate.now()): ClosedRange<LocalDate> =
    today.minusYears(1).minusDays(15)..today.plusYears(1).plusDays(15)

@Composable
fun DateField(
    label: String,
    date: LocalDate?,
    onDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    /** Only these dates can be picked; any date when null. */
    range: ClosedRange<LocalDate>? = null,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SoftCard(Modifier.fillMaxWidth(), onClick = { open = true }, contentPadding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(WatchdogIcons.Calendar, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(
                    date?.let(::formatDate) ?: "Choose a date",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (date != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (date != null) {
                    Text(relativeDays(date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.watchdog.dim)
                }
            }
        }
    }
    if (open) {
        // The picker works in UTC midnights, so convert both ways without drifting a day in other zones.
        val start = (date ?: LocalDate.now()).let { d -> range?.let { d.coerceIn(it.start, it.endInclusive) } ?: d }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = range?.let { it.start.year..it.endInclusive.year } ?: DatePickerDefaults.YearRange,
            selectableDates = range?.let { allowed ->
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) =
                        java.time.Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() in allowed

                    override fun isSelectableYear(year: Int) = year in allowed.start.year..allowed.endInclusive.year
                }
            } ?: DatePickerDefaults.AllDates,
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDate(java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

/** A softer border than Material's default that still meets 3:1 against the screen, so fields match the hairline cards around them. */
@Composable
fun fieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = MaterialTheme.watchdog.fieldBorder,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
)
