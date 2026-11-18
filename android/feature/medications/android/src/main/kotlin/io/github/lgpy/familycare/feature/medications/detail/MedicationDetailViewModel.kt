package io.github.lgpy.familycare.feature.medications.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.feature.medications.data.DoseLogRepository
import io.github.lgpy.familycare.feature.medications.data.MedsError
import io.github.lgpy.familycare.feature.medications.data.MedsResult
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.data.MedicationWithRegimens
import io.github.lgpy.familycare.feature.medications.domain.DoseLogEntry
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.ui.MedsUiError
import io.github.lgpy.familycare.feature.medications.ui.uiError
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One medication (docs/ux/screens.md §3, docs/build-plan.md M3 "versioned regimen
 * changes"): its details, the current schedule, the earlier versions and the most
 * recent doses, plus the three actions.
 *
 * Stopping asks for a last day, which defaults to today and is clamped to the
 * current version's own bounds (never before its start, never after an existing
 * end). The repository applies [io.github.lgpy.familycare.feature.medications.domain.RegimenRules.stop]
 * and sets `stopped_at` in one transaction.
 */
class MedicationDetailViewModel(
    private val medicationId: String,
    private val medicationRepository: MedicationRepository,
    doseLogRepository: DoseLogRepository,
    careCircleRepository: CareCircleRepository,
    private val today: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    private val local = MutableStateFlow(MedicationDetailUiState())

    val uiState: StateFlow<MedicationDetailUiState> = combine(
        medicationRepository.observeMedication(MedicationId(medicationId)),
        doseLogRepository.observeMedicationEntries(MedicationId(medicationId)),
        careCircleRepository.myRole,
        local,
    ) { loaded, entries, role, state ->
        state.copy(
            loaded = true,
            notFound = loaded == null,
            canWrite = role.canWrite,
            medication = loaded,
            recentEntries = entries
                .sortedWith(compareByDescending<DoseLogEntry> { it.scheduledLocal }.thenByDescending { it.actualAt })
                .take(MedicationDetailUiState.RECENT_ENTRIES),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MedicationDetailUiState(),
    )

    /** Opens the stop dialog with a last day the domain will accept. */
    fun requestStop() {
        val current = uiState.value.medication?.currentRegimen ?: return
        val lastDay = when {
            today().isBefore(current.startDate) -> current.startDate
            current.endDate != null && today().isAfter(current.endDate) -> current.endDate
            else -> today()
        }
        local.update { it.copy(showStop = true, stopLastDay = lastDay.toString(), stopDateInvalid = false) }
    }

    fun dismissStop() = local.update { it.copy(showStop = false) }

    fun onLastDayChanged(value: String) =
        local.update { it.copy(stopLastDay = value, stopDateInvalid = false) }

    fun clearError() = local.update { it.copy(error = null) }

    /** Stops the medication from [MedicationDetailUiState.stopLastDay]. */
    fun confirmStop() {
        val state = uiState.value
        val loaded = state.medication ?: return
        val current = loaded.currentRegimen ?: return
        if (!state.canWrite || state.stopping) return

        val lastDay = runCatching { LocalDate.parse(state.stopLastDay.trim()) }.getOrNull()
        if (lastDay == null) {
            local.update { it.copy(stopDateInvalid = true) }
            return
        }

        local.update { it.copy(stopping = true, error = null) }
        viewModelScope.launch {
            when (
                val result = medicationRepository.stop(
                    medication = loaded.medication,
                    regimen = current,
                    lastDay = lastDay,
                )
            ) {
                is MedsResult.Success<*> -> local.update {
                    it.copy(stopping = false, showStop = false)
                }

                is MedsResult.Failure -> local.update {
                    it.copy(
                        stopping = false,
                        stopDateInvalid = result.error is MedsError.Invalid,
                        error = result.uiError(),
                    )
                }
            }
        }
    }
}

/** What the medication detail screen shows. */
data class MedicationDetailUiState(
    val loaded: Boolean = false,
    val notFound: Boolean = false,
    val canWrite: Boolean = false,
    val medication: MedicationWithRegimens? = null,
    val recentEntries: List<DoseLogEntry> = emptyList(),
    val showStop: Boolean = false,
    val stopLastDay: String = "",
    val stopDateInvalid: Boolean = false,
    val stopping: Boolean = false,
    val error: MedsUiError? = null,
) {

    /** Earlier versions, oldest first ("5 mg until 3 March"). */
    val history: List<io.github.lgpy.familycare.feature.medications.domain.Regimen>
        get() = medication?.history.orEmpty()

    companion object {
        const val RECENT_ENTRIES: Int = 12
    }
}
