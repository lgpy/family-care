package io.github.lgpy.familycare.feature.medications.data

import com.powersync.PowerSyncDatabase
import com.powersync.db.getString
import io.github.lgpy.familycare.core.data.circle.circleAccess
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.StockMovement
import io.github.lgpy.familycare.feature.medications.domain.StockMovementId
import io.github.lgpy.familycare.feature.medications.domain.StockMovementKind
import io.github.lgpy.familycare.feature.medications.domain.StockRules
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * [StockRepository] over the local, encrypted PowerSync database (D28).
 *
 * The ledger and the summary are derived, never stored: an overview is the
 * medication (with its regimens) plus its movements plus its dose-log entries,
 * passed through
 * [io.github.lgpy.familycare.feature.medications.domain.StockRules.summary]. A
 * dose confirmation therefore changes the stock with no second write and no
 * server round trip, and a late-syncing or corrected dose simply changes the
 * sum.
 *
 * "Today" comes from [clock], read in the zone of the medication's latest
 * regimen version: a patient abroad keeps their own wall clock, exactly as the
 * dose slots do. [ticks] re-derives the summary as time passes (stock runs out
 * at midnight even when nothing is written).
 *
 * @param clock The clock the ledger timestamps and "today" come from; injectable
 *   for tests.
 * @param ticks The "time passed" signal; defaults to one emission per minute,
 *   the same tick the dose log uses.
 * @param newId Generates movement ids; injectable so a test can assert queued
 *   rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PowerSyncStockRepository(
    private val database: PowerSyncDatabase,
    private val authState: StateFlow<AuthState>,
    private val medicationRepository: MedicationRepository,
    private val doseLogRepository: DoseLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val ticks: Flow<Instant> = minuteTicks { clock.instant() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : StockRepository {

    private val signedInUserId: Flow<String?> = authState
        .map { (it as? AuthState.SignedIn)?.userId }
        .distinctUntilChanged()

    override fun observeMovements(medicationId: MedicationId): Flow<List<StockMovement>> =
        signedInUserId.flatMapLatest { userId ->
            if (userId == null) {
                flowOf(emptyList())
            } else {
                database.watch(
                    SELECT_MEDICATION_STOCK_MOVEMENTS,
                    listOf(medicationId.value),
                ) { cursor -> cursor.toStockMovementRow() }
                    .map { rows -> rows.toMovements().sortedWith(MOVEMENT_ORDER) }
            }
        }

    override fun observeOverview(medicationId: MedicationId): Flow<StockOverview?> {
        val data = combine(
            medicationRepository.observeMedication(medicationId),
            observeMovements(medicationId),
            doseLogRepository.observeMedicationEntries(medicationId),
        ) { item, movements, entries -> OverviewData(item, movements, entries) }

        return data.combine(ticks) { snapshot, instant -> snapshot.overview(instant) }
    }

    /**
     * Every active medication's overview.
     *
     * The medication ids are watched as a distinct list, so a change to a
     * medication's columns (a new threshold, say) re-emits its own overview
     * rather than re-subscribing every watch.
     */
    override val overviews: Flow<List<StockOverview>> =
        medicationRepository.activeMedications
            .map { items -> items.map { it.id }.sortedBy { it.value } }
            .distinctUntilChanged()
            .flatMapLatest { ids ->
                if (ids.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(ids.map { observeOverview(it) }) { overviews ->
                        overviews.filterNotNull().sortedWith(OVERVIEW_ORDER)
                    }
                }
            }

    override suspend fun recordCount(
        medicationId: MedicationId,
        quantity: BigDecimal,
        note: String?,
    ): MedsResult<StockMovementId> = record(medicationId, StockMovementKind.COUNT, quantity, note)

    override suspend fun recordRefill(
        medicationId: MedicationId,
        quantity: BigDecimal,
    ): MedsResult<StockMovementId> = record(medicationId, StockMovementKind.REFILL, quantity, null)

    override suspend fun recordDiscard(
        medicationId: MedicationId,
        quantity: BigDecimal,
        reason: String,
    ): MedsResult<StockMovementId> =
        record(medicationId, StockMovementKind.DISCARD, quantity, reason)

    // --- Helpers -------------------------------------------------------------

    private fun OverviewData.overview(instant: Instant): StockOverview? {
        val item = item ?: return null
        val zone = item.regimens.maxByOrNull { it.startDate }?.timeZone ?: clock.zone
        val today = instant.atZone(zone).toLocalDate()
        return StockOverview(
            item = item,
            movements = movements,
            summary = StockRules.summary(
                medication = item.medication,
                regimens = item.regimens,
                movements = movements,
                entries = entries,
                today = today,
            ),
        )
    }

    /** Appends one movement, after the write permission and the domain rules. */
    private suspend fun record(
        medicationId: MedicationId,
        kind: StockMovementKind,
        quantity: BigDecimal,
        reason: String?,
    ): MedsResult<StockMovementId> {
        val userId = writableUserId() ?: return denied()

        val access = database.circleAccess(userId) ?: return denied()
        if (!database.medicationInCircle(medicationId, access.circleId.value)) {
            return MedsResult.Failure(MedsError.NotAllowed)
        }

        val movement = StockMovement(
            id = StockMovementId(newId()),
            medicationId = medicationId,
            kind = kind,
            quantity = quantity,
            occurredAt = clock.instant(),
            reason = reason,
        ).normalized()

        val validation = movement.validate()
        if (!validation.isValid) return MedsResult.Failure(MedsError.Invalid(validation.errors))

        return writeInOneTransaction(movement.id) { tx ->
            tx.execute(INSERT_STOCK_MOVEMENT, stockMovementParameters(movement))
        }
    }

    /** The signed-in user id when they may write, else null. */
    private suspend fun writableUserId(): String? {
        val userId = signedInUserId.first() ?: return null
        val access = database.circleAccess(userId) ?: return null
        return userId.takeIf { access.role.canWrite }
    }

    private suspend fun denied(): MedsResult.Failure {
        val userId = signedInUserId.first()
        if (userId == null) return MedsResult.Failure(MedsError.NotAllowed)
        return MedsResult.Failure(
            if (database.circleAccess(userId) == null) MedsError.NoCircle else MedsError.NotAllowed,
        )
    }

    private suspend fun PowerSyncDatabase.medicationInCircle(
        medicationId: MedicationId,
        circleId: String,
    ): Boolean = getOptional(
        SELECT_MEDICATION_IN_CIRCLE,
        listOf(medicationId.value, circleId),
    ) { it.getString("id") } != null

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

    /** The three watch emissions a summary needs, before "today" is applied. */
    private class OverviewData(
        val item: MedicationWithRegimens?,
        val movements: List<StockMovement>,
        val entries: List<io.github.lgpy.familycare.feature.medications.domain.DoseLogEntry>,
    )
}

/** Oldest movement first, then id: deterministic for any UI. */
private val MOVEMENT_ORDER: Comparator<StockMovement> = compareBy(
    { it.occurredAt },
    { it.id.value },
)

/** Patient, then medication name, then id: the shopping list's order. */
private val OVERVIEW_ORDER: Comparator<StockOverview> = compareBy(
    { it.patientName.lowercase() },
    { it.medication.name.lowercase() },
    { it.medication.id.value },
)

/** One movement as the `stock_movements` columns the phone writes. */
internal fun stockMovementParameters(movement: StockMovement): List<Any?> = listOf(
    movement.id.value,
    movement.medicationId.value,
    movement.kind.serverValue,
    formatQuantity(movement.quantity),
    movement.reason,
    movement.occurredAt.toString(),
)
