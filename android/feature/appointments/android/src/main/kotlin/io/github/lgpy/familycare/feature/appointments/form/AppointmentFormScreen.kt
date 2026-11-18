package io.github.lgpy.familycare.feature.appointments.form

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentField
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentKind
import io.github.lgpy.familycare.feature.appointments.ui.FieldError
import io.github.lgpy.familycare.feature.appointments.ui.errorRes
import io.github.lgpy.familycare.feature.appointments.ui.formatAppointmentTime
import io.github.lgpy.familycare.feature.appointments.ui.labelRes
import io.github.lgpy.familycare.feature.appointments.ui.localizedAppointmentDate
import io.github.lgpy.familycare.feature.appointments.ui.messageRes
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The create / edit / follow-up form (docs/domain/appointments.md "Screens").
 *
 * The patient is fixed when the form opens from a patient's tab, and a picker
 * when it opens from the Agenda. The time zone is the device's on create and the
 * stored one on edit; it is not editable here, so a trip never silently rewrites
 * the appointment.
 */
@Composable
fun AppointmentFormScreen(
    patientId: String?,
    appointmentId: String?,
    followUpOf: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<AppointmentFormViewModel>(
        parameters = { parametersOf(patientId.orEmpty(), appointmentId.orEmpty(), followUpOf.orEmpty()) },
    )
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onSaved()
    }

    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

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
                Text(
                    text = stringResource(
                        when {
                            uiState.isEdit -> R.string.appointments_form_edit_title
                            followUpOf != null -> R.string.appointments_form_follow_up_title
                            else -> R.string.appointments_form_new_title
                        },
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }

            when {
                !uiState.loaded -> CircularProgressIndicator()
                uiState.missing -> Text(
                    text = stringResource(R.string.appointments_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                )

                else -> {
                    if (uiState.patientFixed) {
                        val name = uiState.patients.firstOrNull { it.id == uiState.patientId }?.name
                        if (name != null) {
                            Text(
                                text = stringResource(R.string.appointments_field_patient) + ": " + name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    } else {
                        PickerField(
                            label = stringResource(R.string.appointments_field_patient),
                            selected = uiState.patients
                                .firstOrNull { it.id == uiState.patientId }?.name
                                .orEmpty(),
                            options = uiState.patients.map { it.id to it.name },
                            enabled = !uiState.saving,
                            onSelect = viewModel::onPatient,
                        )
                        FieldError(
                            visible = AppointmentField.PATIENT in uiState.errors,
                            message = AppointmentField.PATIENT.errorRes(),
                        )
                    }

                    Text(
                        text = stringResource(R.string.appointments_field_kind),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppointmentKind.entries.forEach { kind ->
                            FilterChip(
                                selected = uiState.kind == kind,
                                onClick = { viewModel.onKind(kind) },
                                enabled = !uiState.saving,
                                label = { Text(stringResource(kind.labelRes())) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    OutlinedTextField(
                        value = uiState.title,
                        onValueChange = viewModel::onTitle,
                        label = { Text(stringResource(R.string.appointments_field_title)) },
                        singleLine = true,
                        enabled = !uiState.saving,
                        isError = AppointmentField.TITLE in uiState.errors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FieldError(
                        visible = AppointmentField.TITLE in uiState.errors,
                        message = AppointmentField.TITLE.errorRes(),
                    )

                    OutlinedButton(
                        onClick = { showDate = true },
                        enabled = !uiState.saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.appointments_field_date) +
                                ": " + localizedAppointmentDate(uiState.date),
                        )
                    }
                    OutlinedButton(
                        onClick = { showTime = true },
                        enabled = !uiState.saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.appointments_field_time) +
                                ": " + formatAppointmentTime(uiState.time),
                        )
                    }

                    OutlinedTextField(
                        value = uiState.durationMinutes,
                        onValueChange = viewModel::onDuration,
                        label = { Text(stringResource(R.string.appointments_field_duration)) },
                        singleLine = true,
                        enabled = !uiState.saving,
                        isError = AppointmentField.DURATION in uiState.errors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FieldError(
                        visible = AppointmentField.DURATION in uiState.errors,
                        message = AppointmentField.DURATION.errorRes(),
                    )

                    OutlinedTextField(
                        value = uiState.place,
                        onValueChange = viewModel::onPlace,
                        label = { Text(stringResource(R.string.appointments_field_place)) },
                        singleLine = true,
                        enabled = !uiState.saving,
                        isError = AppointmentField.PLACE in uiState.errors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FieldError(
                        visible = AppointmentField.PLACE in uiState.errors,
                        message = AppointmentField.PLACE.errorRes(),
                    )

                    OutlinedTextField(
                        value = uiState.doctor,
                        onValueChange = viewModel::onDoctor,
                        label = { Text(stringResource(R.string.appointments_field_doctor)) },
                        singleLine = true,
                        enabled = !uiState.saving,
                        isError = AppointmentField.DOCTOR in uiState.errors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FieldError(
                        visible = AppointmentField.DOCTOR in uiState.errors,
                        message = AppointmentField.DOCTOR.errorRes(),
                    )

                    OutlinedTextField(
                        value = uiState.preparation,
                        onValueChange = viewModel::onPreparation,
                        label = { Text(stringResource(R.string.appointments_field_preparation)) },
                        minLines = 2,
                        enabled = !uiState.saving,
                        isError = AppointmentField.PREPARATION in uiState.errors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FieldError(
                        visible = AppointmentField.PREPARATION in uiState.errors,
                        message = AppointmentField.PREPARATION.errorRes(),
                    )

                    OutlinedTextField(
                        value = uiState.notes,
                        onValueChange = viewModel::onNotes,
                        label = { Text(stringResource(R.string.appointments_field_notes)) },
                        minLines = 2,
                        enabled = !uiState.saving,
                        isError = AppointmentField.NOTES in uiState.errors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FieldError(
                        visible = AppointmentField.NOTES in uiState.errors,
                        message = AppointmentField.NOTES.errorRes(),
                    )

                    PickerField(
                        label = stringResource(R.string.appointments_field_companion),
                        selected = uiState.companionUserId
                            ?.let { id -> uiState.members.firstOrNull { it.userId == id }?.displayName }
                            ?: stringResource(R.string.appointments_companion_anyone),
                        options = listOf("" to stringResource(R.string.appointments_companion_anyone)) +
                            uiState.members.map { member ->
                                member.userId to (member.displayName ?: member.userId)
                            },
                        enabled = !uiState.saving,
                        onSelect = { value -> viewModel.onCompanion(value.ifBlank { null }) },
                    )
                    FieldError(
                        visible = AppointmentField.COMPANION in uiState.errors,
                        message = AppointmentField.COMPANION.errorRes(),
                    )

                    uiState.saveError?.let { error ->
                        Text(
                            text = stringResource(error.messageRes()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    if (!uiState.canWrite) {
                        Text(
                            text = stringResource(R.string.appointments_error_not_allowed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Button(
                        onClick = viewModel::save,
                        enabled = uiState.canWrite && !uiState.saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    ) {
                        Text(text = stringResource(R.string.appointments_save))
                    }
                }
            }
        }
    }

    if (showDate) {
        AppointmentDateDialog(
            initial = uiState.date,
            onDismiss = { showDate = false },
            onConfirm = { date ->
                showDate = false
                viewModel.onDate(date)
            },
        )
    }
    if (showTime) {
        AppointmentTimeDialog(
            initial = uiState.time,
            onDismiss = { showTime = false },
            onConfirm = { time ->
                showTime = false
                viewModel.onTime(time)
            },
        )
    }
}

/** A labelled button that opens a dropdown of `(value, label)` options. */
@Composable
private fun PickerField(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = if (selected.isBlank()) label else label + ": " + selected)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppointmentDateDialog(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val selected = pickerState.selectedDateMillis
            TextButton(
                onClick = {
                    if (selected != null) {
                        onConfirm(
                            Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate(),
                        )
                    }
                },
                enabled = selected != null,
            ) { Text(text = stringResource(R.string.appointments_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.cancel)) }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppointmentTimeDialog(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.appointments_field_time)) },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(LocalTime.of(pickerState.hour, pickerState.minute))
                },
            ) { Text(text = stringResource(R.string.appointments_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.cancel)) }
        },
    )
}
