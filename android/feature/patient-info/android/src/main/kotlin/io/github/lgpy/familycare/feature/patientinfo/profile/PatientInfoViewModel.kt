package io.github.lgpy.familycare.feature.patientinfo.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.feature.patientinfo.data.PatientInfoRepository
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * A patient's info as the profile shows it: the allergies banner above the tabs
 * and the Info tab.
 *
 * Both [AllergiesPatientHeader] and [InfoPatientTab] use this view model, so the
 * banner and the tab can never disagree about the record. [PatientInfoUiState.info]
 * is null while the patient has no row yet, which is different from an empty
 * record only in that there is nothing to show at all.
 */
class PatientInfoViewModel(
    patientId: String,
    patientInfoRepository: PatientInfoRepository,
    careCircleRepository: CareCircleRepository,
) : ViewModel() {

    val uiState: StateFlow<PatientInfoUiState> = combine(
        patientInfoRepository.observe(PatientId(patientId)),
        careCircleRepository.myRole,
    ) { info, role ->
        PatientInfoUiState(
            loaded = true,
            canWrite = role.canWrite,
            info = info,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PatientInfoUiState(),
    )
}

/** What the banner and the Info tab show. */
data class PatientInfoUiState(
    val loaded: Boolean = false,
    /** The patient's record, or null when none has been saved yet. */
    val info: PatientInfo? = null,
    /** Whether the "Edit" action exists, by the patient's circle role. */
    val canWrite: Boolean = false,
) {

    /** Allergies are always shown, and only when there are any (D29). */
    val showsAllergies: Boolean get() = info?.allergies?.isNotEmpty() == true

    /** The conditions line sits under the banner. */
    val showsConditions: Boolean get() = info?.conditions?.isNotEmpty() == true

    /** Nothing to say about this patient: no banner, no line. */
    val isHeaderVisible: Boolean get() = showsAllergies || showsConditions

    /** The Info tab's empty state: no field carries a value. */
    val isEmpty: Boolean get() = info?.isEmpty != false
}
