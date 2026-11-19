package io.github.lgpy.familycare.ui.patients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.core.ui.patient.PatientSummaryItem
import io.github.lgpy.familycare.core.ui.patient.PatientSummarySources
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * One row of the patient list, with the primary caregiver's name resolved and
 * the modules' summary facts merged.
 *
 * [summary] is every registered [PatientSummarySources] item for the patient,
 * already in source order: the plain ones form the row's meta line and the
 * tagged ones (allergies, low stock) sit under it.
 */
data class PatientRowUi(
    val patient: Patient,
    val primaryCaregiverName: String?,
    val summary: List<PatientSummaryItem> = emptyList(),
)

/**
 * State of the Patients tab.
 *
 * [canWrite] decides whether "Add patient" and the form's save button exist;
 * it comes from [MemberRole.canWrite], the client half of the server's access
 * function.
 */
data class PatientsUiState(
    val patients: List<PatientRowUi> = emptyList(),
    /** The circle's archived patients, shown collapsed at the bottom (D30). */
    val archivedPatients: List<PatientRowUi> = emptyList(),
    val canWrite: Boolean = false,
    val loaded: Boolean = false,
)

/**
 * The patient list (docs/ux/screens.md §3): every active patient in the current
 * circle, sorted by the repository, with the facts the feature modules
 * contribute to each row (screens v2).
 *
 * The app names no feature module (D5): [summarySources] is whatever the
 * dependency graph collected, and a local write in any module refreshes its own
 * fact because every source is a flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PatientsViewModel(
    patientRepository: PatientRepository,
    careCircleRepository: CareCircleRepository,
    summarySources: PatientSummarySources = PatientSummarySources(emptyList()),
) : ViewModel() {

    private val rows: Flow<PatientsUiState> = combine(
        patientRepository.patients,
        patientRepository.archivedPatients,
        careCircleRepository.members,
        careCircleRepository.myRole,
    ) { patients, archived, members, role ->
        val names = members.associate { it.userId to it.displayName }
        fun row(patient: Patient) = PatientRowUi(
            patient = patient,
            primaryCaregiverName = patient.primaryCaregiverId?.let { names[it] },
        )
        PatientsUiState(
            patients = patients.map(::row),
            archivedPatients = archived.map(::row),
            canWrite = role.canWrite,
            loaded = true,
        )
    }

    val uiState: StateFlow<PatientsUiState> = rows.flatMapLatest { state ->
        withSummaries(state, summarySources)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PatientsUiState(),
    )

    /**
     * Merges every source's facts into each row, keeping the sources' order.
     *
     * The summary flows are watched for the active and archived patients
     * together, so archiving someone never leaves a stale line on the list.
     */
    private fun withSummaries(
        state: PatientsUiState,
        sources: PatientSummarySources,
    ): Flow<PatientsUiState> {
        val rows = state.patients + state.archivedPatients
        if (rows.isEmpty()) return flowOf(state)

        val perPatient = rows.map { row ->
            sources.summary(row.patient.id).map { items -> row.patient.id.value to items }
        }
        return combine(perPatient) { summaries ->
            val byPatient = summaries.toMap()
            state.copy(
                patients = state.patients.withSummary(byPatient),
                archivedPatients = state.archivedPatients.withSummary(byPatient),
            )
        }
    }

    private fun List<PatientRowUi>.withSummary(
        byPatient: Map<String, List<PatientSummaryItem>>,
    ): List<PatientRowUi> = map { row ->
        row.copy(summary = byPatient[row.patient.id.value].orEmpty())
    }
}
