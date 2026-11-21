package io.github.lgpy.familycare.ui.patients

import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.circle.CircleMember
import io.github.lgpy.familycare.core.domain.circle.CoreField
import io.github.lgpy.familycare.core.domain.circle.PatientRules
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.DiscardChangesDialog
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.components.inlineIconSize
import io.github.lgpy.familycare.core.ui.components.SectionHeader
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The patient screen (docs/ux/screens.md §3, screens v2): name, year of birth,
 * allergies on Add patient, linked app user, reminder policy, primary caregiver,
 * and — under a collapsed "More options" — the escalation and missed-window
 * minutes and the optional agreement note. One screen for adding and editing.
 *
 * Screens v2 restyle: a close button and title in the top bar, labelled sections
 * with icons, and the save action as a full-width bar at the bottom. Viewers see
 * the same values without the ability to change them; the save bar is only shown
 * when [MemberRole.canWrite], and the repository refuses a write from a viewer
 * anyway. Closing with changes asks before throwing them away.
 */
@Composable
fun PatientFormScreen(
    patientId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<PatientFormViewModel>(
        parameters = { parametersOf(patientId ?: "") },
    )
    val uiState by viewModel.uiState.collectAsState()
    var discardDialog by remember { mutableStateOf(false) }

    val requestClose = {
        if (uiState.isDirty) discardDialog = true else onBack()
    }

    // The system back gesture asks the same question as the close button.
    BackHandler(enabled = uiState.isDirty) { discardDialog = true }

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onBack()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = requestClose) {
                Icon(
                    imageVector = FcIcons.Close,
                    contentDescription = stringResource(R.string.close),
                )
            }
            Text(
                text = stringResource(
                    if (uiState.isNew) R.string.patient_new_title else R.string.patient_edit_title,
                ),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                !uiState.loaded -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                uiState.notFound -> Text(
                    text = stringResource(R.string.patient_not_available),
                    style = MaterialTheme.typography.bodyLarge,
                )

                else -> PatientFormFields(viewModel = viewModel, uiState = uiState)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (uiState.loaded && !uiState.notFound && uiState.canWrite) {
            PatientFormSaveBar(saving = uiState.saving, onSave = viewModel::save)
        }
    }

    if (discardDialog) {
        DiscardChangesDialog(
            onKeepEditing = { discardDialog = false },
            onDiscard = {
                discardDialog = false
                onBack()
            },
        )
    }
}

@Composable
private fun PatientFormFields(
    viewModel: PatientFormViewModel,
    uiState: PatientFormUiState,
) {
    val canWrite = uiState.canWrite

    if (!canWrite) {
        Text(
            text = stringResource(R.string.patient_read_only),
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    if (uiState.needsPrimaryCaregiver) {
        // D24: the primary caregiver was removed; the reminder has no target.
        // Saving is refused below until one is chosen, so say why up front.
        Text(
            text = stringResource(R.string.patient_needs_primary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }

    OutlinedTextField(
        value = uiState.displayName,
        onValueChange = viewModel::onNameChanged,
        modifier = Modifier.fillMaxWidth(),
        enabled = canWrite,
        singleLine = true,
        label = { FieldLabel(R.string.patient_name_label, FcIcons.Person) },
        isError = CoreField.PATIENT_DISPLAY_NAME in uiState.errors,
        supportingText = errorSupport(
            show = CoreField.PATIENT_DISPLAY_NAME in uiState.errors,
            message = if (uiState.displayName.isBlank()) {
                R.string.error_name_empty
            } else {
                R.string.patient_error_name
            },
        ),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
    )

    // D33: only the year is stored; it is used to show the age and nothing else.
    OutlinedTextField(
        value = uiState.birthYear,
        onValueChange = viewModel::onBirthYearChanged,
        modifier = Modifier.fillMaxWidth(),
        enabled = canWrite,
        singleLine = true,
        label = { FieldLabel(R.string.patient_birth_year_label, FcIcons.CalendarToday) },
        isError = CoreField.PATIENT_BIRTH_YEAR in uiState.errors,
        supportingText = {
            if (CoreField.PATIENT_BIRTH_YEAR in uiState.errors) {
                ErrorText(stringResource(R.string.patient_error_birth_year))
            } else {
                Text(text = stringResource(R.string.patient_birth_year_helper))
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )

    // Add patient only: editing the record's allergies and conditions lives on
    // the profile's Info → Edit info screen, so it is not offered twice.
    if (uiState.isNew) {
        OutlinedTextField(
            value = uiState.allergies,
            onValueChange = viewModel::onAllergiesChanged,
            modifier = Modifier.fillMaxWidth(),
            enabled = canWrite && !uiState.noneKnown,
            singleLine = true,
            label = { FieldLabel(R.string.patient_allergies_label, FcIcons.Warning) },
            supportingText = {
                Text(text = stringResource(R.string.patient_allergies_helper))
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
        FilterChip(
            selected = uiState.noneKnown,
            onClick = { viewModel.onNoneKnownChanged(!uiState.noneKnown) },
            enabled = canWrite,
            label = { Text(text = stringResource(R.string.patient_allergies_none)) },
        )
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    // "This patient is also an app user": a member, or nobody.
    SectionHeader(
        title = stringResource(R.string.patient_linked_user_label),
        icon = FcIcons.Contacts,
    )
    ChoiceRow(
        selected = uiState.linkedUserId == null,
        enabled = canWrite,
        label = stringResource(R.string.patient_linked_user_none),
        onSelect = { viewModel.onLinkedUserChanged(null) },
    )
    uiState.members.forEach { member ->
        ChoiceRow(
            selected = uiState.linkedUserId == member.userId,
            enabled = canWrite,
            label = memberName(member),
            onSelect = { viewModel.onLinkedUserChanged(member.userId) },
        )
    }
    if (CoreField.PATIENT_LINKED_USER in uiState.errors) {
        ErrorText(stringResource(R.string.patient_error_linked_user))
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    SectionHeader(
        title = stringResource(R.string.patient_policy_label),
        icon = FcIcons.NotificationsActive,
    )
    Text(
        text = stringResource(R.string.patient_policy_helper),
        style = MaterialTheme.typography.bodyMedium,
    )
    ReminderPolicy.offered.forEach { policy ->
        ChoiceRow(
            selected = uiState.reminderPolicy == policy,
            enabled = canWrite,
            label = reminderPolicyOption(
                policy = policy,
                escalationDelayMinutes = uiState.escalationDelay.trim().toIntOrNull()
                    ?: PatientRules.DEFAULT_ESCALATION_DELAY_MINUTES,
            ),
            onSelect = { viewModel.onReminderPolicyChanged(policy) },
        )
    }
    if (CoreField.PATIENT_REMINDER_POLICY in uiState.errors) {
        ErrorText(stringResource(R.string.patient_error_policy))
    }

    if (uiState.reminderPolicy == ReminderPolicy.PRIMARY_WITH_ESCALATION ||
        uiState.reminderPolicy == ReminderPolicy.PRIMARY_ONLY
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        SectionHeader(
            title = stringResource(R.string.patient_primary_label),
            icon = FcIcons.Person,
        )
        Text(
            text = stringResource(R.string.patient_primary_helper),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (uiState.isSelfManaged) {
            // PatientRules: a patient who uses the app is their own first contact.
            val linkedName = uiState.members
                .firstOrNull { it.userId == uiState.linkedUserId }
                ?.let { memberName(it) }
                ?: stringResource(R.string.patient_linked_user_none)
            Text(
                text = stringResource(R.string.patient_linked_user_self_primary, linkedName),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            uiState.members.filter { it.role.canWrite }.forEach { member ->
                ChoiceRow(
                    selected = uiState.primaryCaregiverId == member.userId,
                    enabled = canWrite,
                    label = memberName(member),
                    onSelect = { viewModel.onPrimaryCaregiverChanged(member.userId) },
                )
            }
        }
        if (CoreField.PATIENT_PRIMARY_CAREGIVER in uiState.errors) {
            ErrorText(stringResource(R.string.patient_error_primary))
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    PatientMoreOptions(viewModel = viewModel, uiState = uiState)

    uiState.error?.let { error ->
        ErrorText(stringResource(error.messageRes()))
    }
}

/**
 * The escalation delay, missed window and agreement note, collapsed behind
 * "More options" with their current values in the summary — the same expander
 * the New medicine form uses (screens v2 §4). Their defaults stay visible.
 */
@Composable
private fun PatientMoreOptions(
    viewModel: PatientFormViewModel,
    uiState: PatientFormUiState,
) {
    var expanded by remember { mutableStateOf(false) }

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
                    text = stringResource(R.string.patient_more_options),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(
                        R.string.patient_more_summary,
                        uiState.escalationDelay,
                        uiState.missedWindow,
                    ),
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

    // The delay only means something when the server escalates.
    if (uiState.reminderPolicy == ReminderPolicy.PRIMARY_WITH_ESCALATION) {
        MinuteField(
            label = R.string.patient_escalation_label,
            icon = FcIcons.Schedule,
            helper = R.string.patient_escalation_helper,
            value = uiState.escalationDelay,
            enabled = uiState.canWrite,
            hasError = CoreField.PATIENT_ESCALATION_DELAY in uiState.errors,
            errorMessage = R.string.patient_error_escalation,
            presets = listOf(15, 30, 60),
            onValueChange = viewModel::onEscalationDelayChanged,
        )
    }

    MinuteField(
        label = R.string.patient_missed_label,
        icon = FcIcons.Alarm,
        helper = R.string.patient_missed_helper,
        value = uiState.missedWindow,
        enabled = uiState.canWrite,
        hasError = CoreField.PATIENT_MISSED_WINDOW in uiState.errors,
        errorMessage = R.string.patient_error_missed,
        presets = listOf(60, 120, 180),
        onValueChange = viewModel::onMissedWindowChanged,
    )

    OutlinedTextField(
        value = uiState.agreementNote,
        onValueChange = viewModel::onAgreementNoteChanged,
        modifier = Modifier.fillMaxWidth(),
        enabled = uiState.canWrite,
        minLines = 3,
        label = { FieldLabel(R.string.patient_note_label, FcIcons.StickyNote2) },
        isError = CoreField.PATIENT_AGREEMENT_NOTE in uiState.errors,
        supportingText = {
            if (CoreField.PATIENT_AGREEMENT_NOTE in uiState.errors) {
                ErrorText(stringResource(R.string.patient_error_note))
            } else {
                Text(text = stringResource(R.string.patient_note_helper))
            }
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
}

/** The save action as a full-width bar, pinned below the scrolling fields. */
@Composable
private fun PatientFormSaveBar(
    saving: Boolean,
    onSave: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Button(
            onClick = onSave,
            enabled = !saving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .heightIn(min = 52.dp),
        ) {
            Icon(
                imageVector = FcIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.patient_save))
        }
    }
}

/** A field label with its icon, as the other one-screen forms have. */
@Composable
private fun FieldLabel(@StringRes text: Int, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(inlineIconSize(MaterialTheme.typography.bodyLarge)),
        )
        Text(text = stringResource(text))
    }
}

@Composable
private fun MinuteField(
    @StringRes label: Int,
    icon: ImageVector,
    @StringRes helper: Int,
    value: String,
    enabled: Boolean,
    hasError: Boolean,
    @StringRes errorMessage: Int,
    presets: List<Int>,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { FieldLabel(label, icon) },
        isError = hasError,
        supportingText = {
            if (hasError) {
                ErrorText(stringResource(errorMessage))
            } else {
                Text(text = stringResource(helper))
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        presets.forEach { minutes ->
            TextButton(
                onClick = { onValueChange(minutes.toString()) },
                enabled = enabled,
            ) {
                Text(text = minutes.toString())
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    enabled: Boolean,
    label: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun errorSupport(
    show: Boolean,
    @StringRes message: Int,
): (@Composable () -> Unit)? = if (show) {
    { ErrorText(stringResource(message)) }
} else {
    null
}

@Composable
private fun memberName(member: CircleMember): String =
    member.displayName ?: stringResource(R.string.family_circle_member_unnamed)

@StringRes
internal fun PatientFormError.messageRes(): Int = when (this) {
    PatientFormError.Offline -> R.string.error_offline
    PatientFormError.NotAllowed -> R.string.error_not_allowed
    PatientFormError.NotFound -> R.string.error_not_found
    PatientFormError.Unknown -> R.string.error_unknown
    PatientFormError.AllergiesInvalid -> R.string.patient_info_error_allergies
}
