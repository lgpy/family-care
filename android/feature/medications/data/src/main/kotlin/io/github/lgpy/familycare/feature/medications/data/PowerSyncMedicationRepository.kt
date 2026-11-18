package io.github.lgpy.familycare.feature.medications.data

import com.powersync.PowerSyncDatabase
import com.powersync.db.getString
import io.github.lgpy.familycare.core.data.circle.CircleAccess
import io.github.lgpy.familycare.core.data.circle.circleAccess
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.feature.medications.domain.DoseTime
import io.github.lgpy.familycare.feature.medications.domain.Medication
import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.Regimen
import io.github.lgpy.familycare.feature.medications.domain.RegimenChange
import io.github.lgpy.familycare.feature.medications.domain.RegimenId
import io.github.lgpy.familycare.feature.medications.domain.RegimenRules
import io.github.lgpy.familycare.feature.medications.domain.RegimenStop
import io.github.lgpy.familycare.feature.medications.domain.ScheduleType
import io.github.lgpy.familycare.feature.medications.domain.StockMovement
import io.github.lgpy.familycare.feature.medications.domain.StockMovementId
import io.github.lgpy.familycare.feature.medications.domain.StockMovementKind
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
 * [MedicationRepository] over the local, encrypted PowerSync database.
 *
 * Reads are PowerSync watches over the module's own views; writes are SQLite
 * writes inside one `writeTransaction` per user action, so a medication with its
 * first regimen (or an ended version with its successor) is one upload
 * transaction and can never half-apply.
 *
 * Ids are generated on the phone with [UUID], which is what lets a medication be
 * created offline. Every write checks the signed-in role first
 * ([MemberRole.canWrite]) and that the row belongs to the current circle; a
 * viewer gets [MedsError.NotAllowed] and nothing is queued.
 *
 * @param now The clock used for `stopped_at`; injectable for tests.
 * @param newId Generates regimen ids; injectable so a test can assert the queued
 *   rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PowerSyncMedicationRepository(
    private val database: PowerSyncDatabase,
    private val authState: StateFlow<AuthState>,
    private val now: () -> Instant = { Instant.now() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : MedicationRepository {

    private val signedInUserId: Flow<String?> = authState
        .map { (it as? AuthState.SignedIn)?.userId }
        .distinctUntilChanged()

    override fun observe(patientId: PatientId): Flow<List<MedicationWithRegimens>> =
        signedInUserId.flatMapLatest { userId ->
            if (userId == null) {
                flowOf(emptyList())
            } else {
                database.watch(
                    SELECT_PATIENT_MEDICATIONS,
                    listOf(patientId.value, userId),
                ) { cursor -> cursor.toMedicationRow() }
                    .map { rows -> rows.toMedicationWithRegimens() }
            }
        }

    override val activeMedications: Flow<List<MedicationWithRegimens>> =
        signedInUserId.flatMapLatest { userId ->
            if (userId == null) {
                flowOf(emptyList())
            } else {
                database.watch(SELECT_ACTIVE_MEDICATIONS, listOf(userId)) { cursor ->
                    cursor.toMedicationRow()
                }.map { rows -> rows.toMedicationWithRegimens() }
            }
        }

    override fun observeMedication(medicationId: MedicationId): Flow<MedicationWithRegimens?> =
        signedInUserId.flatMapLatest { userId ->
            if (userId == null) {
                flowOf(null)
            } else {
                database.watch(
                    SELECT_MEDICATION,
                    listOf(medicationId.value, userId),
                ) { cursor -> cursor.toMedicationRow() }
                    .map { rows -> rows.toMedicationWithRegimens().firstOrNull() }
            }
        }

    override suspend fun create(
        medication: Medication,
        firstRegimen: Regimen,
        openingQuantity: BigDecimal?,
    ): MedsResult<MedicationId> {
        val normalized = medication.normalized()
        val regimen = firstRegimen.normalized()

        val openingMovement = openingQuantity?.let { quantity ->
            StockMovement(
                id = StockMovementId(newId()),
                medicationId = normalized.id,
                kind = StockMovementKind.COUNT,
                quantity = quantity,
                occurredAt = now(),
            ).normalized()
        }

        val errors = normalized.validate().errors + regimen.validate().errors +
            openingMovement?.validate()?.errors.orEmpty() +
            if (regimen.medicationId != normalized.id) {
                // The regimen is stored under the medication being created; a
                // mismatch would attach it to the wrong one.
                setOf(MedicationField.REGIMEN_SCHEDULE)
            } else {
                emptySet()
            }
        if (errors.isNotEmpty()) return MedsResult.Failure(MedsError.Invalid(errors))

        val access = writerAccess() ?: return denied()
        if (!database.patientInCircle(normalized.patientId, access)) {
            return MedsResult.Failure(MedsError.NotAllowed)
        }
        if (database.getOptional(SELECT_MEDICATION_PRESENT, listOf(normalized.id.value)) { it.getString("id") } != null) {
            return MedsResult.Failure(MedsError.AlreadyExists)
        }

        return writeInOneTransaction(normalized.id) { tx ->
            tx.execute(INSERT_MEDICATION, medicationParameters(normalized))
            tx.execute(INSERT_REGIMEN, regimenParameters(regimen))
            // D28: the opening balance is part of creation, so the medication
            // can never exist for a moment without the stock it was counted in.
            openingMovement?.let { tx.execute(INSERT_STOCK_MOVEMENT, stockMovementParameters(it)) }
        }
    }

    override suspend fun update(medication: Medication): MedsResult<MedicationId> {
        val normalized = medication.normalized()

        val validation = normalized.validate()
        if (!validation.isValid) {
            return MedsResult.Failure(MedsError.Invalid(validation.errors))
        }

        val access = writerAccess() ?: return denied()
        val owner = database.medicationOwner(normalized.id)
            ?: return MedsResult.Failure(MedsError.NotFound)
        if (owner != access.circleId.value) return MedsResult.Failure(MedsError.NotAllowed)

        return writeInOneTransaction(normalized.id) { tx ->
            tx.execute(UPDATE_MEDICATION, medicationUpdateParameters(normalized))
        }
    }

    override suspend fun changeRegimen(
        current: Regimen,
        effectiveFrom: LocalDate,
        times: List<DoseTime>,
        scheduleType: ScheduleType,
        weekdays: Int?,
        intervalDays: Int?,
        timeZone: ZoneId,
    ): MedsResult<RegimenId> {
        val change = RegimenRules.changeRegimen(
            current = current,
            effectiveFrom = effectiveFrom,
            newId = RegimenId(newId()),
            times = times,
            scheduleType = scheduleType,
            weekdays = weekdays,
            intervalDays = intervalDays,
            timeZone = timeZone,
        )
        val changed = when (change) {
            is RegimenChange.Rejected -> return MedsResult.Failure(MedsError.Invalid(change.errors))
            is RegimenChange.Changed -> change
        }

        val access = writerAccess() ?: return denied()
        val owner = database.regimenOwner(current.id) ?: return MedsResult.Failure(MedsError.NotFound)
        if (owner.second != access.circleId.value || owner.first != current.medicationId.value) {
            return MedsResult.Failure(MedsError.NotAllowed)
        }

        val lastDay = changed.ended.endDate ?: return MedsResult.Failure(MedsError.Unknown)

        return writeInOneTransaction(changed.started.id) { tx ->
            tx.execute(UPDATE_REGIMEN_END_DATE, listOf(formatDate(lastDay), current.id.value))
            tx.execute(INSERT_REGIMEN, regimenParameters(changed.started))
        }
    }

    override suspend fun stop(
        medication: Medication,
        regimen: Regimen,
        lastDay: LocalDate,
    ): MedsResult<Unit> {
        val stopped = when (val result = RegimenRules.stop(regimen, lastDay)) {
            is RegimenStop.Rejected -> return MedsResult.Failure(MedsError.Invalid(result.errors))
            is RegimenStop.Stopped -> result.regimen
        }
        val endDate = stopped.endDate ?: return MedsResult.Failure(MedsError.Unknown)

        val access = writerAccess() ?: return denied()
        val owner = database.regimenOwner(regimen.id) ?: return MedsResult.Failure(MedsError.NotFound)
        if (owner.second != access.circleId.value || owner.first != medication.id.value) {
            return MedsResult.Failure(MedsError.NotAllowed)
        }

        val stoppedAt = now().toString()
        return writeInOneTransaction(Unit) { tx ->
            tx.execute(UPDATE_REGIMEN_END_DATE, listOf(formatDate(endDate), regimen.id.value))
            tx.execute(
                UPDATE_MEDICATION_STOPPED_AT,
                listOf(stoppedAt, medication.id.value),
            )
        }
    }

    // --- Helpers -------------------------------------------------------------

    /** The signed-in writer's circle access, or null when nobody may write. */
    private suspend fun writerAccess(): CircleAccess? {
        val userId = signedInUserId.first() ?: return null
        val access = database.circleAccess(userId) ?: return null
        return access.takeIf { it.role.canWrite }
    }

    /** Distinguishes "no circle" from "a role that may not write". */
    private suspend fun denied(): MedsResult.Failure {
        val userId = signedInUserId.first()
        if (userId == null) return MedsResult.Failure(MedsError.NotAllowed)
        return MedsResult.Failure(
            if (database.circleAccess(userId) == null) MedsError.NoCircle else MedsError.NotAllowed,
        )
    }

    private suspend fun PowerSyncDatabase.patientInCircle(
        patientId: PatientId,
        access: CircleAccess,
    ): Boolean = getOptional(
        SELECT_PATIENT_IN_CIRCLE,
        listOf(patientId.value, access.circleId.value),
    ) { it.getString("id") } != null

    /** The circle a medication belongs to, or null when it is not local. */
    private suspend fun PowerSyncDatabase.medicationOwner(medicationId: MedicationId): String? =
        getOptional(SELECT_MEDICATION_OWNER, listOf(medicationId.value)) { it.getString("circle_id") }

    /** The medication and circle of a regimen, or null when it is not local. */
    private suspend fun PowerSyncDatabase.regimenOwner(regimenId: RegimenId): Pair<String, String>? =
        getOptional(SELECT_REGIMEN_OWNER, listOf(regimenId.value)) { cursor ->
            cursor.getString("medication_id") to cursor.getString("circle_id")
        }

    /**
     * Runs [block] in one write transaction and reports [value] on success.
     *
     * One transaction means one upload transaction: a medication and its first
     * regimen (or an ended version and its successor) can never half-apply, and
     * the queue sends them together. A local database error becomes
     * [MedsError.Unknown]; cancellation is never swallowed.
     *
     * The callback is not a suspend function (that is the SDK's API), so
     * [block] may only use the transaction's own execute/query calls — which is
     * all a local write needs.
     */
    private suspend fun <T> writeInOneTransaction(
        value: T,
        block: (com.powersync.db.internal.PowerSyncTransaction) -> Unit,
    ): MedsResult<T> = try {
        database.writeTransaction { transaction -> block(transaction) }
        MedsResult.Success(value)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        MedsResult.Failure(MedsError.Unknown)
    }
}
