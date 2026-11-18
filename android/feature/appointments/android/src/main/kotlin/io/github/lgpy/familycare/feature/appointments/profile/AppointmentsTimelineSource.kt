package io.github.lgpy.familycare.feature.appointments.profile

import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.patient.TimelineEntry
import io.github.lgpy.familycare.core.ui.patient.TimelineKind
import io.github.lgpy.familycare.core.ui.patient.TimelineSource
import io.github.lgpy.familycare.core.ui.patient.TimelineText
import io.github.lgpy.familycare.feature.appointments.AppointmentsRoutes
import io.github.lgpy.familycare.feature.appointments.data.AppointmentRepository
import io.github.lgpy.familycare.feature.appointments.domain.Appointment
import io.github.lgpy.familycare.feature.appointments.ui.labelRes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The appointments module's contribution to the patient's History tab
 * (docs/domain/appointments.md "Screens"; D29): one entry per appointment, past
 * and upcoming, at its start instant.
 *
 * Text is localized here, through the [TimelineText] the app builds from its
 * `Resources`, so the shell's History tab needs to know nothing about
 * appointments (D5). The title is the appointment's own title, or the localized
 * kind when it has none; the detail is the place and doctor. A cancelled
 * appointment stays in the history, marked as cancelled, as the domain says.
 * Every entry opens the appointment's detail screen.
 */
class AppointmentsTimelineSource(
    private val appointmentRepository: AppointmentRepository,
    private val text: TimelineText,
) : TimelineSource {

    override fun entries(patientId: PatientId): Flow<List<TimelineEntry>> =
        appointmentRepository.observeForPatient(patientId).map { appointments ->
            appointments.map { entryFor(it) }.sortedByDescending { it.at }
        }

    private fun entryFor(appointment: Appointment): TimelineEntry {
        val title = appointment.title ?: text.get(appointment.kind.labelRes())
        val detail = listOfNotNull(
            appointment.place,
            appointment.doctor,
            appointment.cancelledAt?.let { text.get(R.string.appointments_cancelled) },
        )
            .takeIf { it.isNotEmpty() }
            ?.joinToString(DETAIL_SEPARATOR)

        return TimelineEntry(
            id = "appointment:${appointment.id.value}",
            at = appointment.startInstant(),
            title = title,
            detail = detail,
            kind = TimelineKind.APPOINTMENT,
            route = AppointmentsRoutes.appointmentDetail(appointment.id.value),
        )
    }

    private companion object {
        /** The separator the other timeline sources use between two facts. */
        const val DETAIL_SEPARATOR = " · "
    }
}
