package io.github.lgpy.familycare.feature.patientinfo.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.feature.patientinfo.data.PatientInfoError
import io.github.lgpy.familycare.feature.patientinfo.data.PatientInfoRepository
import io.github.lgpy.familycare.feature.patientinfo.data.PatientInfoResult
import io.github.lgpy.familycare.feature.patientinfo.domain.EmergencyContact
import io.github.lgpy.familycare.feature.patientinfo.domain.EmergencyContactError
import io.github.lgpy.familycare.feature.patientinfo.domain.EmergencyContactField
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfo
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfoField
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfoRules
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfoValidation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One emergency contact as it is being typed. */
data class ContactDraft(
    val name: String = "",
    val relationship: String = "",
    val phone: String = "",
)

/**
 * The edit form of a patient's info (docs/domain/patient-info.md "Screens").
 *
 * The lists are edited as chips: the draft field adds an entry, a chip's remove
 * button takes it away. Nothing is trimmed away silently — the form builds the
 * domain record as typed, so a blank entry is flagged by
 * [PatientInfoRules.validate] instead of disappearing.
 *
 * The record is read once (the form is not a live mirror of the synced row) and
 * written with [PatientInfoRepository.save], which inserts the row on the first
 * save and updates it afterwards. A viewer never sees the form's save action,
 * and a refusal from the repository is shown rather than swallowed.
 */
class PatientInfoEditViewModel(
    private val patientId: String,
    private val patientInfoRepository: PatientInfoRepository,
    private val careCircleRepository: CareCircleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PatientInfoEditUiState())
    val uiState: StateFlow<PatientInfoEditUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val role = careCircleRepository.myRole.first()
        val info = patientInfoRepository.observe(PatientId(patientId)).first()

        _uiState.update { state ->
            state.copy(
                loaded = true,
                canWrite = role.canWrite,
                allergies = info?.allergies.orEmpty(),
                conditions = info?.conditions.orEmpty(),
                snsNumber = info?.snsNumber.orEmpty(),
                familyDoctor = info?.familyDoctor.orEmpty(),
                healthCentre = info?.healthCentre.orEmpty(),
                healthCentrePhone = info?.healthCentrePhone.orEmpty(),
                notes = info?.notes.orEmpty(),
                contacts = info?.emergencyContacts.orEmpty().map { contact ->
                    ContactDraft(
                        name = contact.name,
                        relationship = contact.relationship.orEmpty(),
                        phone = contact.phone,
                    )
                },
            )
        }
    }

    // --- Allergies and conditions -------------------------------------------

    fun onAllergyDraftChanged(value: String) = update { it.copy(allergyDraft = value) }

    /** Adds the draft as an allergy, unless it is blank or the list is full. */
    fun addAllergy() = update { state ->
        val entry = state.allergyDraft.trim()
        when {
            entry.isEmpty() -> state
            state.allergies.size >= PatientInfoRules.MAX_ALLERGIES -> state
            else -> state.copy(
                allergies = state.allergies + entry,
                allergyDraft = "",
                validation = state.validation.copiedWithout(PatientInfoField.ALLERGIES),
            )
        }
    }

    fun removeAllergy(index: Int) = update { state ->
        state.copy(allergies = state.allergies.filterIndexed { i, _ -> i != index })
    }

    fun onConditionDraftChanged(value: String) = update { it.copy(conditionDraft = value) }

    /** Adds the draft as a condition, unless it is blank or the list is full. */
    fun addCondition() = update { state ->
        val entry = state.conditionDraft.trim()
        when {
            entry.isEmpty() -> state
            state.conditions.size >= PatientInfoRules.MAX_CONDITIONS -> state
            else -> state.copy(
                conditions = state.conditions + entry,
                conditionDraft = "",
                validation = state.validation.copiedWithout(PatientInfoField.CONDITIONS),
            )
        }
    }

    fun removeCondition(index: Int) = update { state ->
        state.copy(conditions = state.conditions.filterIndexed { i, _ -> i != index })
    }

    // --- Fields --------------------------------------------------------------

    fun onSnsNumberChanged(value: String) = updateField(PatientInfoField.SNS_NUMBER) {
        it.copy(snsNumber = value)
    }

    fun onFamilyDoctorChanged(value: String) = updateField(PatientInfoField.FAMILY_DOCTOR) {
        it.copy(familyDoctor = value)
    }

    fun onHealthCentreChanged(value: String) = updateField(PatientInfoField.HEALTH_CENTRE) {
        it.copy(healthCentre = value)
    }

    fun onHealthCentrePhoneChanged(value: String) =
        updateField(PatientInfoField.HEALTH_CENTRE_PHONE) { it.copy(healthCentrePhone = value) }

    fun onNotesChanged(value: String) = updateField(PatientInfoField.NOTES) {
        it.copy(notes = value)
    }

    // --- Emergency contacts ---------------------------------------------------

    /** Adds an empty contact while there is room for one more. */
    fun addContact() = update { state ->
        if (state.contacts.size >= PatientInfoRules.MAX_EMERGENCY_CONTACTS) {
            state
        } else {
            state.copy(
                contacts = state.contacts + ContactDraft(),
                validation = state.validation
                    .copiedWithout(PatientInfoField.EMERGENCY_CONTACTS),
            )
        }
    }

    fun removeContact(index: Int) = update { state ->
        state.copy(
            contacts = state.contacts.filterIndexed { i, _ -> i != index },
            // The indexes of the errors after the removed one shift, so the
            // errors are dropped; the next save re-derives them.
            validation = state.validation.copy(contactErrors = emptySet()),
        )
    }

    fun onContactNameChanged(index: Int, value: String) =
        updateContact(index, EmergencyContactField.NAME) { it.copy(name = value) }

    fun onContactRelationshipChanged(index: Int, value: String) =
        updateContact(index, EmergencyContactField.RELATIONSHIP) { it.copy(relationship = value) }

    fun onContactPhoneChanged(index: Int, value: String) =
        updateContact(index, EmergencyContactField.PHONE) { it.copy(phone = value) }

    // --- Saving ---------------------------------------------------------------

    /**
     * Validates the form and writes it.
     *
     * A domain rejection only marks the inputs; a role or circle refusal says so
     * without losing what was typed; a success sets [PatientInfoEditUiState.saved]
     * so the screen can close.
     */
    fun save() {
        val state = _uiState.value
        if (!state.loaded || !state.canWrite || state.saving) return

        val info = state.toPatientInfo().normalized()
        val validation = info.validate()
        if (!validation.isValid) {
            _uiState.update { it.copy(validation = validation, saveFailed = false, notAllowed = false) }
            return
        }

        _uiState.update {
            it.copy(
                saving = true,
                validation = PatientInfoValidation(),
                saveFailed = false,
                notAllowed = false,
            )
        }

        viewModelScope.launch {
            when (val result = patientInfoRepository.save(PatientId(patientId), info)) {
                is PatientInfoResult.Success -> _uiState.update {
                    it.copy(saving = false, saved = true)
                }

                is PatientInfoResult.Failure -> _uiState.update { current ->
                    when (val error = result.error) {
                        is PatientInfoError.Invalid ->
                            current.copy(saving = false, validation = error.validation)

                        PatientInfoError.NotAllowed, PatientInfoError.NoCircle ->
                            current.copy(saving = false, notAllowed = true)

                        PatientInfoError.Unknown -> current.copy(saving = false, saveFailed = true)
                    }
                }
            }
        }
    }

    // --- Helpers --------------------------------------------------------------

    private fun update(block: (PatientInfoEditUiState) -> PatientInfoEditUiState) {
        _uiState.update { state -> block(state) }
    }

    /** A row-level field edit; it clears that field's error. */
    private fun updateField(
        field: PatientInfoField,
        block: (PatientInfoEditUiState) -> PatientInfoEditUiState,
    ) = _uiState.update { state ->
        block(state).copy(validation = state.validation.copy(
            errors = state.validation.errors - field,
        ))
    }

    /** A contact edit; it clears that contact input's error. */
    private fun updateContact(
        index: Int,
        field: EmergencyContactField,
        block: (ContactDraft) -> ContactDraft,
    ) = _uiState.update { state ->
        state.copy(
            contacts = state.contacts.mapIndexed { i, contact ->
                if (i == index) block(contact) else contact
            },
            validation = state.validation.copy(
                contactErrors = state.validation.contactErrors - EmergencyContactError(index, field),
            ),
        )
    }

    private fun PatientInfoEditUiState.toPatientInfo(): PatientInfo = PatientInfo(
        allergies = allergies,
        conditions = conditions,
        snsNumber = snsNumber,
        familyDoctor = familyDoctor,
        healthCentre = healthCentre,
        healthCentrePhone = healthCentrePhone,
        emergencyContacts = contacts.map { contact ->
            EmergencyContact(
                name = contact.name,
                relationship = contact.relationship,
                phone = contact.phone,
            )
        },
        notes = notes,
    )
}

/**
 * What the edit form holds, what is wrong and whether it is saving.
 *
 * The drafts stay text until [PatientInfoEditViewModel.save], so a half-typed
 * value is never reinterpreted, and blank optional fields become null in the
 * domain record rather than an empty string.
 */
data class PatientInfoEditUiState(
    val loaded: Boolean = false,
    val canWrite: Boolean = false,
    val allergies: List<String> = emptyList(),
    val allergyDraft: String = "",
    val conditions: List<String> = emptyList(),
    val conditionDraft: String = "",
    val snsNumber: String = "",
    val familyDoctor: String = "",
    val healthCentre: String = "",
    val healthCentrePhone: String = "",
    val notes: String = "",
    val contacts: List<ContactDraft> = emptyList(),
    val validation: PatientInfoValidation = PatientInfoValidation(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val notAllowed: Boolean = false,
    val saveFailed: Boolean = false,
) {

    /** The row-level errors, each mapped to a message by the screen. */
    val errors: Set<PatientInfoField> get() = validation.errors

    /** The per-contact errors, marked inline on the matching input. */
    val contactErrors: Set<EmergencyContactError> get() = validation.contactErrors

    /** Whether the "Add" action for allergies is offered. */
    val canAddAllergy: Boolean get() = allergyDraft.trim().isNotEmpty()

    /** Whether the "Add" action for conditions is offered. */
    val canAddCondition: Boolean get() = conditionDraft.trim().isNotEmpty()

    /** Whether another emergency contact may be added (at most 5). */
    val canAddContact: Boolean get() = contacts.size < PatientInfoRules.MAX_EMERGENCY_CONTACTS

    /** Whether [field] was rejected by the last validation. */
    fun hasError(field: PatientInfoField): Boolean = field in errors

    /** Whether [field] of the contact at [index] was rejected. */
    fun hasError(index: Int, field: EmergencyContactField): Boolean =
        EmergencyContactError(index, field) in contactErrors
}

/** The validation without [field], used as the user edits it. */
private fun PatientInfoValidation.copiedWithout(field: PatientInfoField): PatientInfoValidation =
    copy(errors = errors - field)
