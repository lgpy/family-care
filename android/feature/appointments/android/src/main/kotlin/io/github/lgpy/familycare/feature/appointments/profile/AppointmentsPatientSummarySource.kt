package io.github.lgpy.familycare.feature.appointments.profile

import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.patient.PatientSummaryIcon
import io.github.lgpy.familycare.core.ui.patient.PatientSummaryItem
import io.github.lgpy.familycare.core.ui.patient.PatientSummarySource
import io.github.lgpy.familycare.core.ui.patient.PatientSummaryText
import io.github.lgpy.familycare.feature.appointments.data.AppointmentRepository
import io.github.lgpy.familycare.feature.appointments.domain.Appointment
import io.github.lgpy.familycare.feature.appointments.ui.labelRes
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The appointments module's line on the Patients list: the next upcoming
 * appointment, as "Mon · blood test".
 *
 * Cancelled and past appointments are skipped, so the row never advertises a
 * visit that is not going to happen. The weekday and the title are localized
 * here, through [text] and the phone's [locale], so the app's Patients tab needs
 * to know nothing about appointments (D5).
 *
 * [now] is injected so the "upcoming" boundary is testable.
 */
class AppointmentsPatientSummarySource(
    private val appointmentRepository: AppointmentRepository,
    private val text: PatientSummaryText,
    private val now: () -> Instant = Instant::now,
    private val locale: Locale = Locale.getDefault(),
) : PatientSummarySource {

    /** After medications' summary (10). */
    override val order: Int = ORDER

    override fun summary(patientId: PatientId): Flow<List<PatientSummaryItem>> =
        appointmentRepository.observeForPatient(patientId).map { appointments ->
            val instant = now()
            appointments
                .filter { !it.isCancelled && !it.isPast(instant) }
                .minByOrNull { it.startInstant() }
                ?.let { listOf(nextAppointmentItem(it)) }
                .orEmpty()
        }

    private fun nextAppointmentItem(appointment: Appointment): PatientSummaryItem =
        PatientSummaryItem(
            icon = PatientSummaryIcon.EVENT,
            text = text.get(
                R.string.patients_summary_next_appointment,
                appointment.localDate.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                appointment.title ?: text.get(appointment.kind.labelRes()),
            ),
        )

    companion object {

        /** After medications' summary (10), before patient info's (30). */
        const val ORDER: Int = 20
    }
}
