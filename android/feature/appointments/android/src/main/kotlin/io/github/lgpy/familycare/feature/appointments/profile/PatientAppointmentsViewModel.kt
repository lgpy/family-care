package io.github.lgpy.familycare.feature.appointments.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.feature.appointments.agenda.AgendaDay
import io.github.lgpy.familycare.feature.appointments.agenda.groupByDay
import io.github.lgpy.familycare.feature.appointments.data.AppointmentEntry
import io.github.lgpy.familycare.feature.appointments.data.AppointmentRepository
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The Appointments tab of the patient profile (D29): that patient's upcoming
 * appointments, then the past ones, cancelled ones included and marked.
 */
class PatientAppointmentsViewModel(
    patientId: String,
    appointmentRepository: AppointmentRepository,
    patientRepository: PatientRepository,
    careCircleRepository: CareCircleRepository,
    private val clock: () -> Instant = { Instant.now() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    val uiState: StateFlow<PatientAppointmentsUiState> = combine(
        appointmentRepository.observeForPatient(PatientId(patientId)),
        patientRepository.observe(PatientId(patientId)),
        careCircleRepository.myRole,
    ) { appointments, patient, role ->
        val now = clock()
        val entries = appointments.map { AppointmentEntry(it, patient?.displayName.orEmpty()) }
        val upcoming = entries.filter { !it.appointment.startInstant().isBefore(now) }
        val past = entries.filter { it.appointment.startInstant().isBefore(now) }

        PatientAppointmentsUiState(
            loaded = true,
            patientName = patient?.displayName,
            patientMissing = patient == null,
            canWrite = patient != null && role.canWrite(patient),
            today = now.atZone(zone).toLocalDate(),
            upcomingDays = upcoming.groupByDay(ascending = true),
            pastDays = past.groupByDay(ascending = false),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PatientAppointmentsUiState(),
    )
}

/** What the patient's Appointments tab shows. */
data class PatientAppointmentsUiState(
    val loaded: Boolean = false,
    val patientName: String? = null,
    val patientMissing: Boolean = false,
    val canWrite: Boolean = false,
    val today: java.time.LocalDate = java.time.LocalDate.MIN,
    val upcomingDays: List<AgendaDay> = emptyList(),
    val pastDays: List<AgendaDay> = emptyList(),
) {
    val isEmpty: Boolean get() = upcomingDays.isEmpty() && pastDays.isEmpty()
}
