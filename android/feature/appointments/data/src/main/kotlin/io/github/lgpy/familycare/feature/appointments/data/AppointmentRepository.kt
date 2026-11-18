package io.github.lgpy.familycare.feature.appointments.data

import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.feature.appointments.domain.Appointment
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentId
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * The circle's appointments (docs/domain/appointments.md, D29).
 *
 * Reads come from the encrypted local database and writes are local too: the
 * row appears immediately, works in airplane mode (D3), and PowerSync uploads it
 * when the network returns. Domain validation runs before the write, so a
 * mistake is an [AppointmentsError.Invalid] a form can show rather than a
 * constraint error the upload queue could only report as permanent. The
 * signed-in role is checked first: a viewer gets [AppointmentsError.NotAllowed]
 * and nothing is written; deleting needs an owner, as [MemberRole.canManage]
 * says.
 *
 * Cancelling is [cancel], which stamps `cancelled_at` and keeps the row
 * ([delete] is a separate, owner-only action).
 *
 * Ids are generated on the phone ([AppointmentId] supplied by the caller or by
 * the repository), which is what lets an appointment be created offline.
 */
interface AppointmentRepository {

    /**
     * One patient's appointments, earliest first, cancelled ones included (the
     * list shows them marked). Emits again on every local or synced change.
     */
    fun observeForPatient(patientId: PatientId): Flow<List<Appointment>>

    /**
     * Every appointment of the signed-in user's circles that starts at or after
     * [from], with the patient's display name, earliest first.
     *
     * Cancelled appointments are excluded unless [includeCancelled] is true.
     */
    fun observeUpcoming(
        from: Instant,
        includeCancelled: Boolean = false,
    ): Flow<List<AppointmentEntry>>

    /**
     * Every appointment of the signed-in user's circles, past and upcoming, with
     * the patient's display name — the Agenda's source, whose "Show past" toggle
     * splits the list. Cancelled ones are excluded unless [includeCancelled].
     */
    fun observeAgenda(includeCancelled: Boolean = false): Flow<List<AppointmentEntry>>

    /** One appointment of the signed-in user's circles, or null when it is not local. */
    fun observe(id: AppointmentId): Flow<Appointment?>

    /**
     * Stores a new appointment.
     *
     * The patient must be in the current circle, the companion (when set) must
     * be an owner or caregiver of that circle, and the follow-up (when set) must
     * be another appointment of the same patient. The signed-in role must be
     * able to write.
     *
     * @return the stored id, or [AppointmentsError.Invalid] (with
     *   [io.github.lgpy.familycare.feature.appointments.domain.AppointmentField]s),
     *   [AppointmentsError.NotAllowed], [AppointmentsError.NoCircle],
     *   [AppointmentsError.AlreadyExists].
     */
    suspend fun create(appointment: Appointment): AppointmentsResult<AppointmentId>

    /**
     * Updates the user-editable columns of an existing appointment in place.
     * `patient_id` cannot change; a different one is
     * [AppointmentsError.Invalid] with
     * [io.github.lgpy.familycare.feature.appointments.domain.AppointmentField.PATIENT].
     *
     * @return lookup errors as for [create], with [AppointmentsError.NotFound]
     *   when the row is not local.
     */
    suspend fun update(appointment: Appointment): AppointmentsResult<AppointmentId>

    /**
     * Cancels an appointment by stamping `cancelled_at` (now, from the
     * repository's clock). The row and its history stay; a cancelled appointment
     * has no reminders.
     */
    suspend fun cancel(id: AppointmentId): AppointmentsResult<Unit>

    /** Deletes an appointment. Owner only ([MemberRole.canManage]). */
    suspend fun delete(id: AppointmentId): AppointmentsResult<Unit>
}
