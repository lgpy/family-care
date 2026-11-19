package io.github.lgpy.familycare.ui.patients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.canWrite
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One row of the patient list, with the primary caregiver's name resolved. */
data class PatientRowUi(
    val patient: Patient,
    val primaryCaregiverName: String?,
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
 * circle, sorted by the repository, with the policy and primary caregiver as a
 * one-line hint.
 */
class PatientsViewModel(
    patientRepository: PatientRepository,
    careCircleRepository: CareCircleRepository,
) : ViewModel() {

    val uiState: StateFlow<PatientsUiState> = combine(
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
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PatientsUiState(),
    )
}
