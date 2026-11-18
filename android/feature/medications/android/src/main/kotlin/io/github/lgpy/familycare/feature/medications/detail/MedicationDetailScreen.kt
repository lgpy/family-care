package io.github.lgpy.familycare.feature.medications.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.feature.medications.domain.DoseOutcome
import io.github.lgpy.familycare.feature.medications.domain.Regimen
import io.github.lgpy.familycare.feature.medications.ui.formatTime
import io.github.lgpy.familycare.feature.medications.ui.labelRes
import io.github.lgpy.familycare.feature.medications.ui.localizedDate
import io.github.lgpy.familycare.feature.medications.ui.messageRes
import io.github.lgpy.familycare.feature.medications.ui.regimenSummary
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * One medication: what it is, the current schedule, the earlier versions and the
 * recent doses, with the three actions (edit details, change dose or schedule,
 * stop). Viewers see exactly the same information without the buttons.
 */
@Composable
fun MedicationDetailScreen(
    medicationId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onChangeSchedule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<MedicationDetailViewModel>(
        parameters = { parametersOf(medicationId) },
    )
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
            Column {
                Text(
                    text = uiState.medication?.medication?.name
                        ?: stringResource(R.string.meds_detail_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                uiState.medication?.medication?.strength?.let { strength ->
                    Text(text = strength, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        when {
            !uiState.loaded -> CircularProgressIndicator()
            uiState.notFound || uiState.medication == null -> Text(
                text = stringResource(R.string.meds_not_found),
                style = MaterialTheme.typography.bodyLarge,
            )

            else -> {
                val item = uiState.medication!!
                val medication = item.medication

                if (medication.isStopped) {
                    medication.stoppedAt?.let { stoppedAt ->
                        Text(
                            text = stringResource(
                                R.string.meds_detail_stopped,
                                localizedDate(stoppedAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate()),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else if (!uiState.canWrite) {
                    Text(
                        text = stringResource(R.string.patient_read_only),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                HorizontalDivider()
                Text(
                    text = stringResource(R.string.meds_detail_info),
                    style = MaterialTheme.typography.titleSmall,
                )
                medication.activeIngredient?.let {
                    Text(
                        text = stringResource(R.string.meds_detail_ingredient, it),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                medication.strength?.let {
                    Text(
                        text = stringResource(R.string.meds_detail_strength, it),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.meds_detail_form,
                        stringResource(medication.form.labelRes()),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.meds_detail_stock_unit, medication.stockUnit),
                    style = MaterialTheme.typography.bodyMedium,
                )
                medication.instructions?.let {
                    Text(
                        text = stringResource(R.string.meds_detail_instructions, it),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = medication.prescriptionValidUntil?.let {
                        stringResource(R.string.meds_detail_prescription, localizedDate(it))
                    } ?: stringResource(R.string.meds_detail_no_prescription),
                    style = MaterialTheme.typography.bodyMedium,
                )

                HorizontalDivider()
                Text(
                    text = stringResource(R.string.meds_detail_current),
                    style = MaterialTheme.typography.titleSmall,
                )
                item.currentRegimen?.let { regimen ->
                    Text(text = regimenSummary(regimen), style = MaterialTheme.typography.bodyLarge)
                }

                if (uiState.history.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.meds_detail_history),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    uiState.history.forEach { regimen ->
                        Text(
                            text = historyLine(regimen),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                HorizontalDivider()
                Text(
                    text = stringResource(R.string.meds_detail_log),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (uiState.recentEntries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.meds_detail_log_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                uiState.recentEntries.forEach { entry ->
                    val whenText = localizedDate(entry.scheduledLocal.toLocalDate()) +
                        stringResource(R.string.meds_list_separator) +
                        formatTime(entry.scheduledLocal.toLocalTime())
                    val what = stringResource(
                        if (entry.outcome == DoseOutcome.TAKEN) {
                            R.string.meds_status_taken
                        } else {
                            R.string.meds_status_skipped
                        },
                    ).let { outcomeText ->
                        entry.skipReason?.let { reason ->
                            stringResource(
                                R.string.meds_log_what_with_reason,
                                outcomeText,
                                stringResource(reason.labelRes()),
                            )
                        } ?: outcomeText
                    }
                    Text(
                        text = stringResource(R.string.meds_log_line, whenText, what),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                uiState.error?.let { error ->
                    Text(
                        text = stringResource(error.messageRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (uiState.canWrite && !medication.isStopped) {
                    OutlinedButton(
                        onClick = onEdit,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(text = stringResource(R.string.meds_detail_edit)) }
                    OutlinedButton(
                        onClick = onChangeSchedule,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(text = stringResource(R.string.meds_detail_change)) }
                    TextButton(
                        onClick = viewModel::requestStop,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.meds_detail_stop),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    if (uiState.showStop) {
        StopDialog(
            uiState = uiState,
            onDismiss = viewModel::dismissStop,
            onLastDayChanged = viewModel::onLastDayChanged,
            onConfirm = viewModel::confirmStop,
        )
    }
}

/** "1 at 08:00, daily until 3 Mar 2027" / "… since 10 Mar 2027". */
@Composable
private fun historyLine(regimen: Regimen): String {
    val summary = regimenSummary(regimen)
    val end = regimen.endDate
    return if (end == null) {
        stringResource(R.string.meds_history_since, summary, localizedDate(regimen.startDate))
    } else {
        stringResource(R.string.meds_history_until, summary, localizedDate(end))
    }
}

@Composable
private fun StopDialog(
    uiState: MedicationDetailUiState,
    onDismiss: () -> Unit,
    onLastDayChanged: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    R.string.meds_stop_title,
                    uiState.medication?.medication?.name.orEmpty(),
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(R.string.meds_stop_body))
                OutlinedTextField(
                    value = uiState.stopLastDay,
                    onValueChange = onLastDayChanged,
                    label = { Text(text = stringResource(R.string.meds_stop_last_day)) },
                    supportingText = { Text(text = stringResource(R.string.meds_date_helper)) },
                    singleLine = true,
                    isError = uiState.stopDateInvalid,
                )
                if (uiState.stopDateInvalid) {
                    Text(
                        text = stringResource(R.string.meds_error_end),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !uiState.stopping) {
                Text(text = stringResource(R.string.meds_stop_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}

