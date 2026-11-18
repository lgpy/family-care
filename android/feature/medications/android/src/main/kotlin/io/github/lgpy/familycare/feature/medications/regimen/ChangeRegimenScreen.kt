package io.github.lgpy.familycare.feature.medications.regimen

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.ui.FieldError
import io.github.lgpy.familycare.feature.medications.ui.ScheduleFields
import io.github.lgpy.familycare.feature.medications.ui.messageRes
import io.github.lgpy.familycare.feature.medications.ui.regimenSummary
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Changing a medication's dose or schedule: a new version from a date, never an
 * edit of the current one. The previous version ends the day before, which is
 * what keeps "5 mg until 3 March, 10 mg since" true.
 */
@Composable
fun ChangeRegimenScreen(
    medicationId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<ChangeRegimenViewModel>(
        parameters = { parametersOf(medicationId) },
    )
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onBack()
    }

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
                    text = stringResource(R.string.meds_change_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                uiState.medicationName?.let { name ->
                    Text(text = name, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        when {
            !uiState.loaded -> CircularProgressIndicator()
            uiState.notFound -> Text(
                text = stringResource(R.string.meds_not_found),
                style = MaterialTheme.typography.bodyLarge,
            )

            else -> {
                if (!uiState.canWrite) {
                    Text(
                        text = stringResource(R.string.patient_read_only),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                uiState.current?.let { regimen ->
                    Text(
                        text = regimenSummary(regimen),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                OutlinedTextField(
                    value = uiState.effectiveFrom,
                    onValueChange = viewModel::onEffectiveFromChanged,
                    label = { Text(text = stringResource(R.string.meds_change_effective)) },
                    supportingText = {
                        Text(text = stringResource(R.string.meds_change_effective_helper))
                    },
                    enabled = uiState.canWrite,
                    singleLine = true,
                    isError = MedicationField.REGIMEN_START_DATE in uiState.errors,
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldError(
                    MedicationField.REGIMEN_START_DATE in uiState.errors,
                    R.string.meds_error_start,
                )

                ScheduleFields(
                    state = uiState.schedule,
                    errors = uiState.errors,
                    enabled = uiState.canWrite,
                    onScheduleType = viewModel::onScheduleTypeChanged,
                    onWeekdayToggled = viewModel::onWeekdayToggled,
                    onIntervalDays = viewModel::onIntervalDaysChanged,
                    onTimeChanged = viewModel::onTimeChanged,
                    onQuantityStep = viewModel::onQuantityStep,
                    onAddTime = viewModel::onAddTime,
                    onRemoveTime = viewModel::onRemoveTime,
                    onStartDateChanged = {},
                    onEndDateChanged = {},
                    showDates = false,
                )

                uiState.error?.let { error ->
                    Text(
                        text = stringResource(error.messageRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (uiState.canWrite) {
                    Button(
                        onClick = viewModel::save,
                        enabled = !uiState.saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(
                                if (uiState.saving) R.string.meds_step_busy else R.string.meds_save,
                            ),
                        )
                    }
                }
            }
        }
    }
}
