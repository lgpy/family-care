package io.github.lgpy.familycare.feature.medications.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.StockMeter
import io.github.lgpy.familycare.core.ui.components.stockMeterFraction
import io.github.lgpy.familycare.feature.medications.ui.regimenSummary
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * A patient's medications: active ones with their current regimen as a one-line
 * summary, stopped ones collapsed (docs/ux/screens.md §3).
 *
 * "Add medication" exists for owners and caregivers only, the same rule the
 * repository enforces.
 *
 * The list itself ([PatientMedicationsSection]) is also the content of the
 * Medications tab of the patient profile (D29), which embeds it without this
 * screen's Scaffold, back arrow or floating button.
 */
@Composable
fun PatientMedicationsScreen(
    patientId: String,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpenMedication: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<PatientMedicationsViewModel>(
        parameters = { parametersOf(patientId) },
    )
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            if (uiState.canWrite && !uiState.patientMissing) {
                FloatingActionButton(onClick = onAdd) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.meds_list_add),
                    )
                }
            }
        },
    ) { padding ->
        PatientMedicationsList(
            uiState = uiState,
            onToggleStopped = viewModel::toggleStopped,
            onOpenMedication = onOpenMedication,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            header = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.meds_list_title),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        uiState.patientName?.let { name ->
                            Text(text = name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
        )
    }
}

/**
 * The Medications tab of the patient profile (D29): the same list, embedded
 * below the profile's own header and tabs.
 *
 * It has no Scaffold of its own: the profile owns the screen. "Add medication"
 * is a row at the top of the list instead of a floating button, because a
 * floating button would hover over the whole profile rather than this tab.
 */
@Composable
fun PatientMedicationsSection(
    patientId: String,
    onAdd: () -> Unit,
    onOpenMedication: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<PatientMedicationsViewModel>(
        parameters = { parametersOf(patientId) },
    )
    val uiState by viewModel.uiState.collectAsState()

    PatientMedicationsList(
        uiState = uiState,
        onToggleStopped = viewModel::toggleStopped,
        onOpenMedication = onOpenMedication,
        modifier = modifier.fillMaxSize(),
        onAdd = onAdd.takeIf { uiState.canWrite && !uiState.patientMissing },
    )
}

@Composable
private fun PatientMedicationsList(
    uiState: PatientMedicationsUiState,
    onToggleStopped: () -> Unit,
    onOpenMedication: (String) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (header != null) {
            item { header() }
        }

        if (onAdd != null) {
            item {
                FilledTonalButton(
                    onClick = onAdd,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.meds_list_add),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        if (!uiState.loaded) {
            item { CircularProgressIndicator() }
        } else if (uiState.patientMissing) {
            item {
                Text(
                    text = stringResource(R.string.meds_patient_not_available),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            if (uiState.isEmpty) {
                item {
                    Text(
                        text = stringResource(R.string.meds_list_empty),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            items(uiState.active, key = { it.medication.id.value }) { item ->
                MedicationRow(
                    item = item,
                    onClick = { onOpenMedication(item.medication.id.value) },
                )
                HorizontalDivider()
            }
            if (uiState.stopped.isNotEmpty()) {
                item {
                    TextButton(onClick = onToggleStopped) {
                        Text(
                            text = stringResource(
                                R.string.meds_list_stopped,
                                uiState.stopped.size,
                            ),
                        )
                    }
                }
            }
            if (uiState.stoppedExpanded) {
                items(uiState.stopped, key = { it.medication.id.value }) { item ->
                    MedicationRow(
                        item = item,
                        onClick = { onOpenMedication(item.medication.id.value) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun MedicationRow(item: PatientMedicationUi, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = false,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = item.medication.strength
                ?.let { item.medication.name + " " + it }
                ?: item.medication.name,
            style = MaterialTheme.typography.bodyLarge,
        )
        val regimen = item.item.currentRegimen
        if (regimen != null) {
            Text(text = regimenSummary(regimen), style = MaterialTheme.typography.bodyMedium)
        }
        item.daysRemaining?.let { days ->
            StockMeter(
                daysLeft = pluralStringResource(R.plurals.meds_stock_days_left, days, days),
                fraction = stockMeterFraction(days),
                low = item.low,
            )
        }
    }
}
