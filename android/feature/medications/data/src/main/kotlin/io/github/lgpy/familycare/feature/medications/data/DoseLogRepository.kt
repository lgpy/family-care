package io.github.lgpy.familycare.feature.medications.data

import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.feature.medications.domain.DoseLogEntry
import io.github.lgpy.familycare.feature.medications.domain.DoseLogEntryId
import io.github.lgpy.familycare.feature.medications.domain.DoseLogRules
import io.github.lgpy.familycare.feature.medications.domain.DoseOutcome
import io.github.lgpy.familycare.feature.medications.domain.DoseSlot
import io.github.lgpy.familycare.feature.medications.domain.DoseSlotId
import io.github.lgpy.familycare.feature.medications.domain.DoseStatus
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.RegimenId
import io.github.lgpy.familycare.feature.medications.domain.SkipReason
import io.github.lgpy.familycare.feature.medications.domain.TimeSlotGroup
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow

/**
 * Who physically gave (or skipped) a dose.
 *
 * The three cases are exclusive because the database enforces exactly that
 * (`dose_log_given_by_exclusive`, `dose_log_given_by_name_only_when_other`):
 * the signed-in user, another member of the circle, or someone without the app.
 * "Another member" is checked by the server, which knows the circle's
 * membership.
 */
sealed interface GivenBy {

    /** The person confirming (the signed-in user). The default. */
    data object Me : GivenBy

    /** Another member of the patient's circle, by `auth.users.id`. */
    data class Member(val userId: String) : GivenBy

    /** Someone outside the circle; the name is optional free text. */
    data class SomeoneElse(val name: String? = null) : GivenBy
}

/**
 * All of one patient's doses at one wall-clock time, with the derived status of
 * each. This is the "Doses today" card (docs/ux/screens.md §1-2): one tap
 * confirms the group, storage still gets one dose-log entry per slot.
 *
 * [statuses] is in [TimeSlotGroup.slots] order, so a screen can zip them.
 */
data class TodayDoseGroup(
    val group: TimeSlotGroup,
    val statuses: List<DoseStatus>,
) {

    /** The patient the group belongs to. */
    val patientId: PatientId get() = group.patientId

    /** The local date-time the group is due at, in the regimen's zone. */
    val scheduledLocal: LocalDateTime get() = group.scheduledLocal

    /** The slots to confirm, in display order. */
    val slots: List<DoseSlot> get() = group.slots

    /** Whether every slot has a taken or skipped entry. */
    val allConfirmed: Boolean
        get() = statuses.isNotEmpty() && statuses.all { it.isConfirmed }

    /** Whether at least one slot was confirmed independently twice. */
    val confirmedTwice: Boolean get() = statuses.any { it.confirmedTwice }

    /** The status of [slot], or null when it is not part of this group. */
    fun statusOf(slot: DoseSlot): DoseStatus? =
        statuses.firstOrNull { it.slot.slotId == slot.slotId }
}

/**
 * The append-only dose log (D6) and the derived "doses today" view.
 *
 * Every write is local and offline-first: an entry appears immediately, and
 * PowerSync uploads it later. Entries are never edited — [correct] inserts a new
 * entry that points at the wrong one, and [DoseLogRules.effectiveEntries] treats
 * the original as void. "Missed" is derived from the clock, never stored.
 */
interface DoseLogRepository {

    /**
     * The entries of [regimenIds] whose **local** scheduled date is between
     * [from] and [to], both inclusive, oldest slot first.
     *
     * Inclusive of the local date (not an instant) because a slot's identity is
     * the wall-clock time in the regimen's zone, and a DST change moves the
     * instant, never the slot.
     */
    fun observeEntries(
        regimenIds: Collection<RegimenId>,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<DoseLogEntry>>

    /** Every entry of one medication, full history, oldest slot first. */
    fun observeMedicationEntries(medicationId: MedicationId): Flow<List<DoseLogEntry>>

    /**
     * Today's doses of the current circle, grouped per patient and wall-clock
     * time, with each slot's [DoseStatus].
     *
     * Emits when the synced/local data changes **and** as time passes (a
     * minute tick), so a slot moves from upcoming to due to overdue without the
     * screen polling. "Today" is each regimen's own zone, so a patient abroad
     * keeps their own wall clock. Only active medications of non-archived
     * patients are included.
     */
    val dosesToday: Flow<List<TodayDoseGroup>>

    /**
     * Records one time-slot group: a TAKEN entry per slot, with the slot's
     * quantity, `actual_at` at the current time, `confirmed_by` the signed-in
     * user and the same [givenBy] for all of them — **and** a SKIPPED entry with
     * its [SkipReason] for every slot listed in [skipped], all in one write
     * transaction.
     *
     * A skip that applies to the whole group is [skipped] with every slot id.
     * Nothing is written when the role may not write, when a slot's medication
     * is not in the current circle, or when a domain rule rejects an entry.
     */
    suspend fun confirm(
        group: TimeSlotGroup,
        skipped: Map<DoseSlotId, SkipReason> = emptyMap(),
        givenBy: GivenBy = GivenBy.Me,
        note: String? = null,
    ): MedsResult<Unit>

    /**
     * Corrects [entry] with a new entry for the same slot
     * (`corrects_entry_id = entry.id`); the original stays and becomes void in
     * the derivation. The new entry may change the outcome, the quantity, the
     * skip reason, who gave it and the note.
     */
    suspend fun correct(
        entry: DoseLogEntry,
        outcome: DoseOutcome,
        skipReason: SkipReason? = null,
        actualQuantity: BigDecimal? = null,
        givenBy: GivenBy = GivenBy.Me,
        note: String? = null,
    ): MedsResult<DoseLogEntryId>

    /**
     * What the "already given?" warning shows before saving: the effective
     * entries of [slot] (corrections applied, oldest first). More than one means
     * the slot was confirmed twice — a double-dosing risk the UI must mention.
     */
    fun existingConfirmation(slot: DoseSlot, entries: List<DoseLogEntry>): List<DoseLogEntry> =
        DoseLogRules.existingConfirmation(slot, entries)
}
