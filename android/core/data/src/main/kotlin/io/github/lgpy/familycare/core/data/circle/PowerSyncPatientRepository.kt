package io.github.lgpy.familycare.core.data.circle

import com.powersync.PowerSyncDatabase
import com.powersync.db.getString
import io.github.lgpy.familycare.core.data.sync.UploadFailure
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.CareCircleId
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.PatientRules
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.core.domain.circle.canWrite
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
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * [PatientRepository] over the local, encrypted PowerSync database.
 *
 * [save] only writes SQLite: the patient is visible immediately, works in
 * airplane mode, and PowerSync uploads the change when the network returns
 * (D3). The domain validation runs first, so an out-of-range delay is a
 * [CoreError.Invalid] the form can show instead of a constraint failure the
 * upload queue can only mark as permanent.
 *
 * Ids are generated on the phone with [UUID], which is what lets a patient be
 * created offline; the row is a normal insert once the upload reaches PostgREST.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PowerSyncPatientRepository(
    private val database: PowerSyncDatabase,
    private val authState: StateFlow<AuthState>,
    private val rpc: PatientRpc,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Instant = { Clock.System.now() },
) : PatientRepository {

    private val signedInUserId: Flow<String?> = authState
        .map { (it as? AuthState.SignedIn)?.userId }
        .distinctUntilChanged()

    override val patients: Flow<List<Patient>> = signedInUserId.flatMapLatest { userId ->
        if (userId == null) {
            flowOf(emptyList())
        } else {
            database.watch(SELECT_PATIENTS, listOf(userId)) { cursor -> cursor.toPatientRow() }
                .map { rows -> rows.mapNotNull { it.toPatient() } }
        }
    }

    override val archivedPatients: Flow<List<Patient>> =
        signedInUserId.flatMapLatest { userId ->
            if (userId == null) {
                flowOf(emptyList())
            } else {
                database.watch(SELECT_ARCHIVED_PATIENTS, listOf(userId)) { cursor ->
                    cursor.toPatientRow()
                }.map { rows -> rows.mapNotNull { it.toPatient() } }
            }
        }

    override fun observe(patientId: PatientId): Flow<Patient?> =
        signedInUserId.flatMapLatest { userId ->
            if (userId == null) {
                flowOf(null)
            } else {
                database.watch(SELECT_PATIENT, listOf(patientId.value, userId)) { cursor ->
                    cursor.toPatientRow()
                }.map { rows -> rows.firstOrNull()?.toPatient() }
            }
        }

    override fun newPatient(
        circleId: CareCircleId,
        displayName: String,
        linkedUserId: String?,
        chosenPrimaryCaregiverId: String?,
        reminderPolicy: ReminderPolicy,
        escalationDelayMinutes: Int,
        missedWindowMinutes: Int,
        agreementNote: String?,
    ): Patient = PatientRules.newPatient(
        id = PatientId(newId()),
        circleId = circleId,
        displayName = displayName,
        linkedUserId = linkedUserId,
        chosenPrimaryCaregiverId = chosenPrimaryCaregiverId,
        reminderPolicy = reminderPolicy,
        escalationDelayMinutes = escalationDelayMinutes,
        missedWindowMinutes = missedWindowMinutes,
        agreementNote = agreementNote,
    )

    override suspend fun save(patient: Patient): CoreResult<PatientId> {
        val normalized = patient.normalized()

        val validation = normalized.validate()
        if (!validation.isValid) {
            return CoreResult.Failure(CoreError.Invalid(validation.errors))
        }

        val userId = signedInUserId.first()
            ?: return CoreResult.Failure(CoreError.NotAllowed)

        val circleId = database.getOptional(SELECT_CURRENT_CIRCLE_ID, listOf(userId)) { cursor ->
            cursor.getString("circle_id")
        } ?: return CoreResult.Failure(CoreError.NoCircle)

        val role = database.getOptional(SELECT_MY_ROLE, listOf(userId)) { cursor ->
            cursor.toRoleRow()
        }?.toRole() ?: return CoreResult.Failure(CoreError.NotAllowed)

        if (!role.canWrite) {
            return CoreResult.Failure(CoreError.NotAllowed)
        }
        if (normalized.circleId.value != circleId) {
            // The form was built for another circle (a stale screen): never write
            // a patient into a circle the signed-in user cannot see.
            return CoreResult.Failure(CoreError.NotAllowed)
        }

        return try {
            database.writeTransaction { tx ->
                val exists = tx.getOptional(SELECT_PATIENT_PRESENT, listOf(normalized.id.value)) { cursor ->
                    cursor.getString("id")
                } != null
                if (exists) {
                    tx.execute(
                        sql = UPDATE_PATIENT,
                        parameters = listOf(
                            normalized.displayName,
                            normalized.linkedUserId,
                            normalized.reminderPolicy.serverValue,
                            normalized.primaryCaregiverId,
                            normalized.escalationDelayMinutes,
                            normalized.missedWindowMinutes,
                            normalized.agreementNote,
                            normalized.archivedAt?.toString(),
                            normalized.id.value,
                        ),
                    )
                } else {
                    tx.execute(
                        sql = INSERT_PATIENT,
                        parameters = listOf(
                            normalized.id.value,
                            normalized.circleId.value,
                            normalized.displayName,
                            normalized.linkedUserId,
                            normalized.reminderPolicy.serverValue,
                            normalized.primaryCaregiverId,
                            normalized.escalationDelayMinutes,
                            normalized.missedWindowMinutes,
                            normalized.agreementNote,
                            normalized.archivedAt?.toString(),
                        ),
                    )
                }
            }
            CoreResult.Success(normalized.id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            CoreResult.Failure(CoreError.Unknown)
        }
    }

    override suspend fun archive(patientId: PatientId): CoreResult<PatientId> =
        setArchived(patientId, archivedAt = now().toString())

    override suspend fun unarchive(patientId: PatientId): CoreResult<PatientId> =
        setArchived(patientId, archivedAt = null)

    /**
     * Sets or clears `archived_at` locally: one UPDATE of one column, which
     * PowerSync uploads as a PATCH. The rest of the row is untouched, so
     * archiving never revalidates (or rewrites) a patient's form values, and an
     * offline archive syncs when the network returns.
     */
    private suspend fun setArchived(
        patientId: PatientId,
        archivedAt: String?,
    ): CoreResult<PatientId> {
        val userId = signedInUserId.first()
            ?: return CoreResult.Failure(CoreError.NotAllowed)

        val role = database.getOptional(SELECT_MY_ROLE, listOf(userId)) { cursor ->
            cursor.toRoleRow()
        }?.toRole() ?: return CoreResult.Failure(CoreError.NotAllowed)

        // D30: owners only, the same rule as `core.can(patient, 'manage')`.
        if (role != MemberRole.OWNER) {
            return CoreResult.Failure(CoreError.NotAllowed)
        }

        val present = database.getOptional(
            SELECT_PATIENT,
            listOf(patientId.value, userId),
        ) { cursor -> cursor.getString("id") } != null
        if (!present) {
            return CoreResult.Failure(CoreError.NotFound)
        }

        return try {
            database.writeTransaction { tx ->
                tx.execute(
                    sql = UPDATE_PATIENT_ARCHIVED_AT,
                    parameters = listOf(archivedAt, patientId.value),
                )
            }
            CoreResult.Success(patientId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            CoreResult.Failure(CoreError.Unknown)
        }
    }

    override suspend fun erase(patientId: PatientId): CoreResult<PatientId> {
        val userId = signedInUserId.first()
            ?: return CoreResult.Failure(CoreError.NotAllowed)

        // Owners only; the server checks it too, and it is the authority. A
        // local check keeps a caregiver from starting a call that can only fail.
        val role = database.getOptional(SELECT_MY_ROLE, listOf(userId)) { cursor ->
            cursor.toRoleRow()
        }?.toRole() ?: return CoreResult.Failure(CoreError.NotAllowed)
        if (role != MemberRole.OWNER) {
            return CoreResult.Failure(CoreError.NotAllowed)
        }

        val present = database.getOptional(
            SELECT_PATIENT,
            listOf(patientId.value, userId),
        ) { cursor -> cursor.getString("id") } != null
        if (!present) {
            return CoreResult.Failure(CoreError.NotFound)
        }

        return try {
            rpc.erasePatient(patientId.value)
            // The local row goes away when the sync brings the delete. A local
            // DELETE would be queued and uploaded as a direct `core.patients`
            // delete, which the server refuses from M7 on.
            CoreResult.Success(patientId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            CoreResult.Failure(
                careCircleErrorOf(UploadFailure.of(error), error.message, RpcCall.ERASE_PATIENT),
            )
        }
    }
}
