package io.github.lgpy.familycare.ui.patients

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.core.ui.R
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Patients tab: a list of the people in the circle, plus "Add patient" for
 * owners and caregivers (the same rule the server enforces).
 */
@Composable
fun PatientsScreen(
    onAddPatient: () -> Unit,
    onOpenPatient: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<PatientsViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            if (uiState.canWrite) {
                FloatingActionButton(onClick = onAddPatient) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.patients_add),
                    )
                }
            }
        },
    ) { padding ->
        if (uiState.patients.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.patients_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = stringResource(R.string.patients_empty),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.patients_title),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                items(uiState.patients, key = { it.patient.id.value }) { row ->
                    PatientListRow(
                        row = row,
                        onClick = { onOpenPatient(row.patient.id.value) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PatientListRow(
    row: PatientRowUi,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = false,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = row.patient.displayName,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = row.hint(),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** "Primary with escalation · Primary: Mom", localized and without empty parts. */
@Composable
private fun PatientRowUi.hint(): String {
    val policy = stringResource(patient.reminderPolicy.labelRes())
    val primary = primaryCaregiverName
        ?: return policy
    return policy + " · " + stringResource(R.string.patients_primary_name, primary)
}

@StringRes
internal fun ReminderPolicy.labelRes(): Int = when (this) {
    ReminderPolicy.PRIMARY_WITH_ESCALATION -> R.string.patients_policy_primary_with_escalation
    ReminderPolicy.ALL_CAREGIVERS -> R.string.patients_policy_all_caregivers
    // Not offered, but the database enum has it: never show a raw server value.
    ReminderPolicy.ROTA -> R.string.patients_policy_unavailable
}
