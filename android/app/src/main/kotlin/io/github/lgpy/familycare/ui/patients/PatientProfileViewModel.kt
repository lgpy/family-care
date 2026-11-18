package io.github.lgpy.familycare.ui.patients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.core.ui.patient.TimelineEntry
import io.github.lgpy.familycare.core.ui.patient.TimelineSources
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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
 * exists (the same [canWrite] rule the form uses).
 */
data class PatientProfileUiState(
    val loaded: Boolean = false,
    /** The id was given but the patient is not in the local database (anymore). */
    val notFound: Boolean = false,
    val displayName: String = "",
    val canWrite: Boolean = false,
    val history: List<HistoryGroup> = emptyList(),
)

/**
 * The patient profile's state: the patient, the role and the merged history.
 *
 * The screen owns no history of its own; every source is a module's flow, so a
 * local write (a regimen change, a new appointment) refreshes the tab without
 * the shell knowing which module produced it.
 *
 * [zone] is the phone's zone: months are the user's months, not UTC's, and a
 * test can pin it.
 */
class PatientProfileViewModel(
    patientId: String,
    patientRepository: PatientRepository,
    careCircleRepository: CareCircleRepository,
    timelineSources: TimelineSources,
    zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val id = PatientId(patientId)

    val uiState: StateFlow<PatientProfileUiState> = combine(
        patientRepository.observe(id),
        careCircleRepository.myRole,
        timelineSources.entries(id),
    ) { patient, role, entries ->
        PatientProfileUiState(
            loaded = true,
            notFound = patient == null,
            displayName = patient?.displayName.orEmpty(),
            canWrite = role.canWrite,
            history = groupByMonth(entries, zone),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PatientProfileUiState(),
    )

    companion object {

        /** Groups [entries] newest first into the months of [zone]. */
        fun groupByMonth(entries: List<TimelineEntry>, zone: ZoneId): List<HistoryGroup> =
            entries
                .sortedByDescending { entry -> entry.at }
                .groupBy { YearMonth.from(it.at.atZone(zone)) }
                .map { (month, monthEntries) -> HistoryGroup(month, monthEntries) }
    }
}
