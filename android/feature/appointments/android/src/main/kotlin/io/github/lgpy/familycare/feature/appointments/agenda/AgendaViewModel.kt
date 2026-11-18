package io.github.lgpy.familycare.feature.appointments.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.feature.appointments.data.AppointmentEntry
import io.github.lgpy.familycare.feature.appointments.data.AppointmentRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * The Agenda tab (docs/domain/appointments.md "Screens"): every patient's
 * appointments grouped by day, a filter per patient and a "Show past" toggle.
 *
 * The split between upcoming and past uses each appointment's own instant (its
 * local start in its own zone), not the phone's wall clock, so an appointment
 * abroad is past when it is past there. Grouping uses the appointment's local
 * date for the same reason.
 *
 * The ViewModel knows nothing about Android: the screen turns [AgendaDay.date]
 * into "Today", "Tomorrow" or a localized weekday.
 */
class AgendaViewModel(
    appointmentRepository: AppointmentRepository,
    patientRepository: PatientRepository,
    careCircleRepository: CareCircleRepository,
    private val clock: () -> Instant = { Instant.now() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())

    val uiState: StateFlow<AgendaUiState> = combine(
        appointmentRepository.observeAgenda(includeCancelled = true),
        patientRepository.patients,
        careCircleRepository.myRole,
        local,
    ) { entries, patients, role, state ->
        val now = clock()
        val visible = entries.filter { entry ->
            state.patientId == null || entry.appointment.patientId.value == state.patientId
        }
        val upcoming = visible.filter { !it.appointment.startInstant().isBefore(now) }
        val past = visible.filter { it.appointment.startInstant().isBefore(now) }

        AgendaUiState(
            loaded = true,
            canWrite = role.canWrite,
            today = now.atZone(zone).toLocalDate(),
            patients = patients.map { PatientFilter(id = it.id.value, name = it.displayName) },
            selectedPatientId = state.patientId,
            showPast = state.showPast,
            upcomingDays = upcoming.groupByDay(ascending = true),
            pastDays = if (state.showPast) past.groupByDay(ascending = false) else emptyList(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AgendaUiState(),
    )

    /** Filters the list to one patient, or back to every patient with null. */
    fun selectPatient(patientId: String?) {
        local.update { it.copy(patientId = patientId) }
    }

    /** Shows or hides the past appointments below the upcoming ones. */
    fun togglePast() {
        local.update { it.copy(showPast = !it.showPast) }
    }

    private data class LocalState(
        val patientId: String? = null,
        val showPast: Boolean = false,
    )
}

/** One day of the agenda: its local date and that day's appointments. */
data class AgendaDay(
    val date: LocalDate,
    val entries: List<AppointmentEntry>,
)

/** One chip of the patient filter. */
data class PatientFilter(val id: String, val name: String)

/** What the Agenda shows. */
data class AgendaUiState(
    val loaded: Boolean = false,
    /** Whether the signed-in role may create and edit (owner or caregiver). */
    val canWrite: Boolean = false,
    /** "Today" in the phone's zone, so the screen can label the first groups. */
    val today: LocalDate = LocalDate.MIN,
    val patients: List<PatientFilter> = emptyList(),
    val selectedPatientId: String? = null,
    val showPast: Boolean = false,
    val upcomingDays: List<AgendaDay> = emptyList(),
    val pastDays: List<AgendaDay> = emptyList(),
) {
    /** Whether there is nothing to show at all. */
    val isEmpty: Boolean get() = loaded && upcomingDays.isEmpty() && pastDays.isEmpty()
}

/** Groups entries by their local date, earliest day first (or latest, for past). */
internal fun List<AppointmentEntry>.groupByDay(ascending: Boolean): List<AgendaDay> {
    val days = groupBy { it.appointment.localDate }
        .map { (date, entries) ->
            AgendaDay(
                date = date,
                entries = entries.sortedWith(
                    compareBy({ it.appointment.localTime }, { it.appointment.id.value }),
                ),
            )
        }
    return if (ascending) days.sortedBy { it.date } else days.sortedByDescending { it.date }
}
