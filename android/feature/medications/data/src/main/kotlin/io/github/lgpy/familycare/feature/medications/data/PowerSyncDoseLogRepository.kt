package io.github.lgpy.familycare.feature.medications.data

import com.powersync.PowerSyncDatabase
import com.powersync.db.getString
import io.github.lgpy.familycare.core.data.circle.circleAccess
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.canWrite
import io.github.lgpy.familycare.feature.medications.domain.DoseLogEntry
import io.github.lgpy.familycare.feature.medications.domain.DoseLogEntryId
import io.github.lgpy.familycare.feature.medications.domain.DoseOutcome
import io.github.lgpy.familycare.feature.medications.domain.DoseSlot
import io.github.lgpy.familycare.feature.medications.domain.DoseSlotId
import io.github.lgpy.familycare.feature.medications.domain.DoseSlotRules
import io.github.lgpy.familycare.feature.medications.domain.DoseStatus
import io.github.lgpy.familycare.feature.medications.domain.DoseStatusRules
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.RegimenId
import io.github.lgpy.familycare.feature.medications.domain.SkipReason
import io.github.lgpy.familycare.feature.medications.domain.TimeSlotGroup
import io.github.lgpy.familycare.feature.medications.domain.groupTimeSlots
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * [DoseLogRepository] over the local, encrypted PowerSync database.
 *
 * Entries are append-only (D6): the only write is an INSERT, one row per
 * medication and slot, even when the UI confirms a whole time group in one tap.
 * A confirmation of N slots is one write transaction, so it is one upload
 * transaction and either all N entries exist locally or none do.
 *
 * [dosesToday] is derived, never stored: slots come from the active regimens
 * (`DoseSlotRules`), status from the entries and the clock (`DoseStatusRules`).
 * It re-emits when data changes and on a minute tick, which is why "due" turns
 * into "overdue" (and then "missed") without the screen polling.
 *
 * @param now The clock, injectable for tests.
 * @param ticks The "time passed" signal for [dosesToday]; defaults to one
 *   emission per minute. A test passes a single emission (or a test flow) to
 *   make the status deterministic.
 * @param newId Generates entry ids; injectable so a test can assert queued rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PowerSyncDoseLogRepository(
    private val database: PowerSyncDatabase,
    private val authState: StateFlow<AuthState>,
    private val now: () -> Instant = { Instant.now() },
    private val ticks: Flow<Instant> = minuteTicks(now),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : DoseLogRepository {

    private val signedInUserId: Flow<String?> = authState
        .map { (it as? AuthState.SignedIn)?.userId }
        .distinctUntilChanged()

    override fun observeEntries(
        regimenIds: Collection<RegimenId>,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<DoseLogEntry>> {
        val ids = regimenIds.map { it.value }.distinct()
        if (ids.isEmpty() || to.isBefore(from)) return flowOf(emptyList())

        val placeholders = ids.joinToString(separator = ",") { "?" }
        val sql = SELECT_DOSE_LOG_PREFIX + " ($placeholders)\n" + DOSE_LOG_WINDOW_SUFFIX
        val parameters = ids + listOf(from.toString(), to.toString())

        return database.watch(sql, parameters) { cursor -> cursor.toDoseLogRow() }
            .map { rows -> rows.toEntries().sortedWith(ENTRY_ORDER) }
    }

    override fun observeMedicationEntries(medicationId: MedicationId): Flow<List<DoseLogEntry>> =
        database.watch(SELECT_MEDICATION_DOSE_LOG, listOf(medicationId.value)) { cursor ->
            cursor.toDoseLogRow()
        }.map { rows -> rows.toEntries().sortedWith(ENTRY_ORDER) }

    override val dosesToday: Flow<List<TodayDoseGroup>> = signedInUserId.flatMapLatest { userId ->
        if (userId == null) {
            flowOf(emptyList())
        } else {
            database.watch(SELECT_ACTIVE_MEDICATIONS, listOf(userId)) { cursor ->
                cursor.toMedicationRow()
            }.map { rows -> rows.toMedicationWithRegimens() }
                .distinctUntilChanged()
                .flatMapLatest { medications -> todayGroupsFlow(medications) }
        }
    }

    override suspend fun confirm(
        group: TimeSlotGroup,
        skipped: Map<DoseSlotId, SkipReason>,
        givenBy: GivenBy,
        note: String?,
    ): MedsResult<Unit> {
        val userId = writableUserId() ?: return denied()

        val access = database.circleAccess(userId) ?: return denied()
        if (!group.slots.all { database.medicationInCircle(it.medicationId, access.circleId.value) }) {
            return MedsResult.Failure(MedsError.NotAllowed)
        }

        val at = now()
        val entries = group.slots.map { slot ->
            val reason = skipped[slot.slotId]
            val outcome = if (reason == null) DoseOutcome.TAKEN else DoseOutcome.SKIPPED

            DoseLogEntry(
                id = DoseLogEntryId(newId()),
                regimenId = slot.regimenId,
                medicationId = slot.medicationId,
                scheduledLocal = slot.scheduledLocal,
                outcome = outcome,
                actualAt = at,
                confirmedBy = userId,
                skipReason = reason,
                note = note,
                actualQuantity = if (outcome == DoseOutcome.TAKEN) slot.quantity else null,
                givenByUserId = givenBy.userIdOrNull(userId),
                givenByOther = givenBy is GivenBy.SomeoneElse,
                givenByName = (givenBy as? GivenBy.SomeoneElse)?.name,
            ).normalized()
        }

        val errors = entries.flatMap { it.validate().errors }.toSet()
        if (errors.isNotEmpty()) return MedsResult.Failure(MedsError.Invalid(errors))

        return writeInOneTransaction(Unit) { tx ->
            entries.forEach { tx.execute(INSERT_DOSE_LOG, doseLogParameters(it)) }
        }
    }

    override suspend fun correct(
        entry: DoseLogEntry,
        outcome: DoseOutcome,
        skipReason: SkipReason?,
        actualQuantity: BigDecimal?,
        givenBy: GivenBy,
        note: String?,
    ): MedsResult<DoseLogEntryId> {
        val userId = writableUserId() ?: return denied()

        val access = database.circleAccess(userId) ?: return denied()
        if (!database.medicationInCircle(entry.medicationId, access.circleId.value)) {
            return MedsResult.Failure(MedsError.NotAllowed)
        }

        val correction = DoseLogEntry(
            id = DoseLogEntryId(newId()),
            // A correction carries the slot identity: the server refuses one
            // that names a different regimen or scheduled time.
            regimenId = entry.regimenId,
            medicationId = entry.medicationId,
            scheduledLocal = entry.scheduledLocal,
            outcome = outcome,
            actualAt = now(),
            confirmedBy = userId,
            skipReason = skipReason,
            note = note,
            actualQuantity = actualQuantity,
            givenByUserId = givenBy.userIdOrNull(userId),
            givenByOther = givenBy is GivenBy.SomeoneElse,
            givenByName = (givenBy as? GivenBy.SomeoneElse)?.name,
            correctsEntryId = entry.id,
        ).normalized()

        val validation = correction.validate()
        if (!validation.isValid) {
            return MedsResult.Failure(MedsError.Invalid(validation.errors))
        }

        return writeInOneTransaction(correction.id) { tx ->
            tx.execute(INSERT_DOSE_LOG, doseLogParameters(correction))
        }
    }

    // --- Doses today ---------------------------------------------------------

    /**
     * The entries watch plus the tick, combined into groups.
     *
     * The SQL window is fixed for a whole day (device-local today ± 2 days, wide
     * enough for every regimen zone), so the watch is not re-subscribed every
     * minute; the tick only re-derives the statuses. At midnight the window moves
     * to the new day.
     */
    private fun todayGroupsFlow(medications: List<MedicationWithRegimens>): Flow<List<TodayDoseGroup>> {
        val regimenIds = medications.flatMap { it.regimens }.map { it.id.value }.distinct()
        if (regimenIds.isEmpty()) return flowOf(emptyList())

        val placeholders = regimenIds.joinToString(separator = ",") { "?" }
        val sql = SELECT_DOSE_LOG_PREFIX + " ($placeholders)\n" + DOSE_LOG_WINDOW_SUFFIX

        val window = ticks
            .map { instant -> instant.atZone(ZoneId.systemDefault()).toLocalDate() }
            .distinctUntilChanged()
            .flatMapLatest { day ->
                database.watch(
                    sql,
                    regimenIds + listOf(day.minusDays(2).toString(), day.plusDays(2).toString()),
                ) { cursor -> cursor.toDoseLogRow() }
                    .map { rows -> rows.toEntries() }
            }

        return combine(window, ticks) { entries, instant ->
            todayGroups(medications, entries, instant)
        }
    }

    private fun todayGroups(
        medications: List<MedicationWithRegimens>,
        entries: List<DoseLogEntry>,
        at: Instant,
    ): List<TodayDoseGroup> {
        val slots = mutableListOf<DoseSlot>()
        medications.forEach { item ->
            item.regimens.forEach { regimen ->
                val today = at.atZone(regimen.timeZone).toLocalDate()
                slots += DoseSlotRules.doseSlots(listOf(regimen), today, today)
            }
        }
        if (slots.isEmpty()) return emptyList()

        val patientOf = medications.associate { it.medication.id to it.medication.patientId }
        val settings = medications.associate { it.patient.patientId to it.patient }
        val entriesBySlot = entries.groupBy { it.slotId }

        return groupTimeSlots(slots) { medicationId -> patientOf[medicationId] }.mapNotNull { group ->
            val patient = settings[group.patientId] ?: return@mapNotNull null
            val statuses = group.slots.map { slot ->
                DoseStatusRules.doseStatus(
                    slot = slot,
                    entries = entriesBySlot[slot.slotId].orEmpty(),
                    now = at,
                    escalationDelayMinutes = patient.escalationDelayMinutes,
                    missedWindowMinutes = patient.missedWindowMinutes,
                )
            }
            TodayDoseGroup(group = group, statuses = statuses)
        }
    }

    // --- Helpers -------------------------------------------------------------

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

    private suspend fun PowerSyncDatabase.medicationInCircle(medicationId: MedicationId, circleId: String): Boolean =
        getOptional(
            SELECT_MEDICATION_IN_CIRCLE,
            listOf(medicationId.value, circleId),
        ) { it.getString("id") } != null

    /**
     * One write transaction per user action (one upload transaction): a group
     * confirmation either writes all its entries or none.
     *
     * The callback is not a suspend function (the SDK's API), so [block] only
     * uses the transaction's own execute calls.
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

/** `given_by_user_id`, or null when the dose was given by someone without the app. */
private fun GivenBy.userIdOrNull(me: String): String? = when (this) {
    GivenBy.Me -> me
    is GivenBy.Member -> userId
    is GivenBy.SomeoneElse -> null
}

/** Oldest slot first, then oldest entry, then id: deterministic for any UI. */
private val ENTRY_ORDER: Comparator<DoseLogEntry> = compareBy(
    { it.scheduledLocal },
    { it.actualAt },
    { it.id.value },
)

/**
 * One emission now, then at the start of each minute.
 *
 * A minute is the finest resolution a dose status changes at (the escalation
 * delay and missed window are whole minutes), so this is exactly as often as the
 * derived status can change without new data.
 */
internal fun minuteTicks(now: () -> Instant): Flow<Instant> = flow {
    while (true) {
        val current = now()
        emit(current)
        val intoMinute = current.toEpochMilli() % MILLIS_PER_MINUTE
        // `delay(long)` is milliseconds; the next minute boundary is at most
        // one minute away.
        delay(MILLIS_PER_MINUTE - intoMinute)
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
