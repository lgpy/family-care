package io.github.lgpy.familycare.ui.patients

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.PatientAvatar
import io.github.lgpy.familycare.core.ui.components.ScreenHeader
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Patients tab: a list of the people in the circle, plus "Add patient" for
 * owners and caregivers (the same rule the server enforces). A row opens the
 * person's profile (D29).
 */
@Composable
fun PatientsScreen(
    onAddPatient: () -> Unit,
    onOpenPatient: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<PatientsViewModel>()
    val uiState by viewModel.uiState.collectAsState()
    var archivedExpanded by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        // A tab inside the app's Scaffold: the outer one already handles the
        // system bars, so this one must not add the status bar again.
        contentWindowInsets = WindowInsets(0),
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
        if (uiState.patients.isEmpty() && uiState.archivedPatients.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ScreenHeader(title = stringResource(R.string.patients_title))
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    ScreenHeader(
                        title = stringResource(R.string.patients_title),
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
                if (uiState.archivedPatients.isNotEmpty()) {
                    item(key = "archived-header") {
                        // D30: archived patients stay reachable, but out of the way.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = archivedExpanded,
                                    role = Role.Button,
                                    onClick = { archivedExpanded = !archivedExpanded },
                                )
                                .padding(top = 16.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.patients_archived_section,
                                    uiState.archivedPatients.size,
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        HorizontalDivider()
                    }
                    if (archivedExpanded) {
                        items(uiState.archivedPatients, key = { it.patient.id.value }) { row ->
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
        PatientAvatar(
            patientId = row.patient.id,
            name = row.patient.displayName,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = row.patient.displayName,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = row.hint(),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (row.patient.needsPrimaryCaregiver) {
                // D24: a removed member left this patient with nobody to remind.
                Text(
                    text = stringResource(R.string.patient_needs_primary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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
