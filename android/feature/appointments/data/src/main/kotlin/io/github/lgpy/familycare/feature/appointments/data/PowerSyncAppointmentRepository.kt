package io.github.lgpy.familycare.feature.appointments.data

import com.powersync.PowerSyncDatabase
import com.powersync.db.getString
import io.github.lgpy.familycare.core.data.circle.CircleAccess
import io.github.lgpy.familycare.core.data.circle.circleAccess
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.CareCircleId
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.canManage
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.feature.appointments.domain.Appointment
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentField
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentId
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * [AppointmentRepository] over the local, encrypted PowerSync database.
 *
 * Reads are PowerSync watches over the module's own query; writes are SQLite
 * writes inside one `writeTransaction` per user action, so one action is one
 * upload transaction and can never half-apply.
 *
 * Ids are generated on the phone with [UUID], which is what lets an appointment
 * be created offline. Every write checks the signed-in role first
 * ([MemberRole.canWrite], or [MemberRole.canManage] for a delete) and that the
 * row belongs to the current circle; a viewer gets
 * [AppointmentsError.NotAllowed] and nothing is queued.
 *
 * @param now The clock used for `cancelled_at`; injectable for tests.
 * @param newId Generates appointment ids; injectable so a test can assert the
 *   queued rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PowerSyncAppointmentRepository(
    private val database: PowerSyncDatabase,
    private val authState: StateFlow<AuthState>,
    private val now: () -> Instant = { Instant.now() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : AppointmentRepository {

    private val signedInUserId: Flow<String?> = authState
        .map { (it as? AuthState.SignedIn)?.userId }
        .distinctUntilChanged()

    override fun observeForPatient(patientId: PatientId): Flow<List<Appointment>> =
        signedInUserId.flatMapLatest { userId ->
            if (userId == null) {
                flowOf(emptyList())
            } else {
                database.watch(
                    SELECT_PATIENT_APPOINTMENTS,
                    listOf(patientId.value, userId),
                ) { cursor -> cursor.toAppointmentRow() }
                    .map { rows -> rows.toAppointments() }
            }
        }

    override fun observeUpcoming(
        from: Instant,
        includeCancelled: Boolean,
    ): Flow<List<AppointmentEntry>> =
        observeAgenda(includeCancelled = includeCancelled).map { entries ->
            entries
                .filter { !it.appointment.startInstant().isBefore(from) }
                .sortedWith(ENTRY_ORDER)
        }

    override fun observeAgenda(includeCancelled: Boolean): Flow<List<AppointmentEntry>> =
        signedInUserId.flatMapLatest { userId ->
            if (userId == null) {
                flowOf(emptyList())
            } else {
                database.watch(SELECT_CIRCLE_APPOINTMENTS, listOf(userId)) { cursor ->
                    cursor.toAppointmentRow()
                }.map { rows ->
                    rows.toEntries()
                        .filter { includeCancelled || !it.appointment.isCancelled }
                        .sortedWith(ENTRY_ORDER)
                }
            }
        }

    override fun observe(id: AppointmentId): Flow<Appointment?> =
        signedInUserId.flatMapLatest { userId ->
            if (userId == null) {
                flowOf(null)
            } else {
                database.watch(
                    SELECT_APPOINTMENT,
                    listOf(id.value, userId),
                ) { cursor -> cursor.toAppointmentRow() }
                    .map { rows -> rows.toAppointments().firstOrNull() }
            }
        }

    override suspend fun create(appointment: Appointment): AppointmentsResult<AppointmentId> {
        val normalized = appointment.normalized()

        val validation = normalized.validate()
        if (!validation.isValid) {
            return AppointmentsResult.Failure(AppointmentsError.Invalid(validation.errors))
        }

        val access = writerAccess() ?: return denied()
        if (!database.patientInCircle(normalized.patientId, access)) {
            return AppointmentsResult.Failure(AppointmentsError.NotAllowed)
        }

        val related = relatedErrors(normalized, access.circleId, existingPatientId = normalized.patientId.value)
        if (related.isNotEmpty()) {
            return AppointmentsResult.Failure(AppointmentsError.Invalid(related))
        }

        if (database.getOptional(
                SELECT_APPOINTMENT_PRESENT,
                listOf(normalized.id.value),
            ) { it.getString("id") } != null
        ) {
            return AppointmentsResult.Failure(AppointmentsError.AlreadyExists)
        }

        return writeInOneTransaction(normalized.id) { tx ->
            tx.execute(INSERT_APPOINTMENT, appointmentParameters(normalized))
        }
    }

    override suspend fun update(appointment: Appointment): AppointmentsResult<AppointmentId> {
        val normalized = appointment.normalized()

        val validation = normalized.validate()
        if (!validation.isValid) {
            return AppointmentsResult.Failure(AppointmentsError.Invalid(validation.errors))
        }

        val access = writerAccess() ?: return denied()
        val owner = database.appointmentOwner(normalized.id)
            ?: return AppointmentsResult.Failure(AppointmentsError.NotFound)
        if (owner.second != access.circleId.value) {
            return AppointmentsResult.Failure(AppointmentsError.NotAllowed)
        }
        // patient_id is immutable; a form that edited it is refused before the
        // server trigger would refuse the upload.
        if (owner.first != normalized.patientId.value) {
            return AppointmentsResult.Failure(
                AppointmentsError.Invalid(setOf(AppointmentField.PATIENT)),
            )
        }

        val related = relatedErrors(normalized, access.circleId, existingPatientId = owner.first)
        if (related.isNotEmpty()) {
            return AppointmentsResult.Failure(AppointmentsError.Invalid(related))
        }

        return writeInOneTransaction(normalized.id) { tx ->
            tx.execute(UPDATE_APPOINTMENT, appointmentUpdateParameters(normalized))
        }
    }

    override suspend fun cancel(id: AppointmentId): AppointmentsResult<Unit> {
        val access = writerAccess() ?: return denied()
        val owner = database.appointmentOwner(id)
            ?: return AppointmentsResult.Failure(AppointmentsError.NotFound)
        if (owner.second != access.circleId.value) {
            return AppointmentsResult.Failure(AppointmentsError.NotAllowed)
        }

        val cancelledAt = now().toString()
        return writeInOneTransaction(Unit) { tx ->
            tx.execute(UPDATE_APPOINTMENT_CANCELLED_AT, listOf(cancelledAt, id.value))
        }
    }

    override suspend fun delete(id: AppointmentId): AppointmentsResult<Unit> {
        val access = managerAccess() ?: return denied(manage = true)
        val owner = database.appointmentOwner(id)
            ?: return AppointmentsResult.Failure(AppointmentsError.NotFound)
        if (owner.second != access.circleId.value) {
            return AppointmentsResult.Failure(AppointmentsError.NotAllowed)
        }

        return writeInOneTransaction(Unit) { tx ->
            tx.execute(DELETE_APPOINTMENT, listOf(id.value))
        }
    }

    // --- Helpers -------------------------------------------------------------

    /**
     * The companion and follow-up rules that need a database lookup:
     * - the companion must be an owner or caregiver of [circleId];
     * - the follow-up must be another appointment of [existingPatientId].
     *
     * Returns the [AppointmentField]s to mark, empty when both pass.
     */
    private suspend fun relatedErrors(
        appointment: Appointment,
        circleId: CareCircleId,
        existingPatientId: String,
    ): Set<AppointmentField> {
        val errors = mutableSetOf<AppointmentField>()

        appointment.companionUserId?.let { companion ->
            val role = database.getOptional(
                SELECT_MEMBER_ROLE,
                listOf(circleId.value, companion),
            ) { cursor -> cursor.getString("role") }?.let { MemberRole.fromServer(it) }
            if (role != MemberRole.OWNER && role != MemberRole.CAREGIVER) {
                errors += AppointmentField.COMPANION
            }
        }

        appointment.followUpOf?.let { followUp ->
            val patient = database.getOptional(
                SELECT_APPOINTMENT_PATIENT,
                listOf(followUp.value),
            ) { cursor -> cursor.getString("patient_id") }
            if (patient != existingPatientId) errors += AppointmentField.FOLLOW_UP
        }

        return errors
    }

    /** The signed-in writer's circle access, or null when nobody may write. */
    private suspend fun writerAccess(): CircleAccess? {
        val userId = signedInUserId.first() ?: return null
        val access = database.circleAccess(userId) ?: return null
        return access.takeIf { it.role.canWrite }
    }

    /** The signed-in owner's circle access, or null when the role may not delete. */
    private suspend fun managerAccess(): CircleAccess? {
        val userId = signedInUserId.first() ?: return null
        val access = database.circleAccess(userId) ?: return null
        return access.takeIf { it.role.canManage }
    }

    /** Distinguishes "no circle" from "a role that may not write (or manage)". */
    private suspend fun denied(manage: Boolean = false): AppointmentsResult.Failure {
        val userId = signedInUserId.first()
        if (userId == null) return AppointmentsResult.Failure(AppointmentsError.NotAllowed)
        val access = database.circleAccess(userId)
        if (access == null) return AppointmentsResult.Failure(AppointmentsError.NoCircle)
        val allowed = if (manage) access.role.canManage else access.role.canWrite
        return AppointmentsResult.Failure(
            if (allowed) AppointmentsError.Unknown else AppointmentsError.NotAllowed,
        )
    }

    private suspend fun PowerSyncDatabase.patientInCircle(
        patientId: PatientId,
        access: CircleAccess,
    ): Boolean = getOptional(
        SELECT_PATIENT_IN_CIRCLE,
        listOf(patientId.value, access.circleId.value),
    ) { it.getString("id") } != null

    /** The patient and circle of an appointment, or null when it is not local. */
    private suspend fun PowerSyncDatabase.appointmentOwner(
        appointmentId: AppointmentId,
    ): Pair<String, String>? = getOptional(
        SELECT_APPOINTMENT_OWNER,
        listOf(appointmentId.value),
    ) { cursor ->
        cursor.getString("patient_id") to cursor.getString("circle_id")
    }

    /**
     * Runs [block] in one write transaction and reports [value] on success.
     *
     * One transaction means one upload transaction. A local database error
     * becomes [AppointmentsError.Unknown]; cancellation is never swallowed.
     */
    private suspend fun <T> writeInOneTransaction(
        value: T,
        block: (com.powersync.db.internal.PowerSyncTransaction) -> Unit,
    ): AppointmentsResult<T> = try {
        database.writeTransaction { transaction -> block(transaction) }
        AppointmentsResult.Success(value)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        AppointmentsResult.Failure(AppointmentsError.Unknown)
    }

    private companion object {

        /**
         * Earliest start first. The instant (start in its own zone) is what
         * orders two appointments on different zones; the id breaks ties.
         */
        val ENTRY_ORDER: Comparator<AppointmentEntry> = compareBy(
            { it.appointment.startInstant() },
            { it.appointment.id.value },
        )
    }
}
