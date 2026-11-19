package io.github.lgpy.familycare.feature.medications.form

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.domain.MedicationForm
import io.github.lgpy.familycare.feature.medications.domain.MedicationSuggestion
import io.github.lgpy.familycare.feature.medications.ui.FieldError
import io.github.lgpy.familycare.feature.medications.ui.ScheduleFields
import io.github.lgpy.familycare.feature.medications.ui.labelRes
import io.github.lgpy.familycare.feature.medications.ui.messageRes
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The add-medication flow, four steps: what → when and how much → stock → who is
 * reminded. In edit mode ([medicationId] not null) it is the "Edit details"
 * screen: one step with the low-stock threshold and an optional recount, saving
 * with `MedicationRepository.update` (which leaves the regimen untouched) and, if
 * the recount is filled, a stock count after it.
 */
@Composable
fun MedicationFormScreen(
    patientId: String,
    medicationId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<MedicationFormViewModel>(
        parameters = { parametersOf(patientId, medicationId ?: "") },
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
            Column {
                Text(
                    text = stringResource(
                        if (uiState.isEdit) R.string.meds_edit_title else R.string.meds_add_title,
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (!uiState.isEdit) {
                    Text(
                        text = stringResource(
                            R.string.meds_step_of,
                            uiState.stepNumber,
                            MedicationFormUiState.STEP_COUNT,
                        ) + stringResource(R.string.meds_list_separator) +
                            stringResource(uiState.stepTitleRes()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        when {
            !uiState.loaded -> CircularProgressIndicator()
            uiState.notFound -> Text(
                text = stringResource(R.string.meds_not_found),
                style = MaterialTheme.typography.bodyLarge,
            )

            else -> {
                if (!uiState.canWrite) {
                    Text(
                        text = stringResource(R.string.patient_read_only),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                when {
                    uiState.isEdit -> DetailsStep(viewModel, uiState)
                    uiState.step == 0 -> DetailsStep(viewModel, uiState)
                    uiState.step == 1 -> ScheduleStep(viewModel, uiState)
                    uiState.step == 2 -> StockStep(viewModel, uiState)
                    else -> RemindersStep(viewModel, uiState)
                }

                uiState.error?.let { error ->
                    Text(
                        text = stringResource(error.messageRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (uiState.canWrite) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!uiState.isEdit && uiState.step > 0) {
                            OutlinedButton(
                                onClick = viewModel::previous,
                                enabled = !uiState.saving,
                                modifier = Modifier.weight(1f),
                            ) { Text(text = stringResource(R.string.meds_previous)) }
                        }
                        val saves = uiState.isEdit || uiState.step == MedicationFormUiState.STEP_COUNT - 1
                        Button(
                            onClick = { if (saves) viewModel.save() else viewModel.next() },
                            enabled = !uiState.saving,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = stringResource(
                                    when {
                                        uiState.saving -> R.string.meds_step_busy
                                        saves -> R.string.meds_save
                                        else -> R.string.meds_next
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun MedicationFormUiState.stepTitleRes(): Int = when (step) {
    0 -> R.string.meds_step_what
    1 -> R.string.meds_step_when
    2 -> R.string.meds_step_stock
    else -> R.string.meds_step_who
}

/**
 * The catalogue suggestions under the name field (D31): one row per medicine,
 * its strength and form, and an OTC tag when no prescription is needed. Tapping
 * a row fills the step; typing on keeps the rows and free text both available.
 */
@Composable
private fun SuggestionList(
    suggestions: List<MedicationSuggestion>,
    enabled: Boolean,
    onPick: (MedicationSuggestion) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        suggestions.forEach { suggestion ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onPick(suggestion) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = suggestionLine(suggestion),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (suggestion.otc) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = stringResource(R.string.meds_catalog_otc),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** "Name · strength · form", skipping what the entry does not have. */
@Composable
private fun suggestionLine(suggestion: MedicationSuggestion): String {
    val parts = buildList {
        add(suggestion.name)
        if (suggestion.strength.isNotBlank()) add(suggestion.strength)
        if (suggestion.formText.isNotBlank()) add(stringResource(suggestion.form.labelRes()))
    }
    return parts.joinToString(stringResource(R.string.meds_list_separator))
}

@Composable
private fun DetailsStep(viewModel: MedicationFormViewModel, state: MedicationFormUiState) {
    val enabled = state.canWrite
    val errors = state.errors

    OutlinedTextField(
        value = state.name,
        onValueChange = viewModel::onNameChanged,
        label = { Text(text = stringResource(R.string.meds_name_label)) },
        enabled = enabled,
        isError = MedicationField.MEDICATION_NAME in errors,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FieldError(MedicationField.MEDICATION_NAME in errors, R.string.meds_error_name)

    if (state.suggestions.isNotEmpty()) {
        SuggestionList(
            suggestions = state.suggestions,
            enabled = enabled,
            onPick = viewModel::onSuggestionPicked,
        )
        Text(
            text = stringResource(R.string.meds_catalog_footnote),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    OutlinedTextField(
        value = state.activeIngredient,
        onValueChange = viewModel::onActiveIngredientChanged,
        label = { Text(text = stringResource(R.string.meds_ingredient_label)) },
        enabled = enabled,
        isError = MedicationField.MEDICATION_ACTIVE_INGREDIENT in errors,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FieldError(
        MedicationField.MEDICATION_ACTIVE_INGREDIENT in errors,
        R.string.meds_error_ingredient,
    )

    OutlinedTextField(
        value = state.strength,
        onValueChange = viewModel::onStrengthChanged,
        label = { Text(text = stringResource(R.string.meds_strength_label)) },
        supportingText = { Text(text = stringResource(R.string.meds_strength_helper)) },
        enabled = enabled,
        isError = MedicationField.MEDICATION_STRENGTH in errors,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FieldError(MedicationField.MEDICATION_STRENGTH in errors, R.string.meds_error_strength)

    Text(
        text = stringResource(R.string.meds_form_label),
        style = MaterialTheme.typography.titleSmall,
    )
    MedicationForm.entries.forEach { form ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = state.form == form,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = { viewModel.onFormChanged(form) },
                )
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = state.form == form, onClick = null, enabled = enabled)
            Text(
                text = stringResource(form.labelRes()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    OutlinedTextField(
        value = state.stockUnit,
        onValueChange = viewModel::onStockUnitChanged,
        label = { Text(text = stringResource(R.string.meds_stock_unit_label)) },
        supportingText = { Text(text = stringResource(R.string.meds_stock_unit_helper)) },
        enabled = enabled,
        isError = MedicationField.MEDICATION_STOCK_UNIT in errors,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FieldError(MedicationField.MEDICATION_STOCK_UNIT in errors, R.string.meds_error_stock_unit)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = state.requiresPrescription,
                enabled = enabled,
                role = Role.Checkbox,
                onClick = { viewModel.onRequiresPrescriptionChanged(!state.requiresPrescription) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = state.requiresPrescription,
            onCheckedChange = { viewModel.onRequiresPrescriptionChanged(it) },
            enabled = enabled,
        )
        Text(
            text = stringResource(R.string.meds_prescription_label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }

    OutlinedTextField(
        value = state.prescriptionValidUntil,
        onValueChange = viewModel::onPrescriptionValidUntilChanged,
        label = { Text(text = stringResource(R.string.meds_prescription_valid_label)) },
        supportingText = { Text(text = stringResource(R.string.meds_date_helper)) },
        enabled = enabled && state.requiresPrescription,
        isError = MedicationField.MEDICATION_PRESCRIPTION_VALID_UNTIL in errors,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FieldError(
        MedicationField.MEDICATION_PRESCRIPTION_VALID_UNTIL in errors,
        R.string.meds_error_start,
    )

    OutlinedTextField(
        value = state.instructions,
        onValueChange = viewModel::onInstructionsChanged,
        label = { Text(text = stringResource(R.string.meds_instructions_label)) },
        enabled = enabled,
        isError = MedicationField.MEDICATION_INSTRUCTIONS in errors,
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    FieldError(MedicationField.MEDICATION_INSTRUCTIONS in errors, R.string.meds_error_instructions)

    if (state.isEdit) {
        // M5/D28: stock is its own step when adding, and part of "Edit details"
        // afterwards: the threshold moves here, and a recount is optional
        // because it is a separate ledger entry, not a column.
        HorizontalDivider()
        OutlinedTextField(
            value = state.lowStockThresholdDays,
            onValueChange = viewModel::onLowStockThresholdChanged,
            label = { Text(text = stringResource(R.string.meds_stock_threshold_label)) },
            enabled = enabled,
            isError = MedicationField.MEDICATION_LOW_STOCK_THRESHOLD in errors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FieldError(
            MedicationField.MEDICATION_LOW_STOCK_THRESHOLD in errors,
            R.string.meds_error_low_stock,
        )

        OutlinedTextField(
            value = state.recountQuantity,
            onValueChange = viewModel::onRecountQuantityChanged,
            label = { Text(text = stringResource(R.string.meds_stock_recount_label)) },
            enabled = enabled,
            isError = MedicationField.STOCK_QUANTITY in errors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FieldError(MedicationField.STOCK_QUANTITY in errors, R.string.meds_error_stock_quantity)
    }
}

@Composable
private fun ScheduleStep(viewModel: MedicationFormViewModel, state: MedicationFormUiState) {
    ScheduleFields(
        state = state.schedule,
        errors = state.errors,
        enabled = state.canWrite,
        onScheduleType = viewModel::onScheduleTypeChanged,
        onWeekdayToggled = viewModel::onWeekdayToggled,
        onIntervalDays = viewModel::onIntervalDaysChanged,
        onTimeChanged = viewModel::onTimeChanged,
        onQuantityStep = viewModel::onQuantityStep,
        onAddTime = viewModel::onAddTime,
        onRemoveTime = viewModel::onRemoveTime,
        onStartDateChanged = viewModel::onStartDateChanged,
        onEndDateChanged = viewModel::onEndDateChanged,
    )
}

@Composable
private fun StockStep(viewModel: MedicationFormViewModel, state: MedicationFormUiState) {
    val enabled = state.canWrite

    Text(
        text = stringResource(R.string.meds_step_stock),
        style = MaterialTheme.typography.titleSmall,
    )

    OutlinedTextField(
        value = state.stockQuantity,
        onValueChange = viewModel::onStockQuantityChanged,
        label = { Text(text = stringResource(R.string.meds_stock_count_label, state.stockUnit)) },
        enabled = enabled,
        isError = MedicationField.STOCK_QUANTITY in state.errors,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FieldError(MedicationField.STOCK_QUANTITY in state.errors, R.string.meds_error_stock_quantity)

    // D31: the picked pack knows how many units the box holds. Only offered
    // while the count is still empty, so it never overwrites a typed number.
    if (state.stockQuantity.isBlank() && state.packOptions.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.packOptions.forEach { units ->
                AssistChip(
                    onClick = { viewModel.onUsePackQuantity(units) },
                    enabled = enabled,
                    label = { Text(text = stringResource(R.string.meds_stock_pack_chip, units)) },
                )
            }
        }
    }

    OutlinedTextField(
        value = state.lowStockThresholdDays,
        onValueChange = viewModel::onLowStockThresholdChanged,
        label = { Text(text = stringResource(R.string.meds_stock_threshold_label)) },
        enabled = enabled,
        isError = MedicationField.MEDICATION_LOW_STOCK_THRESHOLD in state.errors,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FieldError(
        MedicationField.MEDICATION_LOW_STOCK_THRESHOLD in state.errors,
        R.string.meds_error_low_stock,
    )
}

@Composable
private fun RemindersStep(viewModel: MedicationFormViewModel, state: MedicationFormUiState) {
    val enabled = state.canWrite

    Text(
        text = stringResource(R.string.meds_step_who),
        style = MaterialTheme.typography.titleSmall,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = state.inheritReminders,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { viewModel.onInheritRemindersChanged(true) },
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = state.inheritReminders, onClick = null, enabled = enabled)
        Text(
            text = state.patientName?.let {
                stringResource(R.string.meds_reminders_same, it)
            } ?: stringResource(R.string.meds_reminders_same_unknown),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = !state.inheritReminders,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { viewModel.onInheritRemindersChanged(false) },
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = !state.inheritReminders, onClick = null, enabled = enabled)
        Text(
            text = stringResource(R.string.meds_reminders_override),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }

    if (!state.inheritReminders) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.patient_policy_label),
            style = MaterialTheme.typography.titleSmall,
        )
        ReminderPolicy.offered.forEach { policy ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = state.reminderPolicy == policy,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { viewModel.onReminderPolicyChanged(policy) },
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.reminderPolicy == policy,
                    onClick = null,
                    enabled = enabled,
                )
                Text(
                    text = stringResource(policy.labelRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        FieldError(
            MedicationField.MEDICATION_REMINDER_POLICY in state.errors,
            R.string.meds_error_policy,
        )

        Text(
            text = stringResource(R.string.patient_primary_label),
            style = MaterialTheme.typography.titleSmall,
        )
        state.members.forEach { member ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = state.primaryCaregiverId == member.userId,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { viewModel.onPrimaryCaregiverChanged(member.userId) },
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.primaryCaregiverId == member.userId,
                    onClick = null,
                    enabled = enabled,
                )
                Text(
                    text = member.displayName
                        ?: stringResource(R.string.family_circle_member_unnamed),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        FieldError(
            MedicationField.MEDICATION_PRIMARY_CAREGIVER in state.errors,
            R.string.meds_error_primary,
        )
    }

    Text(
        text = stringResource(R.string.meds_reminders_hint),
        style = MaterialTheme.typography.bodySmall,
    )
}

/** The policy label, the same words the patient form uses. */
@Composable
private fun ReminderPolicy.labelRes(): Int = when (this) {
    ReminderPolicy.PRIMARY_WITH_ESCALATION -> R.string.patients_policy_primary_with_escalation
    ReminderPolicy.ALL_CAREGIVERS -> R.string.patients_policy_all_caregivers
    ReminderPolicy.ROTA -> R.string.patients_policy_unavailable
}
