package io.github.lgpy.familycare.feature.appointments.form

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.components.inlineIconSize
import io.github.lgpy.familycare.core.ui.components.PatientAvatar
import io.github.lgpy.familycare.core.ui.components.SectionHeader
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentField
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentKind
import io.github.lgpy.familycare.feature.appointments.ui.FieldError
import io.github.lgpy.familycare.feature.appointments.ui.PreparationTag
import io.github.lgpy.familycare.feature.appointments.ui.errorRes
import io.github.lgpy.familycare.feature.appointments.ui.formatAppointmentTime
import io.github.lgpy.familycare.feature.appointments.ui.icon
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
 * The create / edit / follow-up form (docs/domain/appointments.md "Screens";
 * docs/ux/mockups/screens-v2.html `const addAppt`): one screen, the mockup's
 * "What / Who / When / Where / Before going" sections and everything else under
 * a collapsed "More options".
 *
 * The patient is preselected when the form comes from a patient's tab and is
 * then fixed (the server keeps `patient_id` immutable); from the Agenda the
 * chips start empty and the form shows the picker. The time zone is the
 * device's on create and the stored one on edit, so a trip never silently
 * rewrites the appointment.
 *
 * The bottom bar always holds the single save action, and a refusal sits next
 * to it as well as beside the field, so a validation message stays visible even
 * when the offending field is above the fold.
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

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        bottomBar = {
            if (uiState.loaded && !uiState.missing) {
                SaveBar(state = uiState, onSave = viewModel::save)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FormHeader(state = uiState, followUpOf = followUpOf, onClose = onBack)

            when {
                !uiState.loaded -> CircularProgressIndicator()
                uiState.missing -> Text(
                    text = stringResource(R.string.appointments_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                )

                else -> {
                    if (!uiState.canWrite) {
                        Text(
                            text = stringResource(R.string.appointments_error_not_allowed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    WhatSection(state = uiState, onKind = viewModel::onKind)
                    WhoSection(state = uiState, onPatient = viewModel::onPatient)
                    WhenSection(
                        state = uiState,
                        onPickDate = { showDate = true },
                        onPickTime = { showTime = true },
                    )
                    WhereSection(
                        state = uiState,
                        onPlace = viewModel::onPlace,
                    )
                    BeforeGoingSection(
                        state = uiState,
                        onTagToggled = viewModel::onPreparationTagToggled,
                        onFreeText = viewModel::onPreparationText,
                    )
                    MoreOptionsSection(
                        state = uiState,
                        onTitle = viewModel::onTitle,
                        onDoctor = viewModel::onDoctor,
                        onDuration = viewModel::onDuration,
                        onNotes = viewModel::onNotes,
                        onCompanion = viewModel::onCompanion,
                    )
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

/** The mockup's top bar: Close, then the form's title. */
@Composable
private fun FormHeader(
    state: AppointmentFormUiState,
    followUpOf: String?,
    onClose: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = FcIcons.Close,
                contentDescription = stringResource(R.string.close),
            )
        }
        Text(
            text = stringResource(
                when {
                    state.isEdit -> R.string.appointments_form_edit_title
                    followUpOf != null -> R.string.appointments_form_follow_up_title
                    else -> R.string.appointments_form_new_title
                },
            ),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** "What": one big chip per appointment kind, each with its icon. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhatSection(state: AppointmentFormUiState, onKind: (AppointmentKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(
            title = stringResource(R.string.appointments_section_what),
            icon = FcIcons.Category,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppointmentKind.entries.forEach { kind ->
                FilterChip(
                    selected = state.kind == kind,
                    onClick = { onKind(kind) },
                    enabled = !state.saving,
                    label = { Text(text = stringResource(kind.labelRes())) },
                    leadingIcon = {
                        Icon(
                            imageVector = kind.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(inlineIconSize(MaterialTheme.typography.labelLarge)),
                        )
                    },
                    modifier = Modifier.heightIn(min = 44.dp),
                )
            }
        }
    }
}

/**
 * "Who": a chip per patient with their avatar.
 *
 * When the form was opened from a patient's tab the appointment belongs to that
 * patient, so only that patient is shown — one selected chip at full emphasis
 * that is not a control — instead of the whole list dimmed as disabled.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhoSection(state: AppointmentFormUiState, onPatient: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(
            title = stringResource(R.string.appointments_section_who),
            icon = FcIcons.Person,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.patientFixed) {
                state.patients
                    .filter { it.id == state.patientId }
                    .forEach { option -> FixedPatientChip(option) }
            } else {
                state.patients.forEach { option ->
                    FilterChip(
                        selected = state.patientId == option.id,
                        onClick = { onPatient(option.id) },
                        enabled = !state.saving,
                        label = { Text(text = option.name) },
                        leadingIcon = {
                            PatientAvatar(
                                patientId = PatientId(option.id),
                                name = option.name,
                                size = 24.dp,
                            )
                        },
                        modifier = Modifier.heightIn(min = 44.dp),
                    )
                }
            }
        }
        FieldError(
            visible = AppointmentField.PATIENT in state.errors,
            message = AppointmentField.PATIENT.errorRes(),
        )
    }
}

/** The fixed patient, selected and readable: a chip without a click target. */
@Composable
private fun FixedPatientChip(option: PatientOption) {
    Surface(
        shape = FilterChipDefaults.shape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.heightIn(min = 44.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PatientAvatar(
                patientId = PatientId(option.id),
                name = option.name,
                size = 18.dp,
            )
            Text(text = option.name, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** "When": two buttons that open the date and time pickers; nothing is typed. */
@Composable
private fun WhenSection(
    state: AppointmentFormUiState,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(
            title = stringResource(R.string.appointments_section_when),
            icon = FcIcons.Event,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onPickDate,
                enabled = !state.saving,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                modifier = Modifier
                    .weight(1.4f)
                    .heightIn(min = 56.dp),
            ) {
                Icon(
                    imageVector = FcIcons.CalendarToday,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = localizedAppointmentDate(state.date),
                    textAlign = TextAlign.Start,
                )
            }
            OutlinedButton(
                onClick = onPickTime,
                enabled = !state.saving,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp),
            ) {
                Icon(
                    imageVector = FcIcons.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = formatAppointmentTime(state.time), textAlign = TextAlign.Start)
            }
        }
    }
}

/** "Where (optional)": the field plus the places this patient has been before. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhereSection(state: AppointmentFormUiState, onPlace: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(
            title = stringResource(R.string.appointments_section_where),
            icon = FcIcons.LocationOn,
        )
        OutlinedTextField(
            value = state.place,
            onValueChange = onPlace,
            label = { Text(text = stringResource(R.string.appointments_field_place)) },
            singleLine = true,
            enabled = !state.saving,
            isError = AppointmentField.PLACE in state.errors,
            modifier = Modifier.fillMaxWidth(),
        )
        FieldError(
            visible = AppointmentField.PLACE in state.errors,
            message = AppointmentField.PLACE.errorRes(),
        )

        val recent = state.recentPlaces.filter { place ->
            !place.equals(state.place.trim(), ignoreCase = true)
        }
        if (recent.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                recent.forEach { place ->
                    AssistChip(
                        onClick = { onPlace(place) },
                        enabled = !state.saving,
                        label = { Text(text = place) },
                        leadingIcon = {
                            Icon(
                                imageVector = FcIcons.History,
                                contentDescription = null,
                                modifier = Modifier.size(inlineIconSize(MaterialTheme.typography.labelLarge)),
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * "Before going (optional)": the chips and a free-text field, both of which make
 * up the one stored preparation string.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BeforeGoingSection(
    state: AppointmentFormUiState,
    onTagToggled: (PreparationTag, String) -> Unit,
    onFreeText: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(
            title = stringResource(R.string.appointments_section_before),
            icon = FcIcons.Checklist,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PreparationTag.entries.forEach { tag ->
                val phrase = stringResource(tag.labelRes())
                FilterChip(
                    selected = tag in state.preparationTags,
                    onClick = { onTagToggled(tag, phrase) },
                    enabled = !state.saving,
                    label = { Text(text = phrase) },
                    leadingIcon = {
                        Icon(
                            imageVector = tag.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(inlineIconSize(MaterialTheme.typography.labelLarge)),
                        )
                    },
                    modifier = Modifier.heightIn(min = 44.dp),
                )
            }
        }
        OutlinedTextField(
            value = state.preparationFreeText,
            onValueChange = onFreeText,
            label = { Text(text = stringResource(R.string.appointments_preparation_other)) },
            enabled = !state.saving,
            isError = AppointmentField.PREPARATION in state.errors,
            modifier = Modifier.fillMaxWidth(),
        )
        FieldError(
            visible = AppointmentField.PREPARATION in state.errors,
            message = AppointmentField.PREPARATION.errorRes(),
        )
    }
}

/**
 * "More options": a collapsed row summarising what it holds, then the fields the
 * mockup keeps out of the way — title, doctor, duration, notes and companion.
 */
@Composable
private fun MoreOptionsSection(
    state: AppointmentFormUiState,
    onTitle: (String) -> Unit,
    onDoctor: (String) -> Unit,
    onDuration: (String) -> Unit,
    onNotes: (String) -> Unit,
    onCompanion: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val summary = moreOptionsSummary(state)

    Surface(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = FcIcons.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.appointments_more_options),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = FcIcons.ExpandMore,
                contentDescription = null,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
    }

    if (!expanded) return

    val enabled = !state.saving

    OutlinedTextField(
        value = state.title,
        onValueChange = onTitle,
        label = { Text(text = stringResource(R.string.appointments_field_title)) },
        singleLine = true,
        enabled = enabled,
        isError = AppointmentField.TITLE in state.errors,
        modifier = Modifier.fillMaxWidth(),
    )
    FieldError(
        visible = AppointmentField.TITLE in state.errors,
        message = AppointmentField.TITLE.errorRes(),
    )

    OutlinedTextField(
        value = state.doctor,
        onValueChange = onDoctor,
        label = { Text(text = stringResource(R.string.appointments_field_doctor)) },
        singleLine = true,
        enabled = enabled,
        isError = AppointmentField.DOCTOR in state.errors,
        modifier = Modifier.fillMaxWidth(),
    )
    FieldError(
        visible = AppointmentField.DOCTOR in state.errors,
        message = AppointmentField.DOCTOR.errorRes(),
    )

    OutlinedTextField(
        value = state.durationMinutes,
        onValueChange = onDuration,
        label = { Text(text = stringResource(R.string.appointments_field_duration)) },
        singleLine = true,
        enabled = enabled,
        isError = AppointmentField.DURATION in state.errors,
        modifier = Modifier.fillMaxWidth(),
    )
    FieldError(
        visible = AppointmentField.DURATION in state.errors,
        message = AppointmentField.DURATION.errorRes(),
    )

    OutlinedTextField(
        value = state.notes,
        onValueChange = onNotes,
        label = { Text(text = stringResource(R.string.appointments_field_notes)) },
        minLines = 2,
        enabled = enabled,
        isError = AppointmentField.NOTES in state.errors,
        modifier = Modifier.fillMaxWidth(),
    )
    FieldError(
        visible = AppointmentField.NOTES in state.errors,
        message = AppointmentField.NOTES.errorRes(),
    )

    PickerField(
        label = stringResource(R.string.appointments_field_companion),
        selected = state.companionUserId
            ?.let { id -> state.members.firstOrNull { it.userId == id }?.displayName }
            ?: stringResource(R.string.appointments_companion_anyone),
        options = listOf("" to stringResource(R.string.appointments_companion_anyone)) +
            state.members.map { member ->
                member.userId to (member.displayName ?: member.userId)
            },
        enabled = enabled,
        onSelect = { value -> onCompanion(value.ifBlank { null }) },
    )
    FieldError(
        visible = AppointmentField.COMPANION in state.errors,
        message = AppointmentField.COMPANION.errorRes(),
    )
}

/** The collapsed expander's one line: what is already filled in, or what it offers. */
@Composable
private fun moreOptionsSummary(state: AppointmentFormUiState): String {
    val parts = mutableListOf<String>()
    state.title.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
    state.doctor.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
    state.durationMinutes.trim().toIntOrNull()?.let { minutes ->
        parts += stringResource(R.string.appointments_detail_duration, minutes)
    }
    if (state.notes.isNotBlank()) {
        parts += stringResource(R.string.appointments_summary_notes)
    }
    state.companionUserId?.let { id ->
        parts += state.members.firstOrNull { it.userId == id }?.displayName
            ?: stringResource(R.string.appointments_companion_anyone)
    }
    return if (parts.isEmpty()) {
        stringResource(R.string.appointments_more_options_hint)
    } else {
        parts.joinToString(" · ")
    }
}

/** The bottom bar: the validation message and the one save action. */
@Composable
private fun SaveBar(state: AppointmentFormUiState, onSave: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.errors.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.appointments_error_invalid),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.saveError?.let { error ->
                Text(
                    text = stringResource(error.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!state.canWrite) {
                Text(
                    text = stringResource(R.string.appointments_error_not_allowed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = onSave,
                enabled = state.canWrite && !state.saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(imageVector = FcIcons.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.appointments_save_action))
            }
        }
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
