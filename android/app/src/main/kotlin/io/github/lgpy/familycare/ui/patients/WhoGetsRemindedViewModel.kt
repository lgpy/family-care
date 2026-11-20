package io.github.lgpy.familycare.ui.patients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * More → "Who gets reminded": the circle's patients, each with the person their
 * reminder policy currently points at.
 *
 * The summary itself is rendered by [reminderSummary] from the same
 * [reminderSummaryKind] the profile head uses, so this list and the profile can
 * never describe the routing differently. The list is read-only: a row opens
 * the patient's existing edit form, where the policy and primary caregiver are
 * changed.
 */
class WhoGetsRemindedViewModel(
    patientRepository: PatientRepository,
    careCircleRepository: CareCircleRepository,
) : ViewModel() {

    val uiState: StateFlow<WhoGetsRemindedUiState> = combine(
        patientRepository.patients,
        careCircleRepository.members,
    ) { patients, members ->
        WhoGetsRemindedUiState(
            loaded = true,
            rows = patients.map { patient ->
                val primary = members.firstOrNull { it.userId == patient.primaryCaregiverId }
                WhoGetsRemindedRow(
                    patient = patient,
                    primaryCaregiverName = primary?.displayName,
                    primaryCaregiverIsMe = primary?.isMe == true,
                )
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = WhoGetsRemindedUiState(),
    )
}

/** One patient and the caregiver their policy currently names. */
data class WhoGetsRemindedRow(
    val patient: Patient,
    /** The primary caregiver's display name, or null when nobody is named. */
    val primaryCaregiverName: String?,
    /** Whether that caregiver is the signed-in user. */
    val primaryCaregiverIsMe: Boolean,
) {

    /** The escalation delay the same summary line names. */
    val escalationDelayMinutes: Int get() = patient.escalationDelayMinutes
}

/** The list's state; [rows] is empty until the patients arrive. */
data class WhoGetsRemindedUiState(
    val loaded: Boolean = false,
    val rows: List<WhoGetsRemindedRow> = emptyList(),
)
