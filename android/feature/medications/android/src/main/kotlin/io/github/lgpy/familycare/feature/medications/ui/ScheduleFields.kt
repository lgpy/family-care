package io.github.lgpy.familycare.feature.medications.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.domain.ScheduleType
import java.time.DayOfWeek

/**
 * The schedule editor shared by step 2 of the add flow and the "change dose or
 * schedule" screen: type, weekdays or interval, the times with a quantity each,
 * and the start/end dates.
 *
 * Every field is text where the user types, with the domain's [MedicationField]s
 * deciding which lines turn red — the same rules that reject the save.
 */
@Composable
fun ScheduleFields(
    state: ScheduleFormState,
    errors: Set<MedicationField>,
    enabled: Boolean,
    onScheduleType: (ScheduleType) -> Unit,
    onWeekdayToggled: (DayOfWeek) -> Unit,
    onIntervalDays: (String) -> Unit,
    onTimeChanged: (Int, String) -> Unit,
    onQuantityStep: (Int, Int) -> Unit,
    onAddTime: () -> Unit,
    onRemoveTime: (Int) -> Unit,
    onStartDateChanged: (String) -> Unit,
    onEndDateChanged: (String) -> Unit,
    showDates: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.meds_schedule_label),
            style = MaterialTheme.typography.titleSmall,
        )
        ScheduleType.entries.forEach { type ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = state.scheduleType == type,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onScheduleType(type) },
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.scheduleType == type,
                    onClick = null,
                    enabled = enabled,
                )
                Text(
                    text = stringResource(type.labelRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        when (state.scheduleType) {
            ScheduleType.WEEKDAYS -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DayOfWeek.entries.forEach { day ->
                        val selected = day in state.weekdays
                        val label = stringResource(day.labelRes())
                        if (selected) {
                            Button(
                                onClick = { onWeekdayToggled(day) },
                                enabled = enabled,
                                modifier = Modifier.weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                            ) { Text(text = label, style = MaterialTheme.typography.labelSmall) }
                        } else {
                            OutlinedButton(
                                onClick = { onWeekdayToggled(day) },
                                enabled = enabled,
                                modifier = Modifier.weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                            ) { Text(text = label, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
                FieldError(
                    visible = MedicationField.REGIMEN_WEEKDAYS in errors,
                    message = R.string.meds_error_weekdays,
                )
            }

            ScheduleType.EVERY_N_DAYS -> {
                OutlinedTextField(
                    value = state.intervalDays,
                    onValueChange = onIntervalDays,
                    label = { Text(text = stringResource(R.string.meds_interval_label)) },
                    enabled = enabled,
                    singleLine = true,
                    isError = MedicationField.REGIMEN_INTERVAL_DAYS in errors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldError(
                    visible = MedicationField.REGIMEN_INTERVAL_DAYS in errors,
                    message = R.string.meds_error_interval,
                )
            }

            ScheduleType.DAILY -> Unit
        }

        Text(
            text = stringResource(R.string.meds_times_label),
            style = MaterialTheme.typography.titleSmall,
        )
        // A duplicate time is its own mistake (the domain rejects it because the
        // second quantity would be ambiguous), so it gets its own line instead
        // of the "add at least one time" one.
        val parsedTimes = state.times.mapNotNull { parseTime(it.time) }
        val timesHaveDuplicates = parsedTimes.size != parsedTimes.toSet().size
        val timesHaveUnparsable = parsedTimes.size != state.times.size
        state.times.forEachIndexed { index, entry ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = entry.time,
                    onValueChange = { onTimeChanged(index, it) },
                    label = { Text(text = stringResource(R.string.meds_time_label)) },
                    enabled = enabled,
                    singleLine = true,
                    isError = MedicationField.REGIMEN_TIMES in errors,
                    modifier = Modifier.weight(1.4f),
                )
                TextButton(
                    onClick = { onQuantityStep(index, -1) },
                    enabled = enabled,
                ) { Text(text = "−") }
                Text(
                    text = formatQuantity(entry.quantity),
                    style = MaterialTheme.typography.bodyLarge,
                )
                TextButton(
                    onClick = { onQuantityStep(index, 1) },
                    enabled = enabled,
                ) { Text(text = "+") }
                IconButton(
                    onClick = { onRemoveTime(index) },
                    enabled = enabled && state.times.size > 1,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.meds_remove_time),
                    )
                }
            }
            Text(
                text = stringResource(R.string.meds_quantity_label) + ": " +
                    formatQuantity(entry.quantity),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        FieldError(
            visible = MedicationField.REGIMEN_TIMES in errors && timesHaveDuplicates,
            message = R.string.meds_error_times_duplicate,
        )
        FieldError(
            visible = MedicationField.REGIMEN_TIMES in errors &&
                (!timesHaveDuplicates || timesHaveUnparsable),
            message = R.string.meds_error_times,
        )
        TextButton(onClick = onAddTime, enabled = enabled) {
            Text(text = stringResource(R.string.meds_add_time))
        }

        if (showDates) {
            OutlinedTextField(
                value = state.startDate,
                onValueChange = onStartDateChanged,
                label = { Text(text = stringResource(R.string.meds_start_date_label)) },
                supportingText = { Text(text = stringResource(R.string.meds_date_helper)) },
                enabled = enabled,
                singleLine = true,
                isError = MedicationField.REGIMEN_START_DATE in errors,
                modifier = Modifier.fillMaxWidth(),
            )
            FieldError(
                visible = MedicationField.REGIMEN_START_DATE in errors,
                message = R.string.meds_error_start,
            )
            OutlinedTextField(
                value = state.endDate,
                onValueChange = onEndDateChanged,
                label = { Text(text = stringResource(R.string.meds_end_date_label)) },
                supportingText = { Text(text = stringResource(R.string.meds_date_helper)) },
                enabled = enabled,
                singleLine = true,
                isError = MedicationField.REGIMEN_END_DATE in errors,
                modifier = Modifier.fillMaxWidth(),
            )
            FieldError(
                visible = MedicationField.REGIMEN_END_DATE in errors,
                message = R.string.meds_error_end,
            )
            FieldError(
                visible = MedicationField.REGIMEN_OVERLAP in errors,
                message = R.string.meds_error_overlap,
            )
        }
    }
}

/** A red line under a field, shown only when the domain rejected it. */
@Composable
fun FieldError(visible: Boolean, @StringRes message: Int) {
    if (!visible) return
    Text(
        text = stringResource(message),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}
