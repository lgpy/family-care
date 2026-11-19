package io.github.lgpy.familycare.feature.medications.form

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.components.FcIcons
import io.github.lgpy.familycare.core.ui.components.inlineIconSize
import io.github.lgpy.familycare.core.ui.components.PillTile
import io.github.lgpy.familycare.core.ui.components.ReflowRow
import io.github.lgpy.familycare.core.ui.components.pillDescription
import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.domain.MedicationForm
import io.github.lgpy.familycare.feature.medications.domain.MedicationSuggestion
import io.github.lgpy.familycare.feature.medications.ui.FieldError
import io.github.lgpy.familycare.feature.medications.ui.PillLookSheet
import io.github.lgpy.familycare.feature.medications.ui.ScheduleFields
import io.github.lgpy.familycare.feature.medications.ui.formatQuantity
import io.github.lgpy.familycare.feature.medications.ui.formatTime
import io.github.lgpy.familycare.feature.medications.ui.labelRes
import io.github.lgpy.familycare.feature.medications.ui.localizedDate
import io.github.lgpy.familycare.feature.medications.ui.messageRes
import io.github.lgpy.familycare.feature.medications.ui.stockUnitName
import java.time.LocalDate
import java.time.LocalTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The one-screen add / edit medication form (docs/ux/mockups/screens-v2.html,
 * "Add medicine (one screen)"). Add mode shows the catalogue search, the drawn
 * pill look, the time chips, the per-time amount, the optional opening count and
 * "More options"; edit mode keeps everything except the regimen/stock-opening
 * sections because those live on [io.github.lgpy.familycare.feature.medications.regimen.ChangeRegimenScreen].
 *
 * The bottom bar always holds the single save action; the validation message
 * sits next to it so a refusal is visible even when the offending field is above
 * the fold.
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

    var showPillSheet by remember { mutableStateOf(false) }
    var showOtherTime by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        bottomBar = {
            if (uiState.loaded && !uiState.notFound) {
                SaveBar(state = uiState, onSave = viewModel::save)
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FormHeader(state = uiState, onClose = onBack)

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

                    MedicineSection(viewModel, uiState)
                    LookSection(state = uiState, onChange = { showPillSheet = true })

                    if (!uiState.isEdit) {
                        WhenSection(
                            state = uiState,
                            onPresetToggled = viewModel::onPresetTimeToggled,
                            onOtherTime = { showOtherTime = true },
                            onCustomTimeRemoved = viewModel::onCustomTimeRemoved,
                        )
                        EachTimeSection(state = uiState, onStep = viewModel::onTimeQuantityStep)
                        HowManySection(viewModel, uiState)
                    }

                    MoreOptionsSection(viewModel, uiState)
                }
            }
        }
    }

    if (showPillSheet) {
        PillLookSheet(
            initial = uiState.pillLook,
            onDismiss = { showPillSheet = false },
            onDone = { look ->
                viewModel.onPillLookChanged(look)
                showPillSheet = false
            },
        )
    }

    if (showOtherTime) {
        OtherTimeDialog(
            onDismiss = { showOtherTime = false },
            onConfirm = { hour, minute ->
                viewModel.onCustomTimeAdded(hour, minute)
                showOtherTime = false
            },
        )
    }
}

@Composable
private fun FormHeader(state: MedicationFormUiState, onClose: () -> Unit) {
    val title = when {
        state.isEdit -> stringResource(R.string.meds_edit_title)
        state.patientName != null -> stringResource(R.string.meds_new_title, state.patientName)
        else -> stringResource(R.string.meds_new_title_unknown)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = FcIcons.Close,
                contentDescription = stringResource(R.string.close),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        )
    }
}

/** The one action of the screen, with the validation message beside it. */
@Composable
private fun SaveBar(state: MedicationFormUiState, onSave: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.errors.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.meds_error_invalid),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.error?.let { error ->
                Text(
                    text = stringResource(error.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val label = when {
                state.saving -> stringResource(R.string.meds_step_busy)
                state.isEdit -> stringResource(R.string.meds_save)
                state.name.trim().isNotEmpty() ->
                    stringResource(R.string.meds_add_action, state.name.trim())

                else -> stringResource(R.string.meds_add_action_empty)
            }
            Button(
                onClick = onSave,
                enabled = state.canWrite && !state.saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                if (!state.saving) {
                    Icon(imageVector = FcIcons.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(text = label)
            }
        }
    }
}

@Composable
private fun SectionTitle(icon: ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

// --- Medicine ---------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MedicineSection(viewModel: MedicationFormViewModel, state: MedicationFormUiState) {
    val enabled = state.canWrite
    val errors = state.errors

    SectionTitle(icon = FcIcons.Medication, title = stringResource(R.string.meds_medicine_section))

    OutlinedTextField(
        value = state.name,
        onValueChange = viewModel::onNameChanged,
        label = { Text(text = stringResource(R.string.meds_name_label)) },
        leadingIcon = { Icon(imageVector = FcIcons.Search, contentDescription = null) },
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
        value = state.strength,
        onValueChange = viewModel::onStrengthChanged,
        label = { Text(text = stringResource(R.string.meds_strength_label)) },
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
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MedicationForm.entries.forEach { form ->
            FilterChip(
                selected = state.form == form,
                onClick = { viewModel.onFormChanged(form) },
                enabled = enabled,
                label = { Text(text = stringResource(form.labelRes())) },
                leadingIcon = {
                    Icon(
                        imageVector = formIcon(form),
                        contentDescription = null,
                        modifier = Modifier.size(inlineIconSize(MaterialTheme.typography.labelLarge)),
                    )
                },
            )
        }
    }
}

/** A decorative icon per form; the chip's label is the real information. */
@Composable
private fun formIcon(form: MedicationForm): ImageVector = when (form) {
    MedicationForm.TABLET, MedicationForm.CAPSULE, MedicationForm.DROPS -> FcIcons.Pill
    MedicationForm.OTHER -> FcIcons.MoreHoriz
    else -> FcIcons.Medication
}

/**
 * The catalogue suggestions under the name field (D31): one row per medicine,
 * its strength and form, and an OTC tag when no prescription is needed. Tapping
 * a row fills the medicine section; typing on keeps the rows and free text both
 * available.
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
                    .heightIn(min = 48.dp)
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

// --- Looks like -------------------------------------------------------------

@Composable
private fun LookSection(state: MedicationFormUiState, onChange: () -> Unit) {
    SectionTitle(icon = FcIcons.Palette, title = stringResource(R.string.meds_looks_section))

    ReflowRow(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Row(
                modifier = Modifier
                    .clickable(enabled = state.canWrite, onClick = onChange)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PillTile(look = state.pillLook, size = 52.dp, contentDescription = null)
                Column {
                    Text(
                        text = state.pillLook?.let { pillDescription(it) }
                            ?: stringResource(R.string.meds_look_not_set),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.meds_look_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        action = {
            TextButton(
                onClick = onChange,
                enabled = state.canWrite,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(text = stringResource(R.string.meds_look_change))
            }
        },
    )
}

// --- When, each time, how many ----------------------------------------------

private data class TimePreset(
    val labelRes: Int,
    val time: LocalTime,
    val icon: ImageVector,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhenSection(
    state: MedicationFormUiState,
    onPresetToggled: (LocalTime) -> Unit,
    onOtherTime: () -> Unit,
    onCustomTimeRemoved: (LocalTime) -> Unit,
) {
    val presets = listOf(
        TimePreset(R.string.meds_preset_morning, MorningTime, FcIcons.WbSunny),
        TimePreset(R.string.meds_preset_lunch, LunchTime, FcIcons.LunchDining),
        TimePreset(R.string.meds_preset_dinner, DinnerTime, FcIcons.DinnerDining),
        TimePreset(R.string.meds_preset_bedtime, BedtimeTime, FcIcons.Bedtime),
    )

    SectionTitle(icon = FcIcons.Schedule, title = stringResource(R.string.meds_when_section))

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        presets.forEach { preset ->
            FilterChip(
                selected = preset.time in state.presetTimes,
                onClick = { onPresetToggled(preset.time) },
                enabled = state.canWrite,
                label = {
                    Text(text = stringResource(preset.labelRes, formatTime(preset.time)))
                },
                leadingIcon = {
                    Icon(
                        imageVector = preset.icon,
                        contentDescription = null,
                        modifier = Modifier.size(inlineIconSize(MaterialTheme.typography.labelLarge)),
                    )
                },
            )
        }
        AssistChip(
            onClick = onOtherTime,
            enabled = state.canWrite,
            label = { Text(text = stringResource(R.string.meds_time_other)) },
            leadingIcon = { Icon(imageVector = FcIcons.Add, contentDescription = null) },
        )
    }

    if (state.customTimes.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.customTimes.forEach { time ->
                InputChip(
                    selected = true,
                    onClick = { onCustomTimeRemoved(time) },
                    enabled = state.canWrite,
                    label = { Text(text = formatTime(time)) },
                    leadingIcon = {
                        Icon(
                            imageVector = FcIcons.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(inlineIconSize(MaterialTheme.typography.labelLarge)),
                        )
                    },
                    trailingIcon = {
                        Icon(
                            imageVector = FcIcons.Close,
                            contentDescription = stringResource(
                                R.string.meds_remove_custom_time,
                                formatTime(time),
                            ),
                            modifier = Modifier.size(inlineIconSize(MaterialTheme.typography.labelLarge)),
                        )
                    },
                )
            }
        }
    }

    FieldError(
        visible = MedicationField.REGIMEN_TIMES in state.errors,
        message = R.string.meds_error_times,
    )
}

@Composable
private fun EachTimeSection(state: MedicationFormUiState, onStep: (Int) -> Unit) {
    SectionTitle(icon = FcIcons.Pill, title = stringResource(R.string.meds_each_time))
    Stepper(
        valueText = formatQuantity(state.eachTimeQuantity),
        unit = stockUnitName(state.stockUnit),
        enabled = state.canWrite,
        onStep = onStep,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HowManySection(viewModel: MedicationFormViewModel, state: MedicationFormUiState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle(icon = FcIcons.Inventory2, title = stringResource(R.string.meds_how_many))
        Text(
            text = stringResource(R.string.meds_optional),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Stepper(
        valueText = state.stockQuantity,
        placeholder = stringResource(R.string.meds_stock_not_counted),
        unit = if (state.stockQuantity.isBlank()) "" else stockUnitName(state.stockUnit),
        enabled = state.canWrite,
        onStep = viewModel::onStockQuantityStep,
        modifier = Modifier.fillMaxWidth(),
    )

    if (state.packOptions.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.packOptions.forEach { units ->
                AssistChip(
                    onClick = { viewModel.onUsePackQuantity(units) },
                    enabled = state.canWrite,
                    label = { Text(text = stringResource(R.string.meds_stock_pack_chip, units)) },
                    leadingIcon = {
                        Icon(
                            imageVector = FcIcons.Package2,
                            contentDescription = null,
                            modifier = Modifier.size(inlineIconSize(MaterialTheme.typography.labelLarge)),
                        )
                    },
                )
            }
        }
    }

    FieldError(
        visible = MedicationField.STOCK_QUANTITY in state.errors,
        message = R.string.meds_error_stock_quantity,
    )
}

// --- More options -----------------------------------------------------------

@Composable
private fun MoreOptionsSection(viewModel: MedicationFormViewModel, state: MedicationFormUiState) {
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
                    text = stringResource(R.string.meds_more_options),
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

    val enabled = state.canWrite
    val errors = state.errors

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

    OutlinedTextField(
        value = state.instructions,
        onValueChange = viewModel::onInstructionsChanged,
        label = { Text(text = stringResource(R.string.meds_instructions_label)) },
        enabled = enabled,
        isError = MedicationField.MEDICATION_INSTRUCTIONS in errors,
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    FieldError(
        MedicationField.MEDICATION_INSTRUCTIONS in errors,
        R.string.meds_error_instructions,
    )

    if (!state.isEdit) {
        HorizontalDivider()
        ScheduleFields(
            state = state.schedule,
            errors = errors,
            enabled = enabled,
            onScheduleType = viewModel::onScheduleTypeChanged,
            onWeekdayToggled = viewModel::onWeekdayToggled,
            onIntervalDays = viewModel::onIntervalDaysChanged,
            onTimeChanged = { _, _ -> },
            onQuantityStep = { _, _ -> },
            onAddTime = {},
            onRemoveTime = {},
            onStartDateChanged = viewModel::onStartDateChanged,
            onEndDateChanged = viewModel::onEndDateChanged,
            showTimes = false,
        )

        DifferentAmountsPerTime(viewModel, state)
    }

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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = state.requiresPrescription,
                enabled = enabled,
                role = Role.Checkbox,
                onClick = { viewModel.onRequiresPrescriptionChanged(!state.requiresPrescription) },
            )
            .padding(vertical = 2.dp),
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
        R.string.meds_error_prescription_date,
    )

    HorizontalDivider()
    ReminderFields(viewModel, state)

    if (state.isEdit) {
        HorizontalDivider()
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
private fun DifferentAmountsPerTime(
    viewModel: MedicationFormViewModel,
    state: MedicationFormUiState,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.meds_different_amounts),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = state.differentAmountsPerTime,
            onCheckedChange = viewModel::onDifferentAmountsPerTimeChanged,
            enabled = state.canWrite,
        )
    }

    if (!state.differentAmountsPerTime) return

    state.selectedTimes.forEach { time ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatTime(time),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Stepper(
                valueText = formatQuantity(state.quantityFor(time)),
                unit = stockUnitName(state.stockUnit),
                enabled = state.canWrite,
                onStep = { steps -> viewModel.onTimeQuantityStep(time, steps) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The one-line summary under "More options". */
@Composable
private fun moreOptionsSummary(state: MedicationFormUiState): String {
    val reminders = if (state.inheritReminders) {
        state.patientName?.let { stringResource(R.string.meds_summary_reminders_from, it) }
            ?: stringResource(R.string.meds_summary_reminders_inherit)
    } else {
        stringResource(
            R.string.meds_summary_reminders_override,
            stringResource(state.reminderPolicy.labelRes()),
        )
    }
    val threshold = state.lowStockThresholdDays.toIntOrNull() ?: 7

    if (state.isEdit) {
        return stringResource(R.string.meds_more_summary_edit, reminders, threshold)
    }

    val date = runCatching { LocalDate.parse(state.schedule.startDate) }
        .getOrNull()
        ?.let { localizedDate(it) }
        ?: state.schedule.startDate
    val schedule = when (state.schedule.scheduleType) {
        io.github.lgpy.familycare.feature.medications.domain.ScheduleType.DAILY ->
            stringResource(R.string.meds_summary_daily, date)

        io.github.lgpy.familycare.feature.medications.domain.ScheduleType.WEEKDAYS ->
            stringResource(R.string.meds_summary_weekdays, date)

        io.github.lgpy.familycare.feature.medications.domain.ScheduleType.EVERY_N_DAYS ->
            stringResource(
                R.string.meds_summary_every_n,
                state.schedule.intervalDays.trim().toIntOrNull() ?: 0,
                date,
            )
    }
    return stringResource(R.string.meds_more_summary, schedule, reminders, threshold)
}

/** The reminder choice, the same words the patient form uses. */
@Composable
private fun ReminderFields(viewModel: MedicationFormViewModel, state: MedicationFormUiState) {
    val enabled = state.canWrite

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

// --- Small controls ---------------------------------------------------------

@Composable
private fun Stepper(
    valueText: String,
    unit: String,
    enabled: Boolean,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onStep(-1) }, enabled = enabled) {
            Icon(
                imageVector = FcIcons.Remove,
                contentDescription = stringResource(R.string.meds_decrease),
            )
        }
        val empty = valueText.isBlank() && placeholder != null
        Text(
            text = when {
                valueText.isBlank() -> placeholder ?: valueText
                unit.isBlank() -> valueText
                else -> "$valueText $unit"
            },
            style = MaterialTheme.typography.bodyLarge,
            // A muted prompt, so an uncounted value does not look like a control.
            color = if (empty) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        )
        IconButton(onClick = { onStep(1) }, enabled = enabled) {
            Icon(
                imageVector = FcIcons.Add,
                contentDescription = stringResource(R.string.meds_increase),
            )
        }
    }
}

// --- Time picker ------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtherTimeDialog(
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    val pickerState = rememberTimePickerState(initialHour = 12, initialMinute = 0, is24Hour = true)
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 400.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.meds_time_other),
                    style = MaterialTheme.typography.titleLarge,
                )
                TimePicker(state = pickerState)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(R.string.cancel))
                    }
                    TextButton(onClick = { onConfirm(pickerState.hour, pickerState.minute) }) {
                        Text(text = stringResource(android.R.string.ok))
                    }
                }
            }
        }
    }
}
