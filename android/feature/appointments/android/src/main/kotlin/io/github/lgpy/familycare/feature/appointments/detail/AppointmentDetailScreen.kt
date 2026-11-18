package io.github.lgpy.familycare.feature.appointments.detail

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.feature.appointments.domain.Appointment
import io.github.lgpy.familycare.feature.appointments.ui.formatAppointmentTime
import io.github.lgpy.familycare.feature.appointments.ui.labelRes
import io.github.lgpy.familycare.feature.appointments.ui.localizedAppointmentDate
import io.github.lgpy.familycare.feature.appointments.ui.messageRes
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * One appointment (docs/domain/appointments.md "Screens"): every field, the
 * preparation highlighted, and the actions the role allows — edit, cancel and
 * "schedule follow-up" for owners and caregivers, delete for owners only.
 *
 * Both destructive actions ask first: cancelling keeps the history, deleting
 * does not.
 */
@Composable
fun AppointmentDetailScreen(
    appointmentId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onFollowUp: (String) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<AppointmentDetailViewModel>(
        parameters = { parametersOf(appointmentId) },
    )
    val uiState by viewModel.uiState.collectAsState()

    var confirmingCancel by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onDeleted()
    }

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
                val appointment = uiState.appointment
                Text(
                    text = appointment?.title
                        ?: appointment?.let { stringResource(it.kind.labelRes()) }
                        ?: stringResource(R.string.appointments_agenda_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }

            when {
                !uiState.loaded -> CircularProgressIndicator()
                uiState.missing -> Text(
                    text = stringResource(R.string.appointments_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                )

                uiState.appointment != null -> {
                    val appointment = uiState.appointment!!
                    AppointmentDetails(
                        appointment = appointment,
                        patientName = uiState.patientName,
                        companionName = uiState.companionName,
                    )

                    uiState.actionError?.let { error ->
                        Text(
                            text = stringResource(error.messageRes()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    if (uiState.canEdit) {
                        Button(
                            onClick = { onEdit(appointment.id.value) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(R.string.appointments_edit))
                        }
                        OutlinedButton(
                            onClick = { onFollowUp(appointment.id.value) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(R.string.appointments_schedule_follow_up))
                        }
                        OutlinedButton(
                            onClick = { confirmingCancel = true },
                            enabled = !uiState.cancelling,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(R.string.appointments_cancel_appointment))
                        }
                    }
                    if (uiState.canDelete) {
                        TextButton(
                            onClick = { confirmingDelete = true },
                            enabled = !uiState.deleting,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(R.string.appointments_delete))
                        }
                    }
                }
            }
        }
    }

    if (confirmingCancel) {
        ConfirmDialog(
            title = R.string.appointments_cancel_confirm_title,
            body = R.string.appointments_cancel_confirm_body,
            confirmLabel = R.string.appointments_cancel_appointment,
            onDismiss = { confirmingCancel = false },
            onConfirm = {
                confirmingCancel = false
                viewModel.cancel()
            },
        )
    }
    if (confirmingDelete) {
        ConfirmDialog(
            title = R.string.appointments_delete_confirm_title,
            body = R.string.appointments_delete_confirm_body,
            confirmLabel = R.string.appointments_delete,
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                confirmingDelete = false
                viewModel.delete()
            },
        )
    }
}

@Composable
private fun AppointmentDetails(
    appointment: Appointment,
    patientName: String?,
    companionName: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(appointment.kind.labelRes()),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = localizedAppointmentDate(appointment.localDate) +
                " · " + formatAppointmentTime(appointment.localTime),
            style = MaterialTheme.typography.bodyLarge,
        )
        appointment.durationMinutes?.let { minutes ->
            Text(
                text = stringResource(R.string.appointments_detail_duration, minutes),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (patientName != null) {
            Text(
                text = stringResource(R.string.appointments_detail_patient, patientName),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        appointment.place?.let { place ->
            Text(
                text = stringResource(R.string.appointments_detail_place, place),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        appointment.doctor?.let { doctor ->
            Text(
                text = stringResource(R.string.appointments_detail_doctor, doctor),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (companionName != null) {
            Text(
                text = stringResource(R.string.appointments_field_companion) + ": " + companionName,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        appointment.preparation?.let { preparation ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.appointments_field_preparation),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(text = preparation, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        appointment.notes?.let { notes ->
            Text(
                text = stringResource(R.string.appointments_field_notes),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(text = notes, style = MaterialTheme.typography.bodyMedium)
        }

        if (appointment.isCancelled) {
            Text(
                text = stringResource(R.string.appointments_cancelled_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: Int,
    body: Int,
    confirmLabel: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(title)) },
        text = { Text(text = stringResource(body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(confirmLabel))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}
