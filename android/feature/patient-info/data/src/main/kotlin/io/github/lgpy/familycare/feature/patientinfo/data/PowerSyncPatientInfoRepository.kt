package io.github.lgpy.familycare.feature.patientinfo.data

import com.powersync.PowerSyncDatabase
import com.powersync.db.getString
import io.github.lgpy.familycare.core.data.circle.CircleAccess
import io.github.lgpy.familycare.core.data.circle.circleAccess
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfo
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
 * [PatientInfoRepository] over the local, encrypted PowerSync database.
 *
 * Reads are PowerSync watches over the module's own table joined to the core's
 * `patients`/`memberships`; a write is one SQLite statement inside one
 * `writeTransaction`, so a save is one upload transaction.
 *
 * Ids are the patient's (`id = patient_id`, D29), which is what lets two phones
 * create the row offline and converge. Every write checks the signed-in role
 * first ([canWrite]) and that the patient belongs to the current circle; a
 * viewer gets [PatientInfoError.NotAllowed] and nothing is queued.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PowerSyncPatientInfoRepository(
    private val database: PowerSyncDatabase,
    private val authState: StateFlow<AuthState>,
) : PatientInfoRepository {

    private val signedInUserId: Flow<String?> = authState
        .map { (it as? AuthState.SignedIn)?.userId }
        .distinctUntilChanged()

    override fun observe(patientId: PatientId): Flow<PatientInfo?> =
        signedInUserId.flatMapLatest { userId ->
            if (userId == null) {
                flowOf(null)
            } else {
                database.watch(
                    SELECT_PATIENT_INFO,
                    listOf(patientId.value, userId),
                ) { cursor -> cursor.toPatientInfoRow() }
                    .map { rows -> rows.toPatientInfo() }
            }
        }

    override suspend fun save(patientId: PatientId, info: PatientInfo): PatientInfoResult<Unit> {
        val normalized = info.normalized()
        validateAndAuthorize(patientId, normalized)?.let { return it }

        return writeInOneTransaction { transaction ->
            if (transaction.currentRow(patientId) != null) {
                val values = patientInfoColumnValues(normalized)
                transaction.execute(
                    updatePatientInfoSql(values.keys.toList()),
                    patientInfoUpdateParameters(values, patientId.value),
                )
            } else {
                transaction.execute(INSERT_PATIENT_INFO, patientInfoInsertParameters(patientId.value, normalized))
            }
            null
        }
    }

    override suspend fun saveChanges(
        patientId: PatientId,
        loaded: PatientInfo?,
        edited: PatientInfo,
    ): PatientInfoResult<Unit> {
        val normalized = edited.normalized()
        validateAndAuthorize(patientId, normalized)?.let { return it }
        val snapshot = (loaded ?: PatientInfo()).normalized()

        return writeInOneTransaction { transaction ->
            // Read in the same transaction as the write: what another caregiver
            // synced since the form opened is merged, never overwritten.
            val current = transaction.currentRow(patientId)
            if (current == null) {
                transaction.execute(INSERT_PATIENT_INFO, patientInfoInsertParameters(patientId.value, normalized))
                return@writeInOneTransaction null
            }

            val plan = planPatientInfoUpdate(snapshot, normalized, current.normalized())
            when {
                !plan.validation.isValid -> PatientInfoError.Invalid(plan.validation)

                plan.columns.isEmpty() -> null

                else -> {
                    transaction.execute(
                        updatePatientInfoSql(plan.columns.keys.toList()),
                        patientInfoUpdateParameters(plan.columns, patientId.value),
                    )
                    null
                }
            }
        }
    }

    // --- Helpers -------------------------------------------------------------

    /**
     * The checks every save starts with: the domain rules, then the signed-in
     * writer's role and the patient's circle. A failure result, or null to go on.
     */
    private suspend fun validateAndAuthorize(
        patientId: PatientId,
        normalized: PatientInfo,
    ): PatientInfoResult.Failure? {
        val validation = normalized.validate()
        if (!validation.isValid) {
            return PatientInfoResult.Failure(PatientInfoError.Invalid(validation))
        }

        val access = writerAccess() ?: return denied()
        if (!database.patientInCircle(patientId, access)) {
            return PatientInfoResult.Failure(PatientInfoError.NotAllowed)
        }
        return null
    }

    /** The patient's row as it is inside this transaction, or null when there is none. */
    private fun com.powersync.db.internal.PowerSyncTransaction.currentRow(
        patientId: PatientId,
    ): PatientInfo? = getOptional(
        SELECT_PATIENT_INFO_BY_ID,
        listOf(patientId.value),
    ) { cursor -> cursor.toPatientInfoRow() }?.toPatientInfo()

    /** The signed-in writer's circle access, or null when nobody may write. */
    private suspend fun writerAccess(): CircleAccess? {
        val userId = signedInUserId.first() ?: return null
        val access = database.circleAccess(userId) ?: return null
        return access.takeIf { it.role.canWrite }
    }

    /** Distinguishes "no circle" from "a role that may not write". */
    private suspend fun denied(): PatientInfoResult.Failure {
        val userId = signedInUserId.first()
        if (userId == null) return PatientInfoResult.Failure(PatientInfoError.NotAllowed)
        return PatientInfoResult.Failure(
            if (database.circleAccess(userId) == null) {
                PatientInfoError.NoCircle
            } else {
                PatientInfoError.NotAllowed
            },
        )
    }

    private suspend fun PowerSyncDatabase.patientInCircle(
        patientId: PatientId,
        access: CircleAccess,
    ): Boolean = getOptional(
        SELECT_PATIENT_IN_CIRCLE,
        listOf(patientId.value, access.circleId.value),
    ) { it.getString("id") } != null

    /**
     * Runs [block] in one write transaction, or reports [PatientInfoError.Unknown].
     *
     * One transaction means one upload transaction. [block] returns the error
     * to report, or null once it has written; a local database error becomes
     * [PatientInfoError.Unknown]; cancellation is never swallowed.
     */
    private suspend fun writeInOneTransaction(
        block: (com.powersync.db.internal.PowerSyncTransaction) -> PatientInfoError?,
    ): PatientInfoResult<Unit> = try {
        val error = database.writeTransaction { transaction -> block(transaction) }
        if (error == null) PatientInfoResult.Success(Unit) else PatientInfoResult.Failure(error)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        PatientInfoResult.Failure(PatientInfoError.Unknown)
    }
}
