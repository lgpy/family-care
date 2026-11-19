package io.github.lgpy.familycare.feature.patientinfo.edit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.theme.FamilyCareTheme
import io.github.lgpy.familycare.feature.patientinfo.domain.EmergencyContactField
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfoField
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The edit form of a patient's info (screens v2, docs/domain/patient-info.md
 * "Screens").
 *
 * Allergies and conditions are edited as chips: the selected ones are removable
 * (allergies in the critical container colour), the suggestions add one tap and
 * "Other…" opens a small text dialog. The texts and up to five contacts are
 * compact inputs with leading icons; the less common fields sit under a "More"
 * expander. Validation errors come from the module's domain rules and are
 * marked under the input they belong to — the same numbers the server's check
 * constraints use, so a save is never refused later by the upload queue.
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
    val allergySuggestions = stringArrayResource(
        R.array.patient_info_allergy_suggestions,
    ).toList()
    val conditionSuggestions = stringArrayResource(
        R.array.patient_info_condition_suggestions,
    ).toList()

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onBack()
    }

    var moreExpanded by remember { mutableStateOf(false) }
    var otherKind by remember { mutableStateOf(OtherEntry.NONE) }
    var otherText by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        EditTopBar(
            title = if (uiState.patientName.isBlank()) {
                stringResource(R.string.patient_info_edit_title)
            } else {
                stringResource(R.string.patient_info_edit_for, uiState.patientName)
            },
            onClose = onBack,
        )

        when {
            !uiState.loaded -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))

            !uiState.canWrite -> Text(
                text = stringResource(R.string.patient_info_not_allowed),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(24.dp),
            )

            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    item {
                        TagListEditor(
                            title = stringResource(R.string.patient_info_allergies_title),
                            icon = FcIcons.WarningFilled,
                            iconTint = FamilyCareTheme.colors.criticalAccent,
                            selected = uiState.allergies,
                            selectedContainer = FamilyCareTheme.colors.criticalContainer,
                            selectedContent = FamilyCareTheme.colors.onCriticalContainer,
                            suggestions = allergySuggestions,
                            otherLabel = stringResource(R.string.patient_info_other),
                            error = uiState.hasError(PatientInfoField.ALLERGIES),
                            errorText = stringResource(R.string.patient_info_error_allergies),
                            onRemove = viewModel::removeAllergy,
                            onAdd = viewModel::addAllergyEntry,
                            onOther = {
                                otherText = ""
                                otherKind = OtherEntry.ALLERGY
                            },
                        )
                    }
                    item {
                        TagListEditor(
                            title = stringResource(R.string.patient_info_conditions_title),
                            icon = FcIcons.EcgHeart,
                            iconTint = MaterialTheme.colorScheme.primary,
                            selected = uiState.conditions,
                            selectedContainer = MaterialTheme.colorScheme.secondaryContainer,
                            selectedContent = MaterialTheme.colorScheme.onSecondaryContainer,
                            suggestions = conditionSuggestions,
                            otherLabel = stringResource(R.string.patient_info_other),
                            error = uiState.hasError(PatientInfoField.CONDITIONS),
                            errorText = stringResource(R.string.patient_info_error_conditions),
                            onRemove = viewModel::removeCondition,
                            onAdd = viewModel::addConditionEntry,
                            onOther = {
                                otherText = ""
                                otherKind = OtherEntry.CONDITION
                            },
                        )
                    }

                    item {
                        ContactSection(
                            contacts = uiState.contacts,
                            canAddContact = uiState.canAddContact,
                            contactsError = uiState.hasError(PatientInfoField.EMERGENCY_CONTACTS),
                            contactsErrorText = stringResource(R.string.patient_info_error_contacts),
                            hasError = { index, field -> uiState.hasError(index, field) },
                            onNameChanged = viewModel::onContactNameChanged,
                            onRelationshipChanged = viewModel::onContactRelationshipChanged,
                            onPhoneChanged = viewModel::onContactPhoneChanged,
                            onRemove = viewModel::removeContact,
                            onAdd = viewModel::addContact,
                        )
                    }

                    item {
                        CompactField(
                            label = stringResource(R.string.patient_info_doctor_label),
                            optional = true,
                            icon = FcIcons.Stethoscope,
                            value = uiState.familyDoctor,
                            onValueChanged = viewModel::onFamilyDoctorChanged,
                            error = uiState.hasError(PatientInfoField.FAMILY_DOCTOR),
                            errorText = stringResource(
                                R.string.patient_info_error_text_length,
                                200,
                            ),
                        )
                    }

                    item {
                        MoreExpander(
                            expanded = moreExpanded,
                            summary = stringResource(R.string.patient_info_more_summary),
                            onToggle = { moreExpanded = !moreExpanded },
                        )
                    }

                    if (moreExpanded) {
                        item {
                            CompactField(
                                label = stringResource(R.string.patient_info_sns_label),
                                icon = FcIcons.Badge,
                                value = uiState.snsNumber,
                                onValueChanged = viewModel::onSnsNumberChanged,
                                error = uiState.hasError(PatientInfoField.SNS_NUMBER),
                                errorText = stringResource(R.string.patient_info_error_sns),
                            )
                        }
                        item {
                            CompactField(
                                label = stringResource(R.string.patient_info_centre_label),
                                optional = true,
                                icon = FcIcons.LocationOn,
                                value = uiState.healthCentre,
                                onValueChanged = viewModel::onHealthCentreChanged,
                                error = uiState.hasError(PatientInfoField.HEALTH_CENTRE),
                                errorText = stringResource(
                                    R.string.patient_info_error_text_length,
                                    200,
                                ),
                            )
                        }
                        item {
                            CompactField(
                                label = stringResource(R.string.patient_info_centre_phone_label),
                                optional = true,
                                icon = FcIcons.Call,
                                value = uiState.healthCentrePhone,
                                onValueChanged = viewModel::onHealthCentrePhoneChanged,
                                error = uiState.hasError(PatientInfoField.HEALTH_CENTRE_PHONE),
                                errorText = stringResource(R.string.patient_info_error_phone),
                            )
                        }
                        item {
                            CompactField(
                                label = stringResource(R.string.patient_info_notes_title),
                                optional = true,
                                icon = FcIcons.StickyNote2,
                                value = uiState.notes,
                                onValueChanged = viewModel::onNotesChanged,
                                singleLine = false,
                                minLines = 3,
                                error = uiState.hasError(PatientInfoField.NOTES),
                                errorText = stringResource(R.string.patient_info_error_notes),
                            )
                        }
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
                }

                HorizontalDivider()
                Button(
                    onClick = viewModel::save,
                    enabled = !uiState.saving,
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
                    Text(
                        text = stringResource(R.string.patient_info_save),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }

    if (otherKind != OtherEntry.NONE) {
        OtherEntryDialog(
            title = stringResource(
                if (otherKind == OtherEntry.ALLERGY) {
                    R.string.patient_info_add_allergy
                } else {
                    R.string.patient_info_add_condition
                },
            ),
            value = otherText,
            onValueChanged = { otherText = it },
            onConfirm = {
                val entry = otherText
                if (otherKind == OtherEntry.ALLERGY) {
                    viewModel.addAllergyEntry(entry)
                } else {
                    viewModel.addConditionEntry(entry)
                }
                otherText = ""
                otherKind = OtherEntry.NONE
            },
            onDismiss = {
                otherText = ""
                otherKind = OtherEntry.NONE
            },
        )
    }
}

/** Which list the "Other…" dialog is adding to. */
private enum class OtherEntry { NONE, ALLERGY, CONDITION }

/** Close and "<Name>'s info", as in the mockup (no back arrow). */
@Composable
private fun EditTopBar(title: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = FcIcons.Close,
                contentDescription = stringResource(R.string.close),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * A titled list of removable chips plus its suggestion chips and "Other…".
 *
 * A suggestion that is already selected is hidden, case-insensitively, so the
 * same allergy cannot be added twice from the list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagListEditor(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    selected: List<String>,
    selectedContainer: Color,
    selectedContent: Color,
    suggestions: List<String>,
    otherLabel: String,
    error: Boolean,
    errorText: String,
    onRemove: (Int) -> Unit,
    onAdd: (String) -> Unit,
    onOther: () -> Unit,
) {
    val available = suggestions.filterNot { suggestion ->
        selected.any { it.equals(suggestion, ignoreCase = true) }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        if (selected.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                selected.forEachIndexed { index, entry ->
                    EntryChip(
                        label = entry,
                        container = selectedContainer,
                        content = selectedContent,
                        trailingIcon = FcIcons.Close,
                        onClick = { onRemove(index) },
                    )
                }
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            available.forEach { suggestion ->
                EntryChip(
                    label = suggestion,
                    leadingIcon = FcIcons.Add,
                    onClick = { onAdd(suggestion) },
                )
            }
            EntryChip(
                label = otherLabel,
                leadingIcon = FcIcons.Edit,
                onClick = onOther,
            )
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

/** One outlined (suggestion) or filled (selected) chip. */
@Composable
private fun EntryChip(
    label: String,
    onClick: () -> Unit,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    container: Color = Color.Transparent,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val shape = RoundedCornerShape(10.dp)
    val borderColor = if (container == Color.Transparent) {
        MaterialTheme.colorScheme.outlineVariant
    } else {
        null
    }

    Row(
        modifier = Modifier
            .clip(shape)
            .background(container)
            .then(
                if (borderColor != null) {
                    Modifier.border(BorderStroke(1.dp, borderColor), shape)
                } else {
                    Modifier
                },
            )
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leadingIcon?.let { icon ->
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            fontWeight = if (container == Color.Transparent) {
                FontWeight.Medium
            } else {
                FontWeight.Bold
            },
        )
        trailingIcon?.let { icon ->
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The emergency contacts (one compact block each) and its add action. */
@Composable
private fun ContactSection(
    contacts: List<ContactDraft>,
    canAddContact: Boolean,
    contactsError: Boolean,
    contactsErrorText: String,
    hasError: (Int, EmergencyContactField) -> Boolean,
    onNameChanged: (Int, String) -> Unit,
    onRelationshipChanged: (Int, String) -> Unit,
    onPhoneChanged: (Int, String) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = FcIcons.Emergency,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.patient_info_contacts_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        if (contactsError) {
            Text(
                text = contactsErrorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        contacts.forEachIndexed { index, contact ->
            ContactEditor(
                index = index,
                contact = contact,
                hasError = { field -> hasError(index, field) },
                onNameChanged = { onNameChanged(index, it) },
                onRelationshipChanged = { onRelationshipChanged(index, it) },
                onPhoneChanged = { onPhoneChanged(index, it) },
                onRemove = { onRemove(index) },
            )
        }

        if (canAddContact) {
            OutlinedButton(onClick = onAdd) {
                Icon(
                    imageVector = FcIcons.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.patient_info_add_contact),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** One emergency contact's three compact inputs. */
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.patient_info_contact, index + 1),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = FcIcons.Close,
                    contentDescription = stringResource(R.string.patient_info_remove_contact),
                )
            }
        }
        CompactField(
            label = stringResource(R.string.patient_info_contact_name),
            icon = FcIcons.Person,
            value = contact.name,
            onValueChanged = onNameChanged,
            error = hasError(EmergencyContactField.NAME),
            errorText = stringResource(R.string.patient_info_error_contact_name),
        )
        CompactField(
            label = stringResource(R.string.patient_info_contact_relationship),
            optional = true,
            icon = FcIcons.Groups,
            value = contact.relationship,
            onValueChanged = onRelationshipChanged,
            error = hasError(EmergencyContactField.RELATIONSHIP),
            errorText = stringResource(R.string.patient_info_error_relationship),
        )
        CompactField(
            label = stringResource(R.string.patient_info_contact_phone),
            icon = FcIcons.Call,
            value = contact.phone,
            onValueChanged = onPhoneChanged,
            error = hasError(EmergencyContactField.PHONE),
            errorText = stringResource(R.string.patient_info_error_phone),
        )
    }
}

/** The "More" expander: a summary line while collapsed, the fields when open. */
@Composable
private fun MoreExpander(
    expanded: Boolean,
    summary: String,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = FcIcons.Tune,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.patient_info_more),
                style = MaterialTheme.typography.titleSmall,
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(if (expanded) 180f else 0f),
        )
    }
}

/** One compact input: leading icon, label, value and its inline error. */
@Composable
private fun CompactField(
    label: String,
    icon: ImageVector,
    value: String,
    onValueChanged: (String) -> Unit,
    error: Boolean,
    errorText: String,
    optional: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChanged,
            label = {
                Text(
                    text = if (optional) {
                        stringResource(R.string.patient_info_optional, label)
                    } else {
                        label
                    },
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            },
            singleLine = singleLine,
            minLines = minLines,
            shape = RoundedCornerShape(14.dp),
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

/** The small text dialog behind the "Other…" chip. */
@Composable
private fun OtherEntryDialog(
    title: String,
    value: String,
    onValueChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChanged,
                singleLine = true,
                label = { Text(text = title) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = value.isNotBlank(),
            ) {
                Text(text = stringResource(R.string.patient_info_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}
