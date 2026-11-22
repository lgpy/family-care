package io.github.lgpy.familycare.feature.medications.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.data.MedicationWithRegimens
import io.github.lgpy.familycare.feature.medications.data.StockRepository
import io.github.lgpy.familycare.feature.medications.domain.Medication
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * One medication row of a patient's list, with the stock the meter shows.
 *
 * [daysRemaining] is the domain's derived number (null when no schedule
 * consumes it), and [low] is the same flag the shopping list uses, so the row
 * never recounts the ledger.
 */
data class PatientMedicationUi(
    val item: MedicationWithRegimens,
    val daysRemaining: Int? = null,
    val low: Boolean = false,
) {

    /** Shorthand for the medication, kept for the screens and tests. */
    val medication: Medication get() = item.medication
}

/**
 * One patient's medications (docs/ux/screens.md §3): the active ones first, and
 * the stopped ones collapsed behind a header.
 *
 * The full patient profile with tabs is M6, so this is the medications entry
 * point the Patients list opens today. The list is read-only here; adding,
 * editing, changing the schedule and stopping each have their own screen.
 *
 * The stock overviews are the same flow the shopping list and the Today card
 * read: a medication's days left and low flag are derived once in the domain and
 * only displayed here ([PatientMedicationUi.daysRemaining]).
 */
class PatientMedicationsViewModel(
    private val patientId: String,
    medicationRepository: MedicationRepository,
    patientRepository: PatientRepository,
    stockRepository: StockRepository,
    careCircleRepository: CareCircleRepository,
    now: () -> Instant = { Instant.now() },
) : ViewModel() {

    private val local = MutableStateFlow(PatientMedicationsUiState())

    val uiState: StateFlow<PatientMedicationsUiState> = combine(
        medicationRepository.observe(PatientId(patientId)),
        stockRepository.overviews,
        patientRepository.observe(PatientId(patientId)),
        careCircleRepository.myRole,
        local,
    ) { medications, overviews, patient, role, state ->
        val stock = overviews.associateBy { it.medication.id.value }

        fun row(item: MedicationWithRegimens) = PatientMedicationUi(
            item = item,
            daysRemaining = stock[item.medication.id.value]?.summary?.daysRemaining,
            low = stock[item.medication.id.value]?.low == true,
        )

        val at = now()
        val active = medications
            .filterNot { it.medication.isStoppedAt(at) }
            .sortedBy { it.medication.name.lowercase() }
            .map(::row)
        val stopped = medications
            .filter { it.medication.isStoppedAt(at) }
            .sortedByDescending { it.medication.stoppedAt }
            .map(::row)
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
    val active: List<PatientMedicationUi> = emptyList(),
    val stopped: List<PatientMedicationUi> = emptyList(),
    val stoppedExpanded: Boolean = false,
) {
    val isEmpty: Boolean get() = active.isEmpty() && stopped.isEmpty()
}
