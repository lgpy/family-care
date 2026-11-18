package io.github.lgpy.familycare.ui.patients

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.circle.CircleMember
import io.github.lgpy.familycare.core.domain.circle.CoreField
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.core.ui.R
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The patient screen (docs/ux/screens.md §3): name, linked app user, reminder
 * policy, primary caregiver, escalation and missed-window minutes, and the
 * optional agreement note. One screen for adding and editing.
 *
 * Viewers see the same values without the ability to change them; the save
 * button is only shown when [MemberRole.canWrite], and the repository refuses a
 * write from a viewer anyway.
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
            Text(
                text = stringResource(
                    if (uiState.isNew) R.string.patient_new_title else R.string.patient_edit_title,
                ),
                style = MaterialTheme.typography.headlineMedium,
            )
        }

        when {
            !uiState.loaded -> CircularProgressIndicator()
            uiState.notFound -> Text(
                text = stringResource(R.string.patient_not_available),
                style = MaterialTheme.typography.bodyLarge,
            )

            else -> PatientFormFields(viewModel = viewModel, uiState = uiState)
        }

        Spacer(modifier = Modifier.height(24.dp))
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

    OutlinedTextField(
        value = uiState.displayName,
        onValueChange = viewModel::onNameChanged,
        modifier = Modifier.fillMaxWidth(),
        enabled = canWrite,
        singleLine = true,
        label = { Text(text = stringResource(R.string.patient_name_label)) },
        isError = CoreField.PATIENT_DISPLAY_NAME in uiState.errors,
        supportingText = errorSupport(
            show = CoreField.PATIENT_DISPLAY_NAME in uiState.errors,
            message = R.string.patient_error_name,
        ),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    // "This patient is also an app user": a member, or nobody.
    Text(
        text = stringResource(R.string.patient_linked_user_label),
        style = MaterialTheme.typography.titleMedium,
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

    Text(
        text = stringResource(R.string.patient_policy_label),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = stringResource(R.string.patient_policy_helper),
        style = MaterialTheme.typography.bodyMedium,
    )
    ReminderPolicy.offered.forEach { policy ->
        ChoiceRow(
            selected = uiState.reminderPolicy == policy,
            enabled = canWrite,
            label = stringResource(policy.labelRes()),
            onSelect = { viewModel.onReminderPolicyChanged(policy) },
        )
    }
    if (CoreField.PATIENT_REMINDER_POLICY in uiState.errors) {
        ErrorText(stringResource(R.string.patient_error_policy))
    }

    if (uiState.reminderPolicy == ReminderPolicy.PRIMARY_WITH_ESCALATION) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.patient_primary_label),
            style = MaterialTheme.typography.titleMedium,
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

    MinuteField(
        label = R.string.patient_escalation_label,
        helper = R.string.patient_escalation_helper,
        value = uiState.escalationDelay,
        enabled = canWrite,
        hasError = CoreField.PATIENT_ESCALATION_DELAY in uiState.errors,
        errorMessage = R.string.patient_error_escalation,
        presets = listOf(15, 30, 60),
        onValueChange = viewModel::onEscalationDelayChanged,
    )

    MinuteField(
        label = R.string.patient_missed_label,
        helper = R.string.patient_missed_helper,
        value = uiState.missedWindow,
        enabled = canWrite,
        hasError = CoreField.PATIENT_MISSED_WINDOW in uiState.errors,
        errorMessage = R.string.patient_error_missed,
        presets = listOf(60, 120, 180),
        onValueChange = viewModel::onMissedWindowChanged,
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    OutlinedTextField(
        value = uiState.agreementNote,
        onValueChange = viewModel::onAgreementNoteChanged,
        modifier = Modifier.fillMaxWidth(),
        enabled = canWrite,
        minLines = 3,
        label = { Text(text = stringResource(R.string.patient_note_label)) },
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

    uiState.error?.let { error ->
        ErrorText(stringResource(error.messageRes()))
    }

    if (canWrite) {
        Button(
            onClick = viewModel::save,
            enabled = !uiState.saving,
        ) {
            Text(text = stringResource(R.string.patient_save))
        }
    }
}

@Composable
private fun MinuteField(
    @StringRes label: Int,
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
        label = { Text(text = stringResource(label)) },
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
}
