package io.github.lgpy.familycare.feature.appointments.form

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.CircleMember
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.feature.appointments.data.AppointmentEntry
import io.github.lgpy.familycare.feature.appointments.data.AppointmentRepository
import io.github.lgpy.familycare.feature.appointments.data.AppointmentsError
import io.github.lgpy.familycare.feature.appointments.data.AppointmentsResult
import io.github.lgpy.familycare.feature.appointments.domain.Appointment
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentField
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentId
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentKind
import io.github.lgpy.familycare.feature.appointments.ui.AppointmentsUiError
import io.github.lgpy.familycare.feature.appointments.ui.PreparationTag
import io.github.lgpy.familycare.feature.appointments.ui.composePreparation
import io.github.lgpy.familycare.feature.appointments.ui.parsePreparation
import io.github.lgpy.familycare.feature.appointments.ui.toUiError
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The create / edit / follow-up form (docs/domain/appointments.md "Screens").
 *
 * The patient is fixed when the form comes from a patient's tab; from the Agenda
 * it starts empty and the form shows the picker. The time zone is the device's
 * on create and the stored one on edit (it is not editable in the UI, so a trip
 * never silently rewrites the appointment).
 *
 * The companion picker offers the circle's owners and caregivers plus "Anyone"
 * ([Appointment.companionUserId] null), which is who the server reminds. The
 * repository still checks the membership before writing.
 */
class AppointmentFormViewModel(
    private val patientIdArg: String?,
    private val appointmentId: String?,
    private val followUpOfArg: String?,
    private val appointmentRepository: AppointmentRepository,
    patientRepository: PatientRepository,
    careCircleRepository: CareCircleRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Instant = { Instant.now() },
) : ViewModel() {

    private val state = MutableStateFlow(
        AppointmentFormUiState(
            isEdit = appointmentId != null,
            patientId = patientIdArg?.takeIf { it.isNotBlank() },
            followUpOf = followUpOfArg,
            date = now().atZone(zone).toLocalDate(),
            timeZone = zone.id,
            loaded = appointmentId == null && followUpOfArg == null,
        ),
    )

    /** The circle's appointments, read only to offer recently used places. */
    private var agendaEntries: List<AppointmentEntry> = emptyList()

    val uiState: StateFlow<AppointmentFormUiState> = state

    init {
        viewModelScope.launch {
            appointmentRepository.observeAgenda(includeCancelled = true).collect { entries ->
                agendaEntries = entries
                refreshRecentPlaces()
            }
        }
        viewModelScope.launch {
            patientRepository.patients.collect { patients ->
                state.update { current ->
                    current.copy(
                        patients = patients.map { PatientOption(it.id.value, it.displayName) },
                    )
                }
            }
        }
        viewModelScope.launch {
            careCircleRepository.members.collect { members ->
                state.update { current ->
                    current.copy(
                        members = members
                            .filter { it.role == MemberRole.OWNER || it.role == MemberRole.CAREGIVER }
                            .sortedWith(MemberOrder),
                    )
                }
            }
        }
        viewModelScope.launch {
            careCircleRepository.myRole.collect { role ->
                state.update { it.copy(canWrite = role.canWrite) }
            }
        }
        appointmentId?.let { id ->
            viewModelScope.launch {
                appointmentRepository.observe(AppointmentId(id)).collect { appointment ->
                    if (appointment == null) {
                        state.update { it.copy(loaded = true, missing = true) }
                    } else {
                        state.update { current ->
                            if (current.prefilled) {
                                current.copy(loaded = true)
                            } else {
                                current.from(appointment).copy(
                                    loaded = true,
                                    prefilled = true,
                                    isEdit = true,
                                )
                            }
                        }
                    }
                }
            }
        }
        followUpOfArg?.let { id ->
            viewModelScope.launch {
                appointmentRepository.observe(AppointmentId(id)).collect { source ->
                    if (source == null) {
                        state.update { it.copy(loaded = true, missing = true) }
                    } else {
                        state.update { current ->
                            if (current.prefilled) {
                                current.copy(loaded = true)
                            } else {
                                // "Schedule follow-up" pre-fills a new appointment
                                // from the earlier one, keeping today's date.
                                current.copy(
                                    loaded = true,
                                    prefilled = true,
                                    patientId = source.patientId.value,
                                    kind = source.kind,
                                    title = source.title.orEmpty(),
                                    place = source.place.orEmpty(),
                                    doctor = source.doctor.orEmpty(),
                                    followUpOf = source.id.value,
                                )
                            }
                        }
                        refreshRecentPlaces()
                    }
                }
            }
        }
    }

    fun onPatient(patientId: String) {
        state.update { it.copy(patientId = patientId) }
        refreshRecentPlaces()
    }

    fun onKind(kind: AppointmentKind) = state.update { it.copy(kind = kind) }
    fun onTitle(value: String) = state.update { it.copy(title = value) }
    fun onDate(value: LocalDate) = state.update { it.copy(date = value) }
    fun onTime(value: LocalTime) = state.update { it.copy(time = value) }
    fun onDuration(value: String) = state.update { it.copy(durationMinutes = value) }
    fun onPlace(value: String) = state.update { it.copy(place = value) }
    fun onDoctor(value: String) = state.update { it.copy(doctor = value) }

    /**
     * Adds or removes one "Before going" chip.
     *
     * [phrase] is the chip's label in the phone's language, so the stored
     * preparation keeps writing the words the person actually sees; the phrase
     * is matched back on edit in either language.
     */
    fun onPreparationTagToggled(tag: PreparationTag, phrase: String) = state.update { current ->
        val tags = if (tag in current.preparationTags) {
            current.preparationTags - tag
        } else {
            current.preparationTags + (tag to phrase)
        }
        current.copy(preparationTags = tags)
    }

    /** The free text that goes into the same preparation as the chips. */
    fun onPreparationText(value: String) = state.update { it.copy(preparationFreeText = value) }

    fun onNotes(value: String) = state.update { it.copy(notes = value) }
    fun onCompanion(userId: String?) = state.update { it.copy(companionUserId = userId) }

    fun save() {
        val current = state.value
        if (current.saving || !current.canWrite) return

        val patientId = current.patientId
        if (patientId == null) {
            state.update { it.copy(errors = setOf(AppointmentField.PATIENT)) }
            return
        }

        val durationText = current.durationMinutes.trim()
        val duration = if (durationText.isEmpty()) null else durationText.toIntOrNull()
        if (durationText.isNotEmpty() && duration == null) {
            state.update { it.copy(errors = setOf(AppointmentField.DURATION)) }
            return
        }

        val appointment = Appointment(
            id = AppointmentId(appointmentId ?: UUID.randomUUID().toString()),
            patientId = PatientId(patientId),
            kind = current.kind,
            startsLocal = LocalDateTime.of(current.date, current.time),
            timeZone = ZoneId.of(current.timeZone),
            title = current.title.trim().takeIf { it.isNotEmpty() },
            durationMinutes = duration,
            place = current.place.trim().takeIf { it.isNotEmpty() },
            doctor = current.doctor.trim().takeIf { it.isNotEmpty() },
            preparation = composePreparation(current.preparationTags, current.preparationFreeText),
            notes = current.notes.trim().takeIf { it.isNotEmpty() },
            companionUserId = current.companionUserId,
            followUpOf = current.followUpOf?.let { AppointmentId(it) },
        )

        val validation = appointment.validate()
        if (!validation.isValid) {
            state.update { it.copy(errors = validation.errors) }
            return
        }

        state.update { it.copy(saving = true, errors = emptySet(), saveError = null) }
        viewModelScope.launch {
            val result = if (appointmentId == null) {
                appointmentRepository.create(appointment)
            } else {
                appointmentRepository.update(appointment)
            }
            state.update { currentState ->
                when (result) {
                    is AppointmentsResult.Success -> currentState.copy(saving = false, saved = true)
                    is AppointmentsResult.Failure -> when (val error = result.error) {
                        is AppointmentsError.Invalid -> currentState.copy(
                            saving = false,
                            errors = error.fields,
                        )

                        else -> currentState.copy(saving = false, saveError = error.toUiError())
                    }
                }
            }
        }
    }

    /**
     * Refreshes the recently used places: the latest past appointments of the
     * chosen patient (or of the whole circle while no patient is chosen).
     *
     * It is derived from what the form already observes — the agenda — so no
     * extra storage or query is needed.
     */
    private fun refreshRecentPlaces() {
        state.update { current -> current.copy(recentPlaces = recentPlaces(current.patientId)) }
    }

    private fun recentPlaces(patientId: String?): List<String> {
        val instant = now()
        return agendaEntries
            .filter { patientId == null || it.appointment.patientId.value == patientId }
            .filter { it.appointment.startInstant().isBefore(instant) }
            .sortedByDescending { it.appointment.startInstant() }
            .mapNotNull { it.appointment.place?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .take(RECENT_PLACE_COUNT)
    }

    /** The order every member picker uses: me first, then by name. */
    private companion object {
        val MemberOrder: Comparator<CircleMember> =
            compareBy({ it.isMe.not() }, { it.displayName?.lowercase() ?: "" })

        /** How many recently used places the "Where" section offers. */
        const val RECENT_PLACE_COUNT = 3
    }
}

/** One patient the form can pick. */
data class PatientOption(val id: String, val name: String)

/** The form's inputs and status. */
data class AppointmentFormUiState(
    val isEdit: Boolean = false,
    val loaded: Boolean = false,
    /** The row the form edits or follows up does not exist locally. */
    val missing: Boolean = false,
    /** At least one source row was applied; later emissions must not overwrite edits. */
    val prefilled: Boolean = false,
    val patientId: String? = null,
    val kind: AppointmentKind = AppointmentKind.CONSULTATION,
    val title: String = "",
    val date: LocalDate = LocalDate.MIN,
    val time: LocalTime = LocalTime.of(9, 0),
    val timeZone: String = ZoneId.systemDefault().id,
    val durationMinutes: String = "",
    val place: String = "",
    val doctor: String = "",
    /** The "Before going" chips, each with the phrase as it will be stored. */
    val preparationTags: Map<PreparationTag, String> = emptyMap(),
    /** Preparation text that is not one of the chips. */
    val preparationFreeText: String = "",
    val notes: String = "",
    val companionUserId: String? = null,
    val followUpOf: String? = null,
    /** Up to three places from this patient's (or the circle's) past appointments. */
    val recentPlaces: List<String> = emptyList(),
    val patients: List<PatientOption> = emptyList(),
    val members: List<CircleMember> = emptyList(),
    val canWrite: Boolean = false,
    val errors: Set<AppointmentField> = emptySet(),
    val saveError: AppointmentsUiError? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
) {

    /** True when the form came from a patient's tab and the patient is fixed. */
    val patientFixed: Boolean get() = isEdit || followUpOf != null || patientId != null
}

/** Fills the form from a stored appointment (edit). */
private fun AppointmentFormUiState.from(appointment: Appointment): AppointmentFormUiState {
    val preparation = parsePreparation(appointment.preparation)
    return copy(
        patientId = appointment.patientId.value,
        kind = appointment.kind,
        title = appointment.title.orEmpty(),
        date = appointment.localDate,
        time = appointment.localTime,
        timeZone = appointment.timeZone.id,
        durationMinutes = appointment.durationMinutes?.toString().orEmpty(),
        place = appointment.place.orEmpty(),
        doctor = appointment.doctor.orEmpty(),
        preparationTags = preparation.tags,
        preparationFreeText = preparation.freeText,
        notes = appointment.notes.orEmpty(),
        companionUserId = appointment.companionUserId,
        followUpOf = appointment.followUpOf?.value,
    )
}
