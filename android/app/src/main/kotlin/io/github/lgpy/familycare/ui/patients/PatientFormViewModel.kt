package io.github.lgpy.familycare.ui.patients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleId
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.CircleMember
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreField
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.PatientRules
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.core.domain.circle.canWrite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why saving failed, when it was not a per-field problem. */
enum class PatientFormError {
    Offline,
    NotAllowed,
    NotFound,
    Unknown,
}

/**
 * The patient form, for a new patient ([patientId] null) or an existing one.
 *
 * Defaults come from [PatientRules]: primary with escalation, the linked user as
 * primary caregiver when the patient also uses the app (self-managed), 30
 * minutes of escalation delay and a 120-minute missed window. The numeric
 * fields stay text so a half-typed value is not silently turned into a number;
 * [save] parses them and marks the fields whose value is missing or out of the
 * range the database enforces.
 *
 * Saving writes the local database ([PatientRepository.save]), so it works
 * offline; the row uploads when the network returns.
 */
class PatientFormViewModel(
    private val patientId: String?,
    private val patientRepository: PatientRepository,
    private val careCircleRepository: CareCircleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PatientFormUiState())
    val uiState: StateFlow<PatientFormUiState> = _uiState.asStateFlow()

    private var circleId: CareCircleId? = null
    private var loadedPatient: Patient? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val circle = careCircleRepository.circle.first()
        val role = careCircleRepository.myRole.first()
        val members = careCircleRepository.members.first()
        val existing = patientId?.let { patientRepository.observe(PatientId(it)).first() }

        circleId = circle?.id
        loadedPatient = existing

        _uiState.update { current ->
            if (existing != null) {
                current.copy(
                    loaded = true,
                    isNew = false,
                    canWrite = role.canWrite,
                    members = members,
                    displayName = existing.displayName,
                    linkedUserId = existing.linkedUserId,
                    reminderPolicy = existing.reminderPolicy,
                    primaryCaregiverId = existing.primaryCaregiverId,
                    escalationDelay = existing.escalationDelayMinutes.toString(),
                    missedWindow = existing.missedWindowMinutes.toString(),
                    agreementNote = existing.agreementNote.orEmpty(),
                )
            } else {
                current.copy(
                    loaded = true,
                    isNew = patientId == null,
                    notFound = patientId != null,
                    canWrite = role.canWrite,
                    members = members,
                )
            }
        }
    }

    fun onNameChanged(value: String) {
        _uiState.update {
            it.copy(
                displayName = value,
                errors = it.errors - CoreField.PATIENT_DISPLAY_NAME,
            )
        }
    }

    /**
     * Picks whether the patient is also an app user. When one is picked, the
     * primary caregiver becomes that user ([PatientRules.defaultPrimaryCaregiver]);
     * clearing it keeps the current choice.
     */
    fun onLinkedUserChanged(userId: String?) {
        _uiState.update {
            it.copy(
                linkedUserId = userId,
                primaryCaregiverId =
                    PatientRules.defaultPrimaryCaregiver(userId, it.primaryCaregiverId),
                errors = it.errors -
                    CoreField.PATIENT_LINKED_USER -
                    CoreField.PATIENT_PRIMARY_CAREGIVER,
            )
        }
    }

    fun onReminderPolicyChanged(policy: ReminderPolicy) {
        _uiState.update {
            it.copy(
                reminderPolicy = policy,
                errors = it.errors - CoreField.PATIENT_REMINDER_POLICY,
            )
        }
    }

    fun onPrimaryCaregiverChanged(userId: String) {
        _uiState.update {
            it.copy(
                primaryCaregiverId = userId,
                errors = it.errors - CoreField.PATIENT_PRIMARY_CAREGIVER,
            )
        }
    }

    fun onEscalationDelayChanged(value: String) {
        _uiState.update {
            it.copy(
                escalationDelay = value,
                errors = it.errors - CoreField.PATIENT_ESCALATION_DELAY,
            )
        }
    }

    fun onMissedWindowChanged(value: String) {
        _uiState.update {
            it.copy(
                missedWindow = value,
                errors = it.errors - CoreField.PATIENT_MISSED_WINDOW,
            )
        }
    }

    fun onAgreementNoteChanged(value: String) {
        _uiState.update {
            it.copy(
                agreementNote = value,
                errors = it.errors - CoreField.PATIENT_AGREEMENT_NOTE,
            )
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    /** Validates the form and stores the patient (local write, works offline). */
    fun save() {
        val state = _uiState.value
        if (!state.loaded || !state.canWrite || state.saving) return

        val errors = mutableSetOf<CoreField>()

        val name = PatientRules.normalizeName(state.displayName)
        if (!PatientRules.isValidName(name)) {
            errors += CoreField.PATIENT_DISPLAY_NAME
        }

        val escalation = state.escalationDelay.trim().toIntOrNull()
        if (escalation == null) {
            errors += CoreField.PATIENT_ESCALATION_DELAY
        }

        val missed = state.missedWindow.trim().toIntOrNull()
        if (missed == null) {
            errors += CoreField.PATIENT_MISSED_WINDOW
        }

        val primary = PatientRules.defaultPrimaryCaregiver(
            linkedUserId = state.linkedUserId,
            chosenPrimaryCaregiverId = state.primaryCaregiverId,
        )
        if (state.reminderPolicy == ReminderPolicy.PRIMARY_WITH_ESCALATION && primary == null) {
            errors += CoreField.PATIENT_PRIMARY_CAREGIVER
        }

        val circle = circleId
        if (circle == null) {
            _uiState.update { it.copy(error = PatientFormError.NotAllowed) }
            return
        }

        if (errors.isNotEmpty() || escalation == null || missed == null) {
            _uiState.update { it.copy(errors = errors) }
            return
        }

        val note = PatientRules.normalizeNote(state.agreementNote)
        val patient = loadedPatient?.copy(
            displayName = name,
            linkedUserId = state.linkedUserId,
            reminderPolicy = state.reminderPolicy,
            primaryCaregiverId = primary,
            escalationDelayMinutes = escalation,
            missedWindowMinutes = missed,
            agreementNote = note,
        ) ?: patientRepository.newPatient(
            circleId = circle,
            displayName = name,
            linkedUserId = state.linkedUserId,
            chosenPrimaryCaregiverId = state.primaryCaregiverId,
            reminderPolicy = state.reminderPolicy,
            escalationDelayMinutes = escalation,
            missedWindowMinutes = missed,
            agreementNote = note,
        )

        // The relationships the server checks in core.check_patient(): a linked
        // user must be a member, and the primary caregiver must be able to write.
        val validation = patient.validateAgainst(state.members)
        if (!validation.isValid) {
            _uiState.update { it.copy(errors = validation.errors, error = null) }
            return
        }

        _uiState.update { it.copy(errors = emptySet(), saving = true, error = null) }
        viewModelScope.launch {
            when (val result = patientRepository.save(patient)) {
                is CoreResult.Success -> _uiState.update {
                    it.copy(saving = false, saved = true)
                }

                is CoreResult.Failure -> _uiState.update { current ->
                    when (val error = result.error) {
                        is CoreError.Invalid -> current.copy(saving = false, errors = error.fields)
                        else -> current.copy(
                            saving = false,
                            error = error.toPatientFormError(),
                        )
                    }
                }
            }
        }
    }
}

/** The form state: what the fields hold, what is wrong, and whether it is saving. */
data class PatientFormUiState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    /** The id was given but the patient is not in the local database (anymore). */
    val notFound: Boolean = false,
    val canWrite: Boolean = false,
    val members: List<CircleMember> = emptyList(),
    val displayName: String = "",
    val linkedUserId: String? = null,
    val reminderPolicy: ReminderPolicy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
    val primaryCaregiverId: String? = null,
    val escalationDelay: String =
        PatientRules.DEFAULT_ESCALATION_DELAY_MINUTES.toString(),
    val missedWindow: String = PatientRules.DEFAULT_MISSED_WINDOW_MINUTES.toString(),
    val agreementNote: String = "",
    val errors: Set<CoreField> = emptySet(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: PatientFormError? = null,
) {
    /** A patient with a linked user is reminded first, so the picker is not shown. */
    val isSelfManaged: Boolean get() = linkedUserId != null

    /**
     * An existing patient nobody would be reminded for (D24: a removed member
     * left it without a primary caregiver). The form flags it at the top.
     */
    val needsPrimaryCaregiver: Boolean
        get() = !isNew && PatientRules.needsPrimaryCaregiver(reminderPolicy, primaryCaregiverId)
}

/** The [CoreError] to [PatientFormError] mapping for a save that failed. */
fun CoreError.toPatientFormError(): PatientFormError = when (this) {
    CoreError.Offline -> PatientFormError.Offline
    CoreError.NotAllowed, CoreError.NoCircle -> PatientFormError.NotAllowed
    CoreError.NotFound -> PatientFormError.NotFound
    else -> PatientFormError.Unknown
}
