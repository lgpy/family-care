package io.github.lgpy.familycare.feature.medications.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.data.MedicationWithRegimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * One patient's medications (docs/ux/screens.md §3): the active ones first, and
 * the stopped ones collapsed behind a header.
 *
 * The full patient profile with tabs is M6, so this is the medications entry
 * point the Patients list opens today. The list is read-only here; adding,
 * editing, changing the schedule and stopping each have their own screen.
 */
class PatientMedicationsViewModel(
    private val patientId: String,
    medicationRepository: MedicationRepository,
    patientRepository: PatientRepository,
    careCircleRepository: CareCircleRepository,
) : ViewModel() {

    private val local = MutableStateFlow(PatientMedicationsUiState())

    val uiState: StateFlow<PatientMedicationsUiState> = combine(
        medicationRepository.observe(PatientId(patientId)),
        patientRepository.observe(PatientId(patientId)),
        careCircleRepository.myRole,
        local,
    ) { medications, patient, role, state ->
        val active = medications
            .filterNot { it.medication.isStopped }
            .sortedBy { it.medication.name.lowercase() }
        val stopped = medications
            .filter { it.medication.isStopped }
            .sortedByDescending { it.medication.stoppedAt }
        state.copy(
            loaded = true,
            patientName = patient?.displayName,
            patientMissing = patient == null,
            canWrite = patient != null && role.canWrite(patient),
            active = active,
            stopped = stopped,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PatientMedicationsUiState(),
    )

    /** Shows or hides the "Stopped" section. */
    fun toggleStopped() {
        local.update { it.copy(stoppedExpanded = !it.stoppedExpanded) }
    }
}

/** What the patient's medication list shows. */
data class PatientMedicationsUiState(
    val loaded: Boolean = false,
    val patientName: String? = null,
    /** The patient id exists in the route but not in the local database. */
    val patientMissing: Boolean = false,
    val canWrite: Boolean = false,
    val active: List<MedicationWithRegimens> = emptyList(),
    val stopped: List<MedicationWithRegimens> = emptyList(),
    val stoppedExpanded: Boolean = false,
) {
    val isEmpty: Boolean get() = active.isEmpty() && stopped.isEmpty()
}
