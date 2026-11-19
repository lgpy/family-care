package io.github.lgpy.familycare.ui.patients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.canManage
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.core.ui.patient.TimelineEntry
import io.github.lgpy.familycare.core.ui.patient.TimelineSources
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One month of the History tab: its events, newest first. */
data class HistoryGroup(
    val month: YearMonth,
    val entries: List<TimelineEntry>,
)

/**
 * The patient profile (D29, docs/ux/screens.md §3).
 *
 * [history] is every module's [TimelineSources] merged, newest first and
 * grouped by month; [canWrite] decides whether the "Edit patient" action
 * exists (the same [canWrite] rule the form uses, false for an archived
 * patient) and [canManage] whether the owner-only Archive and Delete actions
 * exist (D30).
 */
data class PatientProfileUiState(
    val loaded: Boolean = false,
    /** The id was given but the patient is not in the local database (anymore). */
    val notFound: Boolean = false,
    val displayName: String = "",
    val canWrite: Boolean = false,
    val canManage: Boolean = false,
    val isArchived: Boolean = false,
    /** The last archive/unarchive/erase failure, to show once. */
    val actionError: CoreError? = null,
    /** An erase is in flight (the button shows progress and the dialog stays). */
    val erasing: Boolean = false,
    /** The patient was erased: the screen navigates back to the list. */
    val erased: Boolean = false,
    val history: List<HistoryGroup> = emptyList(),
)

/**
 * The patient profile's state: the patient, the role and the merged history.
 *
 * The screen owns no history of its own; every source is a module's flow, so a
 * local write (a regimen change, a new appointment) refreshes the tab without
 * the shell knowing which module produced it.
 *
 * Archive, unarchive and erase (D30) are the owner's lifecycle actions; the
 * screen only asks for confirmation. Erase needs the typed name, which is the
 * screen's check, so a mis-tap cannot delete a person here.
 *
 * [zone] is the phone's zone: months are the user's months, not UTC's, and a
 * test can pin it.
 */
class PatientProfileViewModel(
    patientId: String,
    private val patientRepository: PatientRepository,
    careCircleRepository: CareCircleRepository,
    timelineSources: TimelineSources,
    zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val id = PatientId(patientId)

    private data class ActionState(
        val error: CoreError? = null,
        val erasing: Boolean = false,
        val erased: Boolean = false,
    )

    private val action = MutableStateFlow(ActionState())

    val uiState: StateFlow<PatientProfileUiState> = combine(
        patientRepository.observe(id),
        careCircleRepository.myRole,
        timelineSources.entries(id),
        action,
    ) { patient, role, entries, actionState ->
        PatientProfileUiState(
            loaded = true,
            notFound = patient == null,
            displayName = patient?.displayName.orEmpty(),
            canWrite = patient != null && role.canWrite(patient),
            canManage = role.canManage,
            isArchived = patient?.isArchived == true,
            actionError = actionState.error,
            erasing = actionState.erasing,
            erased = actionState.erased,
            history = groupByMonth(entries, zone),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PatientProfileUiState(),
    )

    /** Archives the patient (owner only); the banner and read-only mode appear locally. */
    fun archive() = runAction { patientRepository.archive(id) }

    /** Clears the archive flag, making the patient editable again (owner only). */
    fun unarchive() = runAction { patientRepository.unarchive(id) }

    /**
     * Erases the patient for good (owner only). The screen has already checked
     * that the typed name matches; the server checks the role again.
     */
    fun erase() {
        action.update { it.copy(erasing = true, error = null) }
        viewModelScope.launch {
            when (val result = patientRepository.erase(id)) {
                is CoreResult.Success ->
                    action.update { it.copy(erasing = false, erased = true) }

                is CoreResult.Failure ->
                    action.update { it.copy(erasing = false, error = result.error) }
            }
        }
    }

    /** Dismisses the error line after the user has seen it. */
    fun clearActionError() {
        action.update { it.copy(error = null) }
    }

    private fun runAction(block: suspend () -> CoreResult<PatientId>) {
        action.update { it.copy(error = null) }
        viewModelScope.launch {
            when (val result = block()) {
                is CoreResult.Success -> Unit
                is CoreResult.Failure -> action.update { it.copy(error = result.error) }
            }
        }
    }

    companion object {

        /** Groups [entries] newest first into the months of [zone]. */
        fun groupByMonth(entries: List<TimelineEntry>, zone: ZoneId): List<HistoryGroup> =
            entries
                .sortedByDescending { entry -> entry.at }
                .groupBy { YearMonth.from(it.at.atZone(zone)) }
                .map { (month, monthEntries) -> HistoryGroup(month, monthEntries) }
    }
}
