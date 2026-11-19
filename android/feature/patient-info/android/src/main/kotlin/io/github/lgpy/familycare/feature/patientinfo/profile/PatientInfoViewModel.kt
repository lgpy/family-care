package io.github.lgpy.familycare.feature.patientinfo.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
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
    patientRepository: PatientRepository,
    careCircleRepository: CareCircleRepository,
) : ViewModel() {

    val uiState: StateFlow<PatientInfoUiState> = combine(
        patientInfoRepository.observe(PatientId(patientId)),
        patientRepository.observe(PatientId(patientId)),
        careCircleRepository.myRole,
    ) { info, patient, role ->
        PatientInfoUiState(
            loaded = true,
            // An archived patient is read-only for everyone (D30).
            canWrite = patient != null && role.canWrite(patient),
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

    /**
     * Whether the tab itself has a row to show. Allergies and conditions are
     * drawn in the profile's header above the tabs, so a record with only those
     * is empty *here*: the tab would otherwise show nothing but "Edit".
     */
    val hasDetails: Boolean
        get() = info?.let { record ->
            !record.snsNumber.isNullOrBlank() ||
                !record.familyDoctor.isNullOrBlank() ||
                !record.healthCentre.isNullOrBlank() ||
                !record.healthCentrePhone.isNullOrBlank() ||
                record.emergencyContacts.isNotEmpty() ||
                !record.notes.isNullOrBlank()
        } == true
}
