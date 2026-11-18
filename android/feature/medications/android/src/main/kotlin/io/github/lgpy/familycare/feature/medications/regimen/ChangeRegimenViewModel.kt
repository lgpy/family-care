package io.github.lgpy.familycare.feature.medications.regimen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.feature.medications.data.MedsError
import io.github.lgpy.familycare.feature.medications.data.MedsResult
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.Regimen
import io.github.lgpy.familycare.feature.medications.domain.RegimenId
import io.github.lgpy.familycare.feature.medications.domain.ScheduleType
import io.github.lgpy.familycare.feature.medications.ui.DoseTimeEntry
import io.github.lgpy.familycare.feature.medications.ui.MedsUiError
import io.github.lgpy.familycare.feature.medications.ui.ScheduleFormState
import io.github.lgpy.familycare.feature.medications.ui.steppedQuantity
import io.github.lgpy.familycare.feature.medications.ui.uiError
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "Change dose or schedule" (docs/domain/medications.md "Regimen", D9).
 *
 * A change never edits the current version: [MedicationRepository.changeRegimen]
 * ends it the day before [ChangeRegimenUiState.effectiveFrom] and starts a new
 * one. The date defaults to tomorrow, because a change must be *after* the
 * current version's start (today is allowed only when the version started
 * earlier); the domain rejects anything else and this form mirrors the check so
 * the date field is marked before the write.
 */
class ChangeRegimenViewModel(
    private val medicationId: String,
    private val medicationRepository: MedicationRepository,
    careCircleRepository: CareCircleRepository,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val today: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChangeRegimenUiState(effectiveFrom = today().plusDays(1).toString()),
    )
    val uiState: StateFlow<ChangeRegimenUiState> = _uiState.asStateFlow()

    private var current: Regimen? = null

    init {
        viewModelScope.launch {
            val role = careCircleRepository.myRole.first()
            val loaded = medicationRepository.observeMedication(MedicationId(medicationId)).first()
            val regimen = loaded?.currentRegimen
            current = regimen
            _uiState.update { state ->
                state.copy(
                    loaded = true,
                    notFound = loaded == null || regimen == null,
                    canWrite = role.canWrite,
                    medicationName = loaded?.medication?.name,
                    current = regimen,
                    schedule = ScheduleFormState.of(regimen),
                )
            }
        }
    }

    fun onEffectiveFromChanged(value: String) =
        _uiState.update { it.copy(effectiveFrom = value, errors = emptySet()) }

    fun onScheduleTypeChanged(type: ScheduleType) = updateSchedule { it.copy(scheduleType = type) }

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
        state.copy(
            times = state.times + DoseTimeEntry(
                time = (state.times.lastOrNull()?.time ?: "08:00"),
                quantity = BigDecimal.ONE,
            ),
        )
    }

    fun onRemoveTime(index: Int) = updateSchedule { state ->
        if (state.times.size <= 1) state else state.copy(times = state.times.filterIndexed { i, _ -> i != index })
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    fun save() {
        val state = _uiState.value
        val regimen = current
        if (!state.loaded || !state.canWrite || state.saving || regimen == null) return

        val fields = mutableSetOf<MedicationField>()
        val effectiveFrom = runCatching { LocalDate.parse(state.effectiveFrom.trim()) }.getOrNull()
        if (effectiveFrom == null) fields += MedicationField.REGIMEN_START_DATE

        val parsed = state.schedule.parse(
            id = RegimenId(newId()),
            medicationId = MedicationId(medicationId),
            timeZone = regimen.timeZone,
        )
        fields += parsed.fields

        if (effectiveFrom != null) {
            val end = regimen.endDate
            if (!effectiveFrom.isAfter(regimen.startDate) || (end != null && effectiveFrom.isAfter(end))) {
                fields += MedicationField.REGIMEN_START_DATE
            }
        }

        val newRegimen = parsed.regimen
        if (fields.isNotEmpty() || effectiveFrom == null || newRegimen == null) {
            _uiState.update { it.copy(errors = fields) }
            return
        }

        _uiState.update { it.copy(errors = emptySet(), saving = true, error = null) }
        viewModelScope.launch {
            val result = medicationRepository.changeRegimen(
                current = regimen,
                effectiveFrom = effectiveFrom,
                times = newRegimen.times,
                scheduleType = newRegimen.scheduleType,
                weekdays = newRegimen.weekdays,
                intervalDays = newRegimen.intervalDays,
            )
            when (result) {
                is MedsResult.Success<*> ->
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

    private fun updateSchedule(block: (ScheduleFormState) -> ScheduleFormState) =
        _uiState.update { it.copy(schedule = block(it.schedule), errors = emptySet()) }
}

/** What the change-regimen screen holds. */
data class ChangeRegimenUiState(
    val loaded: Boolean = false,
    val notFound: Boolean = false,
    val canWrite: Boolean = false,
    val medicationName: String? = null,
    val current: Regimen? = null,
    val effectiveFrom: String = "",
    val schedule: ScheduleFormState = ScheduleFormState(),
    val errors: Set<MedicationField> = emptySet(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: MedsUiError? = null,
)
