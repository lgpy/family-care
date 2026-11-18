package io.github.lgpy.familycare.feature.appointments.reminders

import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.feature.appointments.data.AppointmentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Keeps the appointment alarms in step with the data while the app is running
 * (docs/domain/appointments.md "Reminders").
 *
 * Triggers:
 * - app start: one immediate reschedule, before any user action;
 * - any change in appointments, patients, memberships or the session. The flows
 *   are debounced by two seconds so a multi-row sync or an edit produces one
 *   pass, not one per row.
 *
 * Boot, time/time-zone changes, app updates and the daily safety alarm are
 * broadcasts, handled by [AppointmentRescheduleReceiver].
 *
 * The appointments flow is the Agenda's own source, cancelled rows included, so
 * a cancellation re-plans (and drops that reminder) instead of waiting for the
 * daily alarm. Its fingerprint is the entries' `hashCode`, which covers every
 * field the reminders depend on — including `starts_local`, `time_zone`,
 * `companion_user_id` and `cancelled_at`, none of which changes the row count.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class AppointmentReminderCoordinator(
    private val scope: CoroutineScope,
    private val scheduler: AppointmentReminderScheduler,
    private val appointmentRepository: AppointmentRepository,
    private val patientRepository: PatientRepository,
    private val careCircleRepository: CareCircleRepository,
    private val authRepository: AuthRepository,
) {

    fun start() {
        scope.launch {
            runCatching { scheduler.reschedule() }
            combine(
                appointmentRepository.observeAgenda(includeCancelled = true),
                patientRepository.patients,
                careCircleRepository.myRole,
                careCircleRepository.members,
                authRepository.state,
            ) { appointments, patients, role, members, auth ->
                Trigger(
                    appointments = appointments.hashCode(),
                    patientCount = patients.size,
                    role = role,
                    memberCount = members.size,
                    auth = auth.toString(),
                )
            }
                .debounce(DEBOUNCE_MILLIS)
                .collect {
                    runCatching { scheduler.reschedule() }
                }
        }
    }

    /** Only used to make every contributing flow part of the combined trigger. */
    private data class Trigger(
        val appointments: Int,
        val patientCount: Int,
        val role: Any?,
        val memberCount: Int,
        val auth: String,
    )

    private companion object {
        const val DEBOUNCE_MILLIS = 2_000L
    }
}
