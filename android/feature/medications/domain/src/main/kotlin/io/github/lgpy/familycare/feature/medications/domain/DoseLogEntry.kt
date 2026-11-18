package io.github.lgpy.familycare.feature.medications.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime

/** What happened to a dose. Stored lowercase (`taken`, `skipped`). */
enum class DoseOutcome(val serverValue: String) {
    TAKEN("taken"),
    SKIPPED("skipped"),
    ;

    companion object {
        /** The outcome with this database value, or null when it is not one we know. */
        fun fromServer(value: String?): DoseOutcome? =
            entries.firstOrNull { it.serverValue == value }
    }
}

/** Why a dose was not taken. Required exactly when the outcome is skipped. */
enum class SkipReason(val serverValue: String) {
    REFUSED("refused"),
    NOT_NEEDED("not_needed"),
    UNAVAILABLE("unavailable"),
    OTHER("other"),
    ;

    companion object {
        /** The reason with this database value, or null when it is not one we know. */
        fun fromServer(value: String?): SkipReason? =
            entries.firstOrNull { it.serverValue == value }
    }
}

/**
 * One append-only entry in the dose log (D6): what was done about one slot.
 *
 * Entries are never edited. A mistake is corrected by inserting another entry
 * whose [correctsEntryId] names the wrong one; [DoseLogRules.effectiveEntries]
 * then reports the corrected one as void. Every write is one entry per
 * medication, even when the UI confirms a whole time slot in one tap
 * (docs/ux/screens.md §2).
 *
 * @param scheduledLocal The slot's wall-clock time in the regimen's zone. This
 *   plus [regimenId] is the slot identity ([slotId]); it is what keeps a late
 *   confirmation attached to the right dose.
 * @param actualAt When it really happened, as an instant. May be before the
 *   scheduled time (given early) or long after (an offline phone syncing late).
 * @param confirmedBy `auth.users.id` of the person who recorded the entry.
 * @param actualQuantity How much was actually taken; only meaningful for
 *   [DoseOutcome.TAKEN] and optional even there (the UI defaults it to the
 *   slot's quantity).
 * @param givenByUserId Who gave it, when that is a member of the circle. Set
 *   only when [givenByOther] is false.
 * @param givenByOther True when the dose was given by someone without the app.
 * @param givenByName Optional free-text name of that someone, only with
 *   [givenByOther].
 * @param correctsEntryId The entry this one replaces; "same slot" is a
 *   requirement of the caller, because a correction carries the slot identity.
 */
data class DoseLogEntry(
    val id: DoseLogEntryId,
    val regimenId: RegimenId,
    val medicationId: MedicationId,
    val scheduledLocal: LocalDateTime,
    val outcome: DoseOutcome,
    val actualAt: Instant,
    val confirmedBy: String,
    val skipReason: SkipReason? = null,
    val note: String? = null,
    val actualQuantity: BigDecimal? = null,
    val givenByUserId: String? = null,
    val givenByOther: Boolean = false,
    val givenByName: String? = null,
    val correctsEntryId: DoseLogEntryId? = null,
) {

    /** The slot this entry answers: `(regimenId, scheduledLocal)`. */
    val slotId: DoseSlotId get() = DoseSlotId(regimenId, scheduledLocal)

    /** Whether this entry replaces an earlier one. */
    val isCorrection: Boolean get() = correctsEntryId != null

    /** The text fields as they should be stored: trimmed, blank → null. */
    fun normalized(): DoseLogEntry = copy(
        note = MedicationRules.normalizeOptional(note),
        confirmedBy = MedicationRules.normalizeText(confirmedBy),
        givenByUserId = MedicationRules.normalizeOptional(givenByUserId),
        givenByName = MedicationRules.normalizeOptional(givenByName),
    )

    /** Intrinsic validity, mirroring the `meds.dose_log` check constraints. */
    fun validate(): MedicationValidation = DoseLogRules.validate(this)
}

/**
 * The dose-log rules from docs/domain/medications.md "Dose log".
 *
 * The rules make the two outcomes exclusive: a taken entry has no skip reason
 * and may carry a quantity, a skipped entry must have a reason and no
 * quantity. "Given by" is either a circle member or "someone else", never
 * both, and a name only belongs to "someone else".
 */
object DoseLogRules {

    /** Mirrors `meds.dose_log_note_length`. */
    const val MAX_NOTE_LENGTH: Int = 500

    /** Mirrors `meds.dose_log_given_by_name_length`. */
    const val MAX_GIVEN_BY_NAME_LENGTH: Int = 100

    fun validate(entry: DoseLogEntry): MedicationValidation {
        val errors = mutableSetOf<MedicationField>()

        if (entry.scheduledLocal.second != 0 || entry.scheduledLocal.nano != 0) {
            // Slot times are HH:MM; anything finer could never match a slot.
            errors += MedicationField.DOSE_SCHEDULED_TIME
        }

        when (entry.outcome) {
            DoseOutcome.TAKEN -> {
                if (entry.skipReason != null) {
                    errors += MedicationField.DOSE_SKIP_REASON
                }
                if (entry.actualQuantity != null &&
                    !QuantityRules.isValid(entry.actualQuantity)
                ) {
                    errors += MedicationField.DOSE_QUANTITY
                }
            }

            DoseOutcome.SKIPPED -> {
                if (entry.skipReason == null) {
                    errors += MedicationField.DOSE_SKIP_REASON
                }
                if (entry.actualQuantity != null) {
                    errors += MedicationField.DOSE_QUANTITY
                }
            }
        }

        if (MedicationRules.normalizeOptional(entry.confirmedBy) == null) {
            errors += MedicationField.DOSE_CONFIRMED_BY
        }
        if (givenByUserId(entry) != null && entry.givenByOther) {
            errors += MedicationField.DOSE_GIVEN_BY
        }
        if (givenByName(entry) != null && !entry.givenByOther) {
            errors += MedicationField.DOSE_GIVEN_BY
        }
        if ((givenByName(entry)?.length ?: 0) > MAX_GIVEN_BY_NAME_LENGTH) {
            errors += MedicationField.DOSE_GIVEN_BY
        }
        if ((MedicationRules.normalizeOptional(entry.note)?.length ?: 0) > MAX_NOTE_LENGTH) {
            errors += MedicationField.DOSE_NOTE
        }
        if (entry.correctsEntryId != null && entry.correctsEntryId == entry.id) {
            errors += MedicationField.DOSE_CORRECTION
        }

        return MedicationValidation(errors)
    }

    private fun givenByUserId(entry: DoseLogEntry): String? =
        MedicationRules.normalizeOptional(entry.givenByUserId)

    private fun givenByName(entry: DoseLogEntry): String? =
        MedicationRules.normalizeOptional(entry.givenByName)

    /**
     * The entries that still count for [slot], oldest first.
     *
     * An entry is void when another entry in [entries] names it in
     * [DoseLogEntry.correctsEntryId]; the correcting entry itself counts. The
     * list contains every effective entry, so two unrelated confirmations of
     * one slot come back as two entries — the double-confirmation warning
     * (docs/domain/medications.md).
     */
    fun effectiveEntries(
        slot: DoseSlotId,
        entries: List<DoseLogEntry>,
    ): List<DoseLogEntry> = effectiveEntries(entries).filter { it.slotId == slot }

    /**
     * Every effective entry of a whole medication, oldest first.
     *
     * The stock ledger needs this: a dose deducts unless another entry names it
     * in [DoseLogEntry.correctsEntryId] (D28), and the voiding rule is exactly
     * the one above — not a second implementation of it.
     */
    fun effectiveEntries(entries: List<DoseLogEntry>): List<DoseLogEntry> {
        val voided = entries.mapNotNullTo(mutableSetOf()) { it.correctsEntryId }
        return entries
            .filter { it.id !in voided }
            .sortedWith(compareBy({ it.actualAt }, { it.id.value }))
    }

    /**
     * What the "already given?" warning should show before saving: the
     * effective entries of [slot], or an empty list when the slot is untouched.
     * The UI turns them into "Already given by Mom at 08:04".
     */
    fun existingConfirmation(
        slot: DoseSlot,
        entries: List<DoseLogEntry>,
    ): List<DoseLogEntry> = effectiveEntries(slot.slotId, entries)

    /** The same, addressed by slot identity. */
    fun existingConfirmation(
        slot: DoseSlotId,
        entries: List<DoseLogEntry>,
    ): List<DoseLogEntry> = effectiveEntries(slot, entries)
}
