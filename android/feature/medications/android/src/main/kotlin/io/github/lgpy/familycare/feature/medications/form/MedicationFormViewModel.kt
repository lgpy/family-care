package io.github.lgpy.familycare.feature.medications.form

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.CircleMember
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.feature.medications.data.MedsError
import io.github.lgpy.familycare.feature.medications.data.MedsResult
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.data.MedicationWithRegimens
import io.github.lgpy.familycare.feature.medications.data.StockRepository
import io.github.lgpy.familycare.feature.medications.domain.Medication
import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.domain.MedicationForm
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.MedicationRules
import io.github.lgpy.familycare.feature.medications.domain.RegimenId
import io.github.lgpy.familycare.feature.medications.domain.StockMovementKind
import io.github.lgpy.familycare.feature.medications.ui.DoseTimeEntry
import io.github.lgpy.familycare.feature.medications.ui.MedsUiError
import io.github.lgpy.familycare.feature.medications.ui.MemberOrder
import io.github.lgpy.familycare.feature.medications.ui.ScheduleFormState
import io.github.lgpy.familycare.feature.medications.ui.parseStockQuantity
import io.github.lgpy.familycare.feature.medications.ui.parseThresholdDays
import io.github.lgpy.familycare.feature.medications.ui.parseTime
import io.github.lgpy.familycare.feature.medications.ui.steppedQuantity
import io.github.lgpy.familycare.feature.medications.ui.stockErrors
import io.github.lgpy.familycare.feature.medications.ui.uiError
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The add-medication flow (docs/ux/screens.md §4, four steps in M5: what, when,
 * stock, who) and, in edit mode, the "Edit details" screen.
 *
 * **Step 1 — What:** name, active ingredient, strength, form, stock unit,
 * prescription, instructions. The stock unit starts from a sensible default per
 * form ([defaultStockUnit]) and is only overwritten while the user has not typed
 * their own.
 *
 * **Step 2 — When and how much:** schedule type, times with a quantity each
 * (0.25 steps), start and end date. Times and dates stay text until [save] so a
 * half-typed value is never silently reinterpreted; the domain rules
 * ([Medication.validate], [Regimen.validate]) own the final word and their
 * [MedicationField]s mark the inputs inline.
 *
 * **Step 3 — Stock:** how many there are now (the opening count, written with
 * the medication in one transaction by [MedicationRepository.create]) and the
 * low-stock threshold in days. The quantity is validated by
 * [io.github.lgpy.familycare.feature.medications.domain.StockRules.validate], the
 * same rule the ledger applies (D28).
 *
 * **Step 4 — Who is reminded:** inherit the patient (both overrides null) or
 * override the policy and primary caregiver. Reminders are M4; the screen says
 * so, and nothing is scheduled here.
 *
 * The time zone is the device's ([ZoneId.systemDefault()]), fixed when the
 * regimen is created, as docs/domain/medications.md requires.
 */
class MedicationFormViewModel(
    private val patientId: String,
    private val medicationId: String?,
    private val medicationRepository: MedicationRepository,
    private val stockRepository: StockRepository,
    private val patientRepository: PatientRepository,
    private val careCircleRepository: CareCircleRepository,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val timeZone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(MedicationFormUiState(isEdit = medicationId != null))
    val uiState: StateFlow<MedicationFormUiState> = _uiState.asStateFlow()

    private var existing: Medication? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val role = careCircleRepository.myRole.first()
        val members = careCircleRepository.members.first().sortedWith(MemberOrder)
        val patient = patientRepository.observe(PatientId(patientId)).first()

        val loaded = medicationId?.let { medicationRepository.observeMedication(MedicationId(it)).first() }
        val medication = loaded?.medication
        existing = medication

        _uiState.update { current ->
            if (medicationId != null && medication == null) {
                current.copy(loaded = true, notFound = true, canWrite = role.canWrite, members = members)
            } else if (medication != null && loaded != null) {
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
                    reminderPolicy = patient?.reminderPolicy
                        ?: ReminderPolicy.PRIMARY_WITH_ESCALATION,
                    primaryCaregiverId = patient?.primaryCaregiverId,
                )
            }
        }
    }

    // --- Step 1 --------------------------------------------------------------

    fun onNameChanged(value: String) = updateDetails { it.copy(name = value) }

    fun onActiveIngredientChanged(value: String) =
        updateDetails { it.copy(activeIngredient = value) }

    fun onStrengthChanged(value: String) = updateDetails { it.copy(strength = value) }

    /** Changing the form updates the stock unit only while it is still a default. */
    fun onFormChanged(form: MedicationForm) = updateDetails { state ->
        state.copy(
            form = form,
            stockUnit = if (state.stockUnitEdited) state.stockUnit else defaultStockUnit(form),
        )
    }

    fun onStockUnitChanged(value: String) =
        updateDetails { it.copy(stockUnit = value, stockUnitEdited = true) }

    fun onRequiresPrescriptionChanged(value: Boolean) =
        updateDetails { it.copy(requiresPrescription = value) }

    fun onPrescriptionValidUntilChanged(value: String) =
        updateDetails { it.copy(prescriptionValidUntil = value) }

    fun onInstructionsChanged(value: String) = updateDetails { it.copy(instructions = value) }

    // --- Step 2 --------------------------------------------------------------

    fun onScheduleTypeChanged(type: io.github.lgpy.familycare.feature.medications.domain.ScheduleType) =
        updateSchedule { it.copy(scheduleType = type) }

    fun onWeekdayToggled(day: DayOfWeek) = updateSchedule { state ->
        state.copy(
            weekdays = if (day in state.weekdays) state.weekdays - day else state.weekdays + day,
        )
    }

    fun onIntervalDaysChanged(value: String) = updateSchedule { it.copy(intervalDays = value) }

    fun onTimeChanged(index: Int, value: String) = updateSchedule { state ->
        state.copy(times = state.times.mapIndexed { i, entry ->
            if (i == index) entry.copy(time = value) else entry
        })
    }

    fun onQuantityStep(index: Int, steps: Int) = updateSchedule { state ->
        state.copy(times = state.times.mapIndexed { i, entry ->
            if (i == index) entry.copy(quantity = steppedQuantity(entry.quantity, steps)) else entry
        })
    }

    fun onAddTime() = updateSchedule { state ->
        val last = state.times.lastOrNull()?.time ?: "08:00"
        val next = parseTime(last)?.plusHours(8) ?: java.time.LocalTime.of(8, 0)
        state.copy(
            times = state.times + DoseTimeEntry(
                time = next.hour.toString().padStart(2, '0') +
                    ":" +
                    next.minute.toString().padStart(2, '0'),
                quantity = BigDecimal.ONE,
            ),
        )
    }

    fun onRemoveTime(index: Int) = updateSchedule { state ->
        if (state.times.size <= 1) state else state.copy(times = state.times.filterIndexed { i, _ -> i != index })
    }

    fun onStartDateChanged(value: String) = updateSchedule { it.copy(startDate = value) }

    fun onEndDateChanged(value: String) = updateSchedule { it.copy(endDate = value) }

    // --- Step 3: stock -------------------------------------------------------

    /** "How many <stock unit> are there now?" — the opening count. */
    fun onStockQuantityChanged(value: String) =
        _uiState.update { it.copy(stockQuantity = value, errors = it.errors - MedicationField.STOCK_QUANTITY) }

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

    // --- Step 4 --------------------------------------------------------------

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

    /** Step 1 → 2 → 3 → 4, validating the step being left. */
    fun next() {
        val state = _uiState.value
        if (state.isEdit) return
        val fields = when (state.step) {
            0 -> detailsValidation(state) - STOCK_STEP_FIELDS
            1 -> state.schedule.parse(RegimenId(newId()), MedicationId(newId())).fields
            else -> stockStepValidation(state)
        }
        if (fields.isNotEmpty()) {
            _uiState.update { it.copy(errors = fields) }
            return
        }
        _uiState.update { it.copy(errors = emptySet(), step = (it.step + 1).coerceAtMost(LAST_STEP)) }
    }

    fun previous() = _uiState.update {
        it.copy(step = (it.step - 1).coerceAtLeast(0), errors = emptySet())
    }

    /**
     * Validates everything and writes. Add: [MedicationRepository.create] with
     * the first regimen and the opening count in one transaction (D28); edit:
     * [MedicationRepository.update], which never touches the regimen, followed by
     * a count when the form's optional recount is filled.
     */
    fun save() {
        val state = _uiState.value
        if (!state.loaded || !state.canWrite || state.saving || state.notFound) return

        val id = existing?.id ?: MedicationId(newId())
        val draft = state.toMedication(id, PatientId(patientId), existing)
        val fields = draft.second.toMutableSet()
        fields += thresholdValidation(state.lowStockThresholdDays)

        if (!state.isEdit) {
            val parsed = state.schedule.parse(
                id = RegimenId(newId()),
                medicationId = id,
                timeZone = timeZone,
            )
            fields += parsed.fields
            val quantity = parseStockQuantity(state.stockQuantity)
            fields += stockErrors(quantity, StockMovementKind.COUNT)
            val regimen = parsed.regimen
            val medication = draft.first
            if (fields.isNotEmpty() || regimen == null || medication == null) {
                _uiState.update { it.copy(errors = fields) }
                return
            }
            submit { medicationRepository.create(medication, regimen, openingQuantity = quantity) }
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

    private fun submit(call: suspend () -> MedsResult<*>) {
        _uiState.update { it.copy(errors = emptySet(), saving = true, error = null) }
        viewModelScope.launch {
            when (val result = call()) {
                is MedsResult.Success ->
                    _uiState.update { it.copy(saving = false, saved = true) }

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

    private fun updateSchedule(block: (ScheduleFormState) -> ScheduleFormState) =
        _uiState.update { it.copy(schedule = block(it.schedule), errors = emptySet()) }

    /** The stock step's own inputs: the opening count and the threshold. */
    private fun stockStepValidation(state: MedicationFormUiState): Set<MedicationField> =
        stockErrors(parseStockQuantity(state.stockQuantity), StockMovementKind.COUNT) +
            thresholdValidation(state.lowStockThresholdDays)

    /** The threshold days must be a whole number within the medication's range. */
    private fun thresholdValidation(text: String): Set<MedicationField> {
        val days = parseThresholdDays(text)
        val inRange = days != null &&
            days in MedicationRules.MIN_LOW_STOCK_THRESHOLD_DAYS..MedicationRules.MAX_LOW_STOCK_THRESHOLD_DAYS
        return if (inRange) emptySet() else setOf(MedicationField.MEDICATION_LOW_STOCK_THRESHOLD)
    }

    /** The "What" step's fields, without the stock step's own inputs. */
    private fun detailsValidation(state: MedicationFormUiState): Set<MedicationField> {
        val (_, fields) = state.toMedication(
            MedicationId(existing?.id?.value ?: "draft"),
            PatientId(patientId),
            existing,
        )
        return fields
    }
}

/** The fields the stock step owns, so the "What" step does not flag them. */
private val STOCK_STEP_FIELDS: Set<MedicationField> = setOf(
    MedicationField.STOCK_QUANTITY,
    MedicationField.MEDICATION_LOW_STOCK_THRESHOLD,
)

/** What the four steps hold, what is wrong, and whether it is saving. */
data class MedicationFormUiState(
    val loaded: Boolean = false,
    val isEdit: Boolean = false,
    val notFound: Boolean = false,
    val canWrite: Boolean = false,
    val step: Int = 0,
    val patientName: String? = null,
    val members: List<CircleMember> = emptyList(),
    val name: String = "",
    val activeIngredient: String = "",
    val strength: String = "",
    val form: MedicationForm = MedicationForm.TABLET,
    val stockUnit: String = defaultStockUnit(MedicationForm.TABLET),
    val stockUnitEdited: Boolean = false,
    /** The opening count, as typed: "how many <stock unit> are there now?". */
    val stockQuantity: String = "",
    /** The days of stock that count as low, as typed. */
    val lowStockThresholdDays: String = MedicationRules.DEFAULT_LOW_STOCK_THRESHOLD_DAYS.toString(),
    /** Edit mode's optional count, written after a successful update. */
    val recountQuantity: String = "",
    val requiresPrescription: Boolean = false,
    val prescriptionValidUntil: String = "",
    val instructions: String = "",
    val schedule: ScheduleFormState = ScheduleFormState(),
    val inheritReminders: Boolean = true,
    val reminderPolicy: ReminderPolicy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
    val primaryCaregiverId: String? = null,
    val errors: Set<MedicationField> = emptySet(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: MedsUiError? = null,
) {
    /** Step titles for the progress line, 1-based. */
    val stepNumber: Int get() = step + 1

    companion object {
        const val STEP_COUNT: Int = 4
    }
}

private const val LAST_STEP = MedicationFormUiState.STEP_COUNT - 1

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
