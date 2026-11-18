package io.github.lgpy.familycare.feature.patientinfo.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.feature.patientinfo.domain.EmergencyContactField
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfoField
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The edit form of a patient's info (docs/domain/patient-info.md "Screens").
 *
 * The allergies and conditions are edited as removable chips with a field that
 * adds one; the texts and up to five contacts are plain inputs. Validation
 * errors come from the module's domain rules and are marked under the input they
 * belong to — the same numbers the server's check constraints use, so a save is
 * never refused later by the upload queue.
 *
 * Closing on success is the screen's job: [PatientInfoEditUiState.saved] is set
 * by the view model once the write is queued.
 */
@Composable
fun PatientInfoEditScreen(
    patientId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<PatientInfoEditViewModel>(
        parameters = { parametersOf(patientId) },
    )
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onBack()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
            Text(
                text = stringResource(R.string.patient_info_edit_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        when {
            !uiState.loaded -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))

            !uiState.canWrite -> Text(
                text = stringResource(R.string.patient_info_not_allowed),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(24.dp),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    TagListEditor(
                        title = stringResource(R.string.patient_info_allergies_title),
                        entries = uiState.allergies,
                        draft = uiState.allergyDraft,
                        draftLabel = stringResource(R.string.patient_info_add_allergy),
                        canAdd = uiState.canAddAllergy,
                        error = uiState.hasError(PatientInfoField.ALLERGIES),
                        errorText = stringResource(R.string.patient_info_error_allergies),
                        onDraftChanged = viewModel::onAllergyDraftChanged,
                        onAdd = viewModel::addAllergy,
                        onRemove = viewModel::removeAllergy,
                    )
                }
                item {
                    TagListEditor(
                        title = stringResource(R.string.patient_info_conditions_title),
                        entries = uiState.conditions,
                        draft = uiState.conditionDraft,
                        draftLabel = stringResource(R.string.patient_info_add_condition),
                        canAdd = uiState.canAddCondition,
                        error = uiState.hasError(PatientInfoField.CONDITIONS),
                        errorText = stringResource(R.string.patient_info_error_conditions),
                        onDraftChanged = viewModel::onConditionDraftChanged,
                        onAdd = viewModel::addCondition,
                        onRemove = viewModel::removeCondition,
                    )
                }
                item {
                    Field(
                        label = stringResource(R.string.patient_info_sns_label),
                        value = uiState.snsNumber,
                        onValueChanged = viewModel::onSnsNumberChanged,
                        error = uiState.hasError(PatientInfoField.SNS_NUMBER),
                        errorText = stringResource(R.string.patient_info_error_sns),
                    )
                }
                item {
                    Field(
                        label = stringResource(R.string.patient_info_doctor_label),
                        value = uiState.familyDoctor,
                        onValueChanged = viewModel::onFamilyDoctorChanged,
                        error = uiState.hasError(PatientInfoField.FAMILY_DOCTOR),
                        errorText = stringResource(R.string.patient_info_error_text_length, 200),
                    )
                }
                item {
                    Field(
                        label = stringResource(R.string.patient_info_centre_label),
                        value = uiState.healthCentre,
                        onValueChanged = viewModel::onHealthCentreChanged,
                        error = uiState.hasError(PatientInfoField.HEALTH_CENTRE),
                        errorText = stringResource(R.string.patient_info_error_text_length, 200),
                    )
                }
                item {
                    Field(
                        label = stringResource(R.string.patient_info_centre_phone_label),
                        value = uiState.healthCentrePhone,
                        onValueChanged = viewModel::onHealthCentrePhoneChanged,
                        error = uiState.hasError(PatientInfoField.HEALTH_CENTRE_PHONE),
                        errorText = stringResource(R.string.patient_info_error_phone),
                    )
                }

                item {
                    Text(
                        text = stringResource(R.string.patient_info_contacts_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (uiState.hasError(PatientInfoField.EMERGENCY_CONTACTS)) {
                    item {
                        Text(
                            text = stringResource(R.string.patient_info_error_contacts),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                itemsIndexed(uiState.contacts) { index, contact ->
                    ContactEditor(
                        index = index,
                        contact = contact,
                        hasError = { field -> uiState.hasError(index, field) },
                        onNameChanged = { viewModel.onContactNameChanged(index, it) },
                        onRelationshipChanged = { viewModel.onContactRelationshipChanged(index, it) },
                        onPhoneChanged = { viewModel.onContactPhoneChanged(index, it) },
                        onRemove = { viewModel.removeContact(index) },
                    )
                }
                item {
                    if (uiState.canAddContact) {
                        OutlinedButton(onClick = viewModel::addContact) {
                            Text(text = stringResource(R.string.patient_info_add_contact))
                        }
                    }
                }

                item {
                    Field(
                        label = stringResource(R.string.patient_info_notes_title),
                        value = uiState.notes,
                        onValueChanged = viewModel::onNotesChanged,
                        singleLine = false,
                        error = uiState.hasError(PatientInfoField.NOTES),
                        errorText = stringResource(R.string.patient_info_error_notes),
                    )
                }

                if (uiState.notAllowed) {
                    item {
                        Text(
                            text = stringResource(R.string.patient_info_not_allowed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (uiState.saveFailed) {
                    item {
                        Text(
                            text = stringResource(R.string.patient_info_save_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                item {
                    Button(
                        onClick = viewModel::save,
                        enabled = !uiState.saving,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(text = stringResource(R.string.patient_info_save))
                    }
                }
            }
        }
    }
}

/** A titled list of removable entries plus its "add" field. */
@Composable
private fun TagListEditor(
    title: String,
    entries: List<String>,
    draft: String,
    draftLabel: String,
    canAdd: Boolean,
    error: Boolean,
    errorText: String,
    onDraftChanged: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        entries.forEachIndexed { index, entry ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemove(index) }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.patient_info_remove, entry),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChanged,
                label = { Text(text = draftLabel) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAdd, enabled = canAdd) {
                Text(text = stringResource(R.string.patient_info_add))
            }
        }
        if (error) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** One emergency contact's three inputs. */
@Composable
private fun ContactEditor(
    index: Int,
    contact: ContactDraft,
    hasError: (EmergencyContactField) -> Boolean,
    onNameChanged: (String) -> Unit,
    onRelationshipChanged: (String) -> Unit,
    onPhoneChanged: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.patient_info_contact, index + 1),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.patient_info_remove_contact),
                )
            }
        }
        Field(
            label = stringResource(R.string.patient_info_contact_name),
            value = contact.name,
            onValueChanged = onNameChanged,
            error = hasError(EmergencyContactField.NAME),
            errorText = stringResource(R.string.patient_info_error_contact_name),
        )
        Field(
            label = stringResource(R.string.patient_info_contact_relationship),
            value = contact.relationship,
            onValueChanged = onRelationshipChanged,
            error = hasError(EmergencyContactField.RELATIONSHIP),
            errorText = stringResource(R.string.patient_info_error_relationship),
        )
        Field(
            label = stringResource(R.string.patient_info_contact_phone),
            value = contact.phone,
            onValueChanged = onPhoneChanged,
            error = hasError(EmergencyContactField.PHONE),
            errorText = stringResource(R.string.patient_info_error_phone),
        )
    }
}

/** One labelled input with its inline error. */
@Composable
private fun Field(
    label: String,
    value: String,
    onValueChanged: (String) -> Unit,
    error: Boolean,
    errorText: String,
    singleLine: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChanged,
            label = { Text(text = label) },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            modifier = Modifier.fillMaxWidth(),
        )
        if (error) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
