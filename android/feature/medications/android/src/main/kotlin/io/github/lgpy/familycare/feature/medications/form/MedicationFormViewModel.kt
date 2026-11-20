package io.github.lgpy.familycare.feature.medications.form

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.CircleMember
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.core.domain.medication.PillColor
import io.github.lgpy.familycare.core.domain.medication.PillLook
import io.github.lgpy.familycare.core.domain.medication.PillLookRules
import io.github.lgpy.familycare.core.domain.medication.PillShape
import io.github.lgpy.familycare.feature.medications.data.MedsError
import io.github.lgpy.familycare.feature.medications.data.MedsResult
import io.github.lgpy.familycare.feature.medications.data.MedicationCatalogRepository
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.data.StockRepository
import io.github.lgpy.familycare.feature.medications.domain.Medication
import io.github.lgpy.familycare.feature.medications.domain.MedicationCatalog
import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.domain.MedicationForm
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.MedicationRules
import io.github.lgpy.familycare.feature.medications.domain.MedicationSuggestion
import io.github.lgpy.familycare.feature.medications.domain.MedicationSuggestionRules
import io.github.lgpy.familycare.feature.medications.domain.QuantityRules
import io.github.lgpy.familycare.feature.medications.domain.RegimenId
import io.github.lgpy.familycare.feature.medications.domain.StockMovementKind
import io.github.lgpy.familycare.feature.medications.domain.StockRules
import io.github.lgpy.familycare.feature.medications.ui.DoseTimeEntry
import io.github.lgpy.familycare.feature.medications.ui.MedsUiError
import io.github.lgpy.familycare.feature.medications.ui.MemberOrder
import io.github.lgpy.familycare.feature.medications.ui.ScheduleFormState
import io.github.lgpy.familycare.feature.medications.ui.formatTime
import io.github.lgpy.familycare.feature.medications.ui.parseDoseQuantity
import io.github.lgpy.familycare.feature.medications.ui.parseStockQuantity
import io.github.lgpy.familycare.feature.medications.ui.parseThresholdDays
import io.github.lgpy.familycare.feature.medications.ui.steppedQuantity
import io.github.lgpy.familycare.feature.medications.ui.stockErrors
import io.github.lgpy.familycare.feature.medications.ui.uiError
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The one-screen add-medication form and, in edit mode, the "Edit details"
 * screen (docs/ux/mockups/screens-v2.html, "Add medicine (one screen)").
 *
 * **Medicine:** name, active ingredient, strength, form, stock unit,
 * prescription and instructions. The bundled catalogue supplies suggestions
 * while the name is typed (D31); picking one fills what it knows. The stock unit
 * starts from a sensible default per form ([defaultStockUnit]) and is only
 * overwritten while the user has not typed their own.
 *
 * **Looks like:** the drawn pill look (D33). A form implies a starting look
 * ([defaultPillLook]) until the caregiver picks one in the sheet.
 *
 * **When and how much:** a set of daily presets plus any custom times, each with
 * a quantity. "Each time" is one typed amount applied to every selected time;
 * the "Different amount per time" switch (under More options) turns it into one
 * amount per time, shown in the same section. Times are [LocalTime]s in the
 * state, so no half-typed time is ever reinterpreted; amounts stay text until
 * [save]/[daysOfStock] parse them, so a half-typed `1,` never becomes a stored
 * quantity.
 *
 * **Stock:** an optional opening count ([MedicationRepository.create] writes it
 * with the medication in one transaction, D28) and the low-stock threshold in
 * days.
 *
 * **More options:** schedule type, start and end date, the per-time overrides,
 * the threshold, the prescription flag/date and the reminder override. The
 * domain rules ([Medication.validate], [Regimen.validate]) own the final word and
 * their [MedicationField]s mark the inputs inline.
 *
 * [save] validates the whole screen at once; there are no steps. The time zone is
 * the device's ([ZoneId.systemDefault()]), fixed when the regimen is created, as
 * docs/domain/medications.md requires.
 */
class MedicationFormViewModel(
    private val patientId: String,
    private val medicationId: String?,
    private val medicationRepository: MedicationRepository,
    private val stockRepository: StockRepository,
    private val patientRepository: PatientRepository,
    private val careCircleRepository: CareCircleRepository,
    private val catalogRepository: MedicationCatalogRepository,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val timeZone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(MedicationFormUiState(isEdit = medicationId != null))
    val uiState: StateFlow<MedicationFormUiState> = _uiState.asStateFlow()

    private var existing: Medication? = null

    /** The search in flight; a newer keystroke cancels the older one. */
    private var searchJob: Job? = null

    /** The circle's own medications, the first suggestions (D31). */
    private var circleMedications: List<Medication> = emptyList()

    /**
     * Whether this form is about to add the circle's first active medicine.
     * Fixed at load time, like [circleMedications], so the reminder prompt
     * describes the state the user found, not one a background sync changed
     * while the form was open.
     */
    private var firstMedicine = false

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val role = careCircleRepository.myRole.first()
        val members = careCircleRepository.members.first().sortedWith(MemberOrder)
        val patient = patientRepository.observe(PatientId(patientId)).first()
        circleMedications = medicationRepository.activeMedications.first().map { it.medication }
        firstMedicine = medicationId == null && circleMedications.isEmpty()

        val loaded = medicationId?.let { medicationRepository.observeMedication(MedicationId(it)).first() }
        val medication = loaded?.medication
        existing = medication

        _uiState.update { current ->
            if (medicationId != null && medication == null) {
                current.copy(loaded = true, notFound = true, canWrite = role.canWrite, members = members)
            } else if (medication != null) {
                current.copy(
                    loaded = true,
                    notFound = false,
                    canWrite = role.canWrite,
                    members = members,
                    patientName = patient?.displayName ?: loaded.patient.displayName,
                    name = medication.name,
                    activeIngredient = medication.activeIngredient.orEmpty(),
                    strength = medication.strength.orEmpty(),
                    form = medication.form,
                    stockUnit = medication.stockUnit,
                    stockUnitEdited = true,
                    pillLook = medication.pillLook ?: defaultPillLook(medication.form),
                    pillLookChosen = medication.pillLook != null,
                    requiresPrescription = medication.requiresPrescription,
                    prescriptionValidUntil = medication.prescriptionValidUntil?.toString().orEmpty(),
                    instructions = medication.instructions.orEmpty(),
                    lowStockThresholdDays = medication.lowStockThresholdDays.toString(),
                    schedule = ScheduleFormState.of(loaded.currentRegimen),
                    inheritReminders =
                        medication.reminderPolicy == null && medication.primaryCaregiverId == null,
                    reminderPolicy = medication.reminderPolicy
                        ?: patient?.reminderPolicy
                        ?: ReminderPolicy.PRIMARY_WITH_ESCALATION,
                    primaryCaregiverId = medication.primaryCaregiverId
                        ?: patient?.primaryCaregiverId,
                )
            } else {
                current.copy(
                    loaded = true,
                    canWrite = role.canWrite,
                    members = members,
                    patientName = patient?.displayName,
                    pillLook = defaultPillLook(current.form),
                    reminderPolicy = patient?.reminderPolicy
                        ?: ReminderPolicy.PRIMARY_WITH_ESCALATION,
                    primaryCaregiverId = patient?.primaryCaregiverId,
                )
            }
        }
        // The loaded form is the baseline the discard question compares against;
        // only the caregiver's own edits make it dirty.
        _uiState.update { it.copy(baseline = it.draft()) }
    }

    // --- Medicine -----------------------------------------------------------

    /**
     * Typing the name searches the bundled catalogue (D31). The filled fields
     * are left alone, so editing a picked name afterwards keeps its ingredient,
     * strength and form.
     */
    fun onNameChanged(value: String) {
        updateDetails { it.copy(name = value) }
        requestSuggestions(value)
    }

    /** Picks a suggestion: fills what it knows, leaves the rest as it is. */
    fun onSuggestionPicked(suggestion: MedicationSuggestion) {
        _uiState.update { state ->
            state.copy(
                name = suggestion.name,
                activeIngredient = suggestion.dci.ifBlank { state.activeIngredient },
                strength = suggestion.strength.ifBlank { state.strength },
                form = suggestion.form,
                stockUnit = if (state.stockUnitEdited) state.stockUnit else defaultStockUnit(suggestion.form),
                pillLook = if (state.pillLookChosen) state.pillLook else defaultPillLook(suggestion.form),
                requiresPrescription = !suggestion.otc,
                packUnits = suggestion.packOptions.firstOrNull(),
                packOptions = suggestion.packOptions,
                suggestions = emptyList(),
                errors = emptySet(),
            )
        }
    }

    fun onActiveIngredientChanged(value: String) =
        updateDetails { it.copy(activeIngredient = value) }

    fun onStrengthChanged(value: String) = updateDetails { it.copy(strength = value) }

    /** Changing the form updates the stock unit and the starting look while they are still defaults. */
    fun onFormChanged(form: MedicationForm) = updateDetails { state ->
        state.copy(
            form = form,
            stockUnit = if (state.stockUnitEdited) state.stockUnit else defaultStockUnit(form),
            pillLook = if (state.pillLookChosen) state.pillLook else defaultPillLook(form),
        )
    }

    fun onStockUnitChanged(value: String) =
        updateDetails { it.copy(stockUnit = value, stockUnitEdited = true) }

    fun onRequiresPrescriptionChanged(value: Boolean) =
        updateDetails { it.copy(requiresPrescription = value) }

    fun onPrescriptionValidUntilChanged(value: String) =
        updateDetails { it.copy(prescriptionValidUntil = value) }

    fun onInstructionsChanged(value: String) = updateDetails { it.copy(instructions = value) }

    /** The pill-look sheet's "Done": stores the chosen look, normalised to the domain rules. */
    fun onPillLookChanged(look: PillLook) =
        _uiState.update {
            it.copy(
                pillLook = PillLookRules.normalize(look),
                pillLookChosen = true,
                errors = it.errors - MedicationField.MEDICATION_PILL_LOOK,
            )
        }

    // --- When and how much --------------------------------------------------

    fun onScheduleTypeChanged(type: io.github.lgpy.familycare.feature.medications.domain.ScheduleType) =
        updateSchedule { it.copy(scheduleType = type) }

    fun onWeekdayToggled(day: DayOfWeek) = updateSchedule { state ->
        state.copy(
            weekdays = if (day in state.weekdays) state.weekdays - day else state.weekdays + day,
        )
    }

    fun onIntervalDaysChanged(value: String) = updateSchedule { it.copy(intervalDays = value) }

    /** Adds or removes one of the fixed daily presets. */
    fun onPresetTimeToggled(time: LocalTime) = _uiState.update { state ->
        val presets = if (time in state.presetTimes) {
            state.presetTimes - time
        } else {
            state.presetTimes + time
        }
        state.copy(
            presetTimes = presets,
            errors = state.errors - MedicationField.REGIMEN_TIMES,
        )
    }

    /** "Other time": adds a custom time chip, unless it is already selected. */
    fun onCustomTimeAdded(hour: Int, minute: Int) {
        val time = LocalTime.of(hour, minute)
        _uiState.update { state ->
            when {
                // Picking a preset's own time selects that chip instead of
                // showing the same hour twice.
                time in PresetTimes -> state.copy(
                    presetTimes = state.presetTimes + time,
                    errors = state.errors - MedicationField.REGIMEN_TIMES,
                )

                time in state.selectedTimes -> state
                else -> state.copy(
                    customTimes = state.customTimes + time,
                    timeQuantityTexts = state.timeQuantityTexts + (time to state.eachTimeText),
                    errors = state.errors - MedicationField.REGIMEN_TIMES,
                )
            }
        }
    }

    /** Removes a custom time chip. A preset time stays a preset. */
    fun onCustomTimeRemoved(time: LocalTime) = _uiState.update { state ->
        state.copy(
            customTimes = state.customTimes - time,
            timeQuantityTexts = state.timeQuantityTexts - time,
        )
    }

    /** The "Each time" field, as typed. */
    fun onTimeQuantityChanged(value: String) = _uiState.update { state ->
        state.copy(eachTimeText = value).clearingQuantityError()
    }

    /** The "Each time" buttons: one step of the shared amount, applied to every selected time. */
    fun onTimeQuantityStep(steps: Int) = _uiState.update { state ->
        state.copy(
            eachTimeText = QuantityRules.format(steppedQuantity(state.eachTimeQuantity, steps)),
        ).clearingQuantityError()
    }

    /** One time's own amount, as typed, while "Different amount per time" is on. */
    fun onTimeQuantityChanged(time: LocalTime, value: String) = _uiState.update { state ->
        state.copy(
            differentAmountsPerTime = true,
            timeQuantityTexts = state.timeQuantityTexts + (time to value),
        ).clearingQuantityError()
    }

    /** One time's buttons, while "Different amount per time" is on. */
    fun onTimeQuantityStep(time: LocalTime, steps: Int) = _uiState.update { state ->
        val next = QuantityRules.format(steppedQuantity(state.quantityFor(time), steps))
        state.copy(
            differentAmountsPerTime = true,
            timeQuantityTexts = state.timeQuantityTexts + (time to next),
        ).clearingQuantityError()
    }

    fun onDifferentAmountsPerTimeChanged(enabled: Boolean) = _uiState.update { state ->
        state.copy(
            differentAmountsPerTime = enabled,
            timeQuantityTexts = if (enabled) {
                state.selectedTimes.associateWith { state.timeQuantityTexts[it] ?: state.eachTimeText }
            } else {
                emptyMap()
            },
        )
    }

    fun onStartDateChanged(value: String) = updateSchedule { it.copy(startDate = value) }

    fun onEndDateChanged(value: String) = updateSchedule { it.copy(endDate = value) }

    // --- Stock --------------------------------------------------------------

    /** The opening count ("How many do you have?"), as typed. */
    fun onStockQuantityChanged(value: String) =
        _uiState.update {
            it.copy(stockQuantity = value, errors = it.errors - MedicationField.STOCK_QUANTITY)
        }

    /**
     * The opening count's − and + buttons.
     *
     * The count is typed first and nudged here; a blank field starts at 1 on the
     * first `+` and at 0 on the first `−`, so an empty field never jumps to a
     * number the caregiver did not choose. A whole pack is one tap away on its
     * own chip ([onUsePackQuantity]).
     */
    fun onStockQuantityStep(steps: Int) = _uiState.update { state ->
        val typed = parseStockQuantity(state.stockQuantity) ?: BigDecimal.ZERO
        val next = typed.add(BigDecimal(steps))
            .coerceAtLeast(BigDecimal.ZERO)
            .coerceAtMost(MAX_STOCK_STEP)
        state.copy(
            stockQuantity = QuantityRules.format(next),
            errors = state.errors - MedicationField.STOCK_QUANTITY,
        )
    }

    /**
     * The one-tap "1 pack = N" chip of the catalogue (D31): fills the opening
     * count with the pack's unit count when the box says how many it holds.
     */
    fun onUsePackQuantity(units: Int) =
        _uiState.update {
            it.copy(stockQuantity = units.toString(), errors = it.errors - MedicationField.STOCK_QUANTITY)
        }

    fun onLowStockThresholdChanged(value: String) =
        _uiState.update {
            it.copy(
                lowStockThresholdDays = value,
                errors = it.errors - MedicationField.MEDICATION_LOW_STOCK_THRESHOLD,
            )
        }

    /** Edit mode's optional count, written after a successful update. */
    fun onRecountQuantityChanged(value: String) =
        _uiState.update { it.copy(recountQuantity = value, errors = it.errors - MedicationField.STOCK_QUANTITY) }

    // --- Reminders ----------------------------------------------------------

    fun onInheritRemindersChanged(inherit: Boolean) =
        _uiState.update { it.copy(inheritReminders = inherit, errors = it.errors - MedicationField.MEDICATION_REMINDER_POLICY) }

    fun onReminderPolicyChanged(policy: ReminderPolicy) =
        _uiState.update {
            it.copy(
                reminderPolicy = policy,
                errors = it.errors - MedicationField.MEDICATION_REMINDER_POLICY,
            )
        }

    fun onPrimaryCaregiverChanged(userId: String) =
        _uiState.update {
            it.copy(primaryCaregiverId = userId, errors = it.errors - MedicationField.MEDICATION_PRIMARY_CAREGIVER)
        }

    fun clearError() = _uiState.update { it.copy(error = null) }

    /**
     * Validates everything and writes. Add: [MedicationRepository.create] with
     * the first regimen and the optional opening count in one transaction
     * (D28); edit: [MedicationRepository.update], which never touches the
     * regimen, followed by a count when the form's optional recount is filled.
     */
    fun save() {
        val state = _uiState.value
        if (!state.loaded || !state.canWrite || state.saving || state.notFound) return

        val id = existing?.id ?: MedicationId(newId())
        val draft = state.toMedication(id, PatientId(patientId), existing)
        val fields = draft.second.toMutableSet()
        fields += thresholdValidation(state.lowStockThresholdDays)

        if (!state.isEdit) {
            val parsed = state.scheduleForRegimen().parse(
                id = RegimenId(newId()),
                medicationId = id,
                timeZone = timeZone,
            )
            fields += parsed.fields
            // An amount that is not a valid dose marks the same field the
            // domain would: the When section owns the times and their amounts.
            if (state.invalidQuantityTimes().isNotEmpty()) {
                fields += MedicationField.REGIMEN_TIMES
            }
            // The opening count is optional: a blank field writes no count row.
            val quantityText = state.stockQuantity.trim()
            val quantity = quantityText.takeIf { it.isNotEmpty() }?.let(::parseStockQuantity)
            if (quantityText.isNotEmpty()) {
                fields += stockErrors(quantity, StockMovementKind.COUNT)
            }
            val regimen = parsed.regimen
            val medication = draft.first
            if (fields.isNotEmpty() || regimen == null || medication == null) {
                _uiState.update { it.copy(errors = fields) }
                return
            }
            submit(firstMedicine = firstMedicine) {
                medicationRepository.create(medication, regimen, openingQuantity = quantity)
            }
            return
        }

        val medication = draft.first
        val recountText = state.recountQuantity.trim()
        val recount = recountText.takeIf { it.isNotEmpty() }?.let(::parseStockQuantity)
        if (recountText.isNotEmpty()) {
            fields += stockErrors(recount, StockMovementKind.COUNT)
        }
        if (fields.isNotEmpty() || medication == null) {
            _uiState.update { it.copy(errors = fields) }
            return
        }
        submit {
            when (val updated = medicationRepository.update(medication)) {
                is MedsResult.Failure -> updated
                is MedsResult.Success -> {
                    // D28: a recount after an edit is a second write; the edit's
                    // own columns are already stored, so a refusal here only
                    // leaves the count out.
                    if (recount == null) updated else stockRepository.recordCount(medication.id, recount)
                }
            }
        }
    }

    /**
     * Runs the write and reports success. [firstMedicine] marks the one case
     * that also opens the reminder-setup prompt: the circle had no active
     * medicine before this save, so reminders have just started mattering.
     */
    private fun submit(
        firstMedicine: Boolean = false,
        call: suspend () -> MedsResult<*>,
    ) {
        _uiState.update { it.copy(errors = emptySet(), saving = true, error = null) }
        viewModelScope.launch {
            when (val result = call()) {
                is MedsResult.Success ->
                    _uiState.update {
                        it.copy(saving = false, saved = true, firstMedicineSaved = firstMedicine)
                    }

                is MedsResult.Failure ->
                    _uiState.update {
                        it.copy(
                            saving = false,
                            errors = (result.error as? MedsError.Invalid)?.fields.orEmpty(),
                            error = result.uiError(),
                        )
                    }
            }
        }
    }

    private fun updateDetails(block: (MedicationFormUiState) -> MedicationFormUiState) =
        _uiState.update { state -> block(state).copy(errors = emptySet()) }

    /**
     * Looks up suggestions for [rawQuery] off the main thread: the circle's own
     * medications first (a family re-adds what it already has), then the
     * bundled catalogue. The result is applied only while the field still holds
     * the same text, so a fast typist never sees stale suggestions.
     */
    private fun requestSuggestions(rawQuery: String) {
        searchJob?.cancel()
        val query = rawQuery.trim()
        if (query.length < MedicationCatalog.MIN_QUERY_LENGTH) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            val catalogue = runCatching {
                catalogRepository.search(query, SUGGESTION_LIMIT)
            }.getOrDefault(emptyList())
            val circle = MedicationSuggestionRules.circleSuggestions(query, circleMedications, SUGGESTION_LIMIT)
            val merged = MedicationSuggestionRules.merge(circle, catalogue, SUGGESTION_LIMIT)
            _uiState.update { state ->
                if (state.name.trim() == query) state.copy(suggestions = merged) else state
            }
        }
    }

    private fun updateSchedule(block: (ScheduleFormState) -> ScheduleFormState) =
        _uiState.update { it.copy(schedule = block(it.schedule), errors = emptySet()) }

    /** The threshold days must be a whole number within the medication's range. */
    private fun thresholdValidation(text: String): Set<MedicationField> {
        val days = parseThresholdDays(text)
        val inRange = days != null &&
            days in MedicationRules.MIN_LOW_STOCK_THRESHOLD_DAYS..MedicationRules.MAX_LOW_STOCK_THRESHOLD_DAYS
        return if (inRange) emptySet() else setOf(MedicationField.MEDICATION_LOW_STOCK_THRESHOLD)
    }
}

/** The times the "When" chips offer, in the order the mockup shows them. */
val MorningTime: LocalTime = LocalTime.of(8, 0)
val LunchTime: LocalTime = LocalTime.of(13, 0)
val DinnerTime: LocalTime = LocalTime.of(20, 0)
val BedtimeTime: LocalTime = LocalTime.of(22, 0)

/** The four fixed preset times, so a custom time can select its chip instead. */
val PresetTimes: Set<LocalTime> = setOf(MorningTime, LunchTime, DinnerTime, BedtimeTime)

/** How many catalogue suggestions the name field shows at once. */
private const val SUGGESTION_LIMIT = 12

/** The upper bound the opening count's buttons can reach (the domain's stock maximum). */
private val MAX_STOCK_STEP: BigDecimal = BigDecimal("100000")

/**
 * The one screen's state: what the medicine is, how it looks, when it is taken,
 * how much is in the box, what is wrong and whether it is saving.
 */
data class MedicationFormUiState(
    val loaded: Boolean = false,
    val isEdit: Boolean = false,
    val notFound: Boolean = false,
    val canWrite: Boolean = false,
    val patientName: String? = null,
    val members: List<CircleMember> = emptyList(),
    val name: String = "",
    /** Catalogue suggestions for the name field (D31); empty below two characters. */
    val suggestions: List<MedicationSuggestion> = emptyList(),
    /** The pack sizes of the picked suggestion, for the stock chips. */
    val packOptions: List<Int> = emptyList(),
    /** The pack size the form offers first ("1 pack = N"), when known. */
    val packUnits: Int? = null,
    val activeIngredient: String = "",
    val strength: String = "",
    val form: MedicationForm = MedicationForm.TABLET,
    val stockUnit: String = defaultStockUnit(MedicationForm.TABLET),
    val stockUnitEdited: Boolean = false,
    /** The drawn pill look (D33); a form supplies a starting look. */
    val pillLook: PillLook? = defaultPillLook(MedicationForm.TABLET),
    /** Whether the caregiver picked the look, so a form change must not replace it. */
    val pillLookChosen: Boolean = false,
    /** The opening count, as typed: "how many <stock unit> are there now?". Blank means none. */
    val stockQuantity: String = "",
    /** The days of stock that count as low, as typed. */
    val lowStockThresholdDays: String = MedicationRules.DEFAULT_LOW_STOCK_THRESHOLD_DAYS.toString(),
    /** Edit mode's optional count, written after a successful update. */
    val recountQuantity: String = "",
    val requiresPrescription: Boolean = false,
    val prescriptionValidUntil: String = "",
    val instructions: String = "",
    val schedule: ScheduleFormState = ScheduleFormState(),
    /** The fixed daily presets currently selected. */
    val presetTimes: Set<LocalTime> = setOf(MorningTime),
    /** The extra times added through "Other time". */
    val customTimes: List<LocalTime> = emptyList(),
    /** The amount every selected time gets ("Each time"), as typed. */
    val eachTimeText: String = "1",
    /** Whether "More options" gives individual times their own amount. */
    val differentAmountsPerTime: Boolean = false,
    /** The per-time amounts, as typed, used only while [differentAmountsPerTime] is on. */
    val timeQuantityTexts: Map<LocalTime, String> = emptyMap(),
    val inheritReminders: Boolean = true,
    val reminderPolicy: ReminderPolicy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
    val primaryCaregiverId: String? = null,
    val errors: Set<MedicationField> = emptySet(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    /**
     * The save just added the circle's first medicine, so the screen offers the
     * notification and battery-exemption prompts (finding 4 of the first-use
     * audit) before leaving.
     */
    val firstMedicineSaved: Boolean = false,
    val error: MedsUiError? = null,
    /** The loaded values; null until the form has loaded. */
    val baseline: MedicationFormDraft? = null,
) {

    /** Whether the caregiver changed anything that closing would lose. */
    val isDirty: Boolean get() = baseline != null && baseline != draft()

    /** Presets plus custom times, in clock order, as the regimen will store them. */
    val selectedTimes: List<LocalTime>
        get() = (presetTimes + customTimes).sorted()

    /** The shared amount, or 1 while its text is not a number (the save reports that). */
    val eachTimeQuantity: BigDecimal
        get() = parseDoseQuantity(eachTimeText) ?: BigDecimal.ONE

    /** The amount [time] shows: its own text, or the shared "Each time" text. */
    fun quantityTextFor(time: LocalTime): String =
        if (differentAmountsPerTime) timeQuantityTexts[time] ?: eachTimeText else eachTimeText

    /** The amount [time] gets: its own value, or the shared "Each time" value. */
    fun quantityFor(time: LocalTime): BigDecimal =
        parseDoseQuantity(quantityTextFor(time)) ?: BigDecimal.ONE

    /**
     * The selected times whose typed amount is not a valid dose, so the form can
     * refuse the save and mark the same field the domain would. Empty means
     * every amount can be stored.
     */
    fun invalidQuantityTimes(): Set<LocalTime> =
        selectedTimes.filterNot { parseDoseQuantity(quantityTextFor(it)) != null }.toSet()

    /** Drops the When section's error once every amount parses; keeps it otherwise. */
    internal fun clearingQuantityError(): MedicationFormUiState =
        if (MedicationField.REGIMEN_TIMES in errors &&
            selectedTimes.isNotEmpty() &&
            invalidQuantityTimes().isEmpty()
        ) {
            copy(errors = errors - MedicationField.REGIMEN_TIMES)
        } else {
            this
        }

    /** The schedule editor with the chip-selected times, ready for [ScheduleFormState.parse]. */
    fun scheduleForRegimen(): ScheduleFormState = schedule.copy(
        times = selectedTimes.map { time ->
            DoseTimeEntry(time = formatTime(time), quantity = quantityFor(time))
        },
    )
}

/**
 * The values the caregiver can change, as the discard question compares them.
 *
 * Volatile state (suggestions, errors, saving, the loaded patient) is left out:
 * typing a name may replace a suggestion list without the user having edited
 * anything else.
 */
data class MedicationFormDraft(
    val name: String,
    val activeIngredient: String,
    val strength: String,
    val form: MedicationForm,
    val stockUnit: String,
    val pillLook: PillLook?,
    val requiresPrescription: Boolean,
    val prescriptionValidUntil: String,
    val instructions: String,
    val lowStockThresholdDays: String,
    val stockQuantity: String,
    val recountQuantity: String,
    val schedule: ScheduleFormState,
    val presetTimes: Set<LocalTime>,
    val customTimes: List<LocalTime>,
    val eachTimeText: String,
    val differentAmountsPerTime: Boolean,
    val timeQuantityTexts: Map<LocalTime, String>,
    val inheritReminders: Boolean,
    val reminderPolicy: ReminderPolicy,
    val primaryCaregiverId: String?,
)

/** The current editable values, as the baseline is stored after loading. */
internal fun MedicationFormUiState.draft(): MedicationFormDraft = MedicationFormDraft(
    name = name,
    activeIngredient = activeIngredient,
    strength = strength,
    form = form,
    stockUnit = stockUnit,
    pillLook = pillLook,
    requiresPrescription = requiresPrescription,
    prescriptionValidUntil = prescriptionValidUntil,
    instructions = instructions,
    lowStockThresholdDays = lowStockThresholdDays,
    stockQuantity = stockQuantity,
    recountQuantity = recountQuantity,
    schedule = schedule,
    presetTimes = presetTimes,
    customTimes = customTimes,
    eachTimeText = eachTimeText,
    differentAmountsPerTime = differentAmountsPerTime,
    timeQuantityTexts = timeQuantityTexts,
    inheritReminders = inheritReminders,
    reminderPolicy = reminderPolicy,
    primaryCaregiverId = primaryCaregiverId,
)

/**
 * How many days the typed opening count lasts at the amounts and schedule the
 * form currently has, or null when there is nothing to estimate from (no
 * count, no parsable schedule or no consumption). It is the same rule the stock
 * card uses ([StockRules.daysRemaining]), so the form cannot promise something
 * the detail screen contradicts.
 *
 * The regimen is only parsed, never stored, so it gets placeholder ids.
 */
internal fun MedicationFormUiState.daysOfStock(timeZone: ZoneId = ZoneId.systemDefault()): Int? {
    val stock = parseStockQuantity(stockQuantity) ?: return null
    val regimen = scheduleForRegimen()
        .parse(RegimenId("preview"), MedicationId("preview"), timeZone)
        .regimen ?: return null
    return StockRules.daysRemaining(stock, regimen)
}

/**
 * The look a form starts from (docs/ux/mockups/screens-v2.html): a tablet is a
 * white round tablet, a capsule a white two-tone capsule, drops a bottle; every
 * other form has no drawn pill until the caregiver picks one.
 */
internal fun defaultPillLook(form: MedicationForm): PillLook? = when (form) {
    MedicationForm.TABLET -> PillLook(shape = PillShape.ROUND, color = PillColor.WHITE)
    MedicationForm.CAPSULE -> PillLook(
        shape = PillShape.CAPSULE,
        color = PillColor.WHITE,
        color2 = PillColor.WHITE,
    )

    MedicationForm.DROPS -> PillLook(shape = PillShape.DROPS, color = PillColor.WHITE)
    else -> null
}

/**
 * The medication this form describes, and the fields the domain rejected.
 *
 * [existing] keeps the columns the form does not edit: id, patient, the stopped
 * timestamp and (when the threshold text does not parse) the stored threshold.
 */
private fun MedicationFormUiState.toMedication(
    id: MedicationId,
    patientId: PatientId,
    existing: Medication?,
): Pair<Medication?, Set<MedicationField>> {
    val fields = mutableSetOf<MedicationField>()

    val validUntil = prescriptionValidUntil.trim().takeIf { it.isNotEmpty() }?.let { text ->
        runCatching { LocalDate.parse(text) }.getOrNull() ?: run {
            fields += MedicationField.MEDICATION_PRESCRIPTION_VALID_UNTIL
            null
        }
    }

    val medication = Medication(
        id = id,
        patientId = existing?.patientId ?: patientId,
        name = name,
        activeIngredient = activeIngredient,
        strength = strength,
        form = form,
        stockUnit = stockUnit,
        instructions = instructions,
        requiresPrescription = requiresPrescription,
        prescriptionValidUntil = validUntil,
        pillLook = pillLook,
        lowStockThresholdDays = parseThresholdDays(lowStockThresholdDays)
            ?: existing?.lowStockThresholdDays
            ?: MedicationRules.DEFAULT_LOW_STOCK_THRESHOLD_DAYS,
        reminderPolicy = if (inheritReminders) null else reminderPolicy,
        primaryCaregiverId = if (inheritReminders) null else primaryCaregiverId,
        stoppedAt = existing?.stoppedAt,
    ).normalized()

    fields += medication.validate().errors
    return medication to fields
}

/**
 * The stock unit a new medication starts with, per form: what stock is counted
 * in. These are stored data tokens (the column is free text), not screen text,
 * so they are not translated; a caregiver may type their own.
 */
fun defaultStockUnit(form: MedicationForm): String = when (form) {
    MedicationForm.TABLET -> "tablet"
    MedicationForm.CAPSULE -> "capsule"
    MedicationForm.DROPS -> "drop"
    MedicationForm.LIQUID -> "ml"
    MedicationForm.INJECTION -> "dose"
    MedicationForm.INHALER -> "dose"
    MedicationForm.PATCH -> "patch"
    MedicationForm.CREAM -> "g"
    MedicationForm.OTHER -> "unit"
}
