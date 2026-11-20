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
import io.github.lgpy.familycare.feature.patientinfo.data.PatientInfoError
import io.github.lgpy.familycare.feature.patientinfo.data.PatientInfoRepository
import io.github.lgpy.familycare.feature.patientinfo.data.PatientInfoResult
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfo
import java.time.Year
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

    /** The typed allergies do not fit patient-info's rules (count or length). */
    AllergiesInvalid,
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
    private val patientInfoRepository: PatientInfoRepository,
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
                    birthYear = existing.birthYear?.toString().orEmpty(),
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
        // Whatever the screen opened with is the state the discard question
        // compares against: only the user's own edits make the form dirty.
        _uiState.update { it.copy(baseline = it.draft()) }
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

    /**
     * Keeps the half-typed year as text; [save] parses it. The rule is 1900 up
     * to the current year: a year of birth in the future is a typo, even though
     * the database's wider 1900..2100 range would accept it.
     *
     * The number keypad still offers `.`, `,`, `-` and space on some phones, so
     * only digits are kept, at most the four of a year.
     */
    fun onBirthYearChanged(value: String) {
        val digits = value.filter { it.isDigit() }.take(4)
        _uiState.update {
            it.copy(
                birthYear = digits,
                errors = it.errors - CoreField.PATIENT_BIRTH_YEAR,
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

    /** The allergies as typed on Add patient; saved through patient-info. */
    fun onAllergiesChanged(value: String) {
        _uiState.update { it.copy(allergies = value, error = null) }
    }

    /**
     * The explicit "None known" answer. It turns the free-text field off and
     * saves no allergy row, because the record has no "none" value: an empty
     * list means "not recorded", never a clinical claim (docs/domain/patient-info.md).
     */
    fun onNoneKnownChanged(noneKnown: Boolean) {
        _uiState.update {
            it.copy(
                noneKnown = noneKnown,
                allergies = if (noneKnown) "" else it.allergies,
                error = null,
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

        val birthYearText = state.birthYear.trim()
        val birthYear = birthYearText.toIntOrNull()
        if (birthYearText.isNotEmpty() &&
            (birthYear == null || birthYear !in PatientRules.MIN_BIRTH_YEAR..Year.now().value)
        ) {
            errors += CoreField.PATIENT_BIRTH_YEAR
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
            birthYear = birthYear,
            reminderPolicy = state.reminderPolicy,
            primaryCaregiverId = primary,
            escalationDelayMinutes = escalation,
            missedWindowMinutes = missed,
            agreementNote = note,
        ) ?: patientRepository.newPatient(
            circleId = circle,
            displayName = name,
            linkedUserId = state.linkedUserId,
            birthYear = birthYear,
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

        // The allergies are Add patient's only extra field. They are stored in
        // `info.patient_info` through the patient-info repository, the same row
        // the profile's Info → Edit info screen writes. "None known" and an
        // empty field both write nothing: the record has no "none" value.
        val allergies = if (state.noneKnown || !state.isNew) {
            null
        } else {
            PatientInfo(allergies = parseAllergyEntries(state.allergies)).normalized()
                .takeIf { it.allergies.isNotEmpty() }
        }
        if (allergies != null && !allergies.validate().isValid) {
            _uiState.update { it.copy(error = PatientFormError.AllergiesInvalid) }
            return
        }

        _uiState.update { it.copy(errors = emptySet(), saving = true, error = null) }
        viewModelScope.launch {
            when (val result = patientRepository.save(patient)) {
                is CoreResult.Success -> {
                    // From here on a retry updates this patient instead of
                    // adding a second one when only the allergies failed.
                    if (loadedPatient == null) loadedPatient = patient.copy(id = result.value)
                    val allergyError = allergies?.let { info ->
                        when (val saved = patientInfoRepository.save(result.value, info)) {
                            is PatientInfoResult.Success -> null
                            is PatientInfoResult.Failure -> saved.error
                        }
                    }
                    _uiState.update {
                        if (allergyError == null) {
                            it.copy(saving = false, saved = true)
                        } else {
                            it.copy(
                                saving = false,
                                error = allergyError.toPatientFormError(),
                            )
                        }
                    }
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

/**
 * The allergy text as the entries the record stores: one per comma or newline,
 * trimmed, with a repeated entry kept once (case-insensitively). Blank input
 * gives an empty list, which writes no allergy row.
 */
internal fun parseAllergyEntries(text: String): List<String> =
    text.split(',', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }

/** What the discard question compares: only the fields the user can change. */
data class PatientFormDraft(
    val displayName: String,
    val birthYear: String,
    val linkedUserId: String?,
    val reminderPolicy: ReminderPolicy,
    val primaryCaregiverId: String?,
    val escalationDelay: String,
    val missedWindow: String,
    val agreementNote: String,
    val allergies: String,
    val noneKnown: Boolean,
)

/** The current editable values, as the baseline is stored after loading. */
internal fun PatientFormUiState.draft(): PatientFormDraft = PatientFormDraft(
    displayName = displayName,
    birthYear = birthYear,
    linkedUserId = linkedUserId,
    reminderPolicy = reminderPolicy,
    primaryCaregiverId = primaryCaregiverId,
    escalationDelay = escalationDelay,
    missedWindow = missedWindow,
    agreementNote = agreementNote,
    allergies = allergies,
    noneKnown = noneKnown,
)

/** The patient-info failure as the form's own refusal, so the screen can say it. */
private fun PatientInfoError.toPatientFormError(): PatientFormError = when (this) {
    PatientInfoError.NotAllowed, PatientInfoError.NoCircle -> PatientFormError.NotAllowed
    is PatientInfoError.Invalid -> PatientFormError.AllergiesInvalid
    PatientInfoError.Unknown -> PatientFormError.Unknown
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
    /** The year of birth as typed; empty when it is not recorded (optional). */
    val birthYear: String = "",
    val linkedUserId: String? = null,
    val reminderPolicy: ReminderPolicy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
    val primaryCaregiverId: String? = null,
    val escalationDelay: String =
        PatientRules.DEFAULT_ESCALATION_DELAY_MINUTES.toString(),
    val missedWindow: String = PatientRules.DEFAULT_MISSED_WINDOW_MINUTES.toString(),
    val agreementNote: String = "",
    /** Add patient's allergies, as typed and separated by commas. */
    val allergies: String = "",
    /** The explicit "None known" answer: no allergy row is written. */
    val noneKnown: Boolean = false,
    val errors: Set<CoreField> = emptySet(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: PatientFormError? = null,
    /** The loaded values; null until the form has loaded. */
    val baseline: PatientFormDraft? = null,
) {
    /** Whether the user changed anything that closing the form would lose. */
    val isDirty: Boolean get() = baseline != null && baseline != draft()

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
