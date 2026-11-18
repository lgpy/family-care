package io.github.lgpy.familycare.feature.medications.domain

import io.github.lgpy.familycare.core.domain.circle.Patient
import java.time.Instant

/** The derived state of one dose slot (docs/domain/medications.md "Dose status"). */
enum class DoseState {
    /** Before the scheduled time; nothing to do yet. */
    UPCOMING,

    /** Due now, within the patient's escalation delay. */
    DUE,

    /** Past the escalation delay, still unconfirmed, inside the missed window. */
    OVERDUE,

    /** Past the missed window with no confirmation. Never stored (D9). */
    MISSED,

    /** An effective "taken" entry exists for the slot. */
    TAKEN,

    /** An effective "skipped" entry exists for the slot. */
    SKIPPED,
}

/**
 * The state of [slot] plus the entries that produced it.
 *
 * [effectiveEntries] is what [DoseLogRules.effectiveEntries] returned (corrected
 * entries removed), oldest first. More than one means the slot was confirmed
 * independently twice — the UI keeps showing one dose but must say so
 * ("confirmed twice"), because that is a double-dosing risk.
 */
data class DoseStatus(
    val slot: DoseSlot,
    val state: DoseState,
    val effectiveEntries: List<DoseLogEntry> = emptyList(),
) {

    /** How many independent confirmations the slot has. */
    val confirmationCount: Int get() = effectiveEntries.size

    /** Whether two or more effective entries exist for the slot. */
    val confirmedTwice: Boolean get() = confirmationCount > 1

    /** Whether the slot has been resolved by a taken or skipped entry. */
    val isConfirmed: Boolean get() = state == DoseState.TAKEN || state == DoseState.SKIPPED

    /** The entry that decides TAKEN/SKIPPED: the latest effective one. */
    val latestEntry: DoseLogEntry? get() = effectiveEntries.lastOrNull()
}

/**
 * Dose-status derivation from docs/domain/medications.md "Dose status" and the
 * walkthrough in docs/ux/screens.md.
 *
 * ### Boundaries (inclusive, and pinned by the shared vectors)
 * With `s = slot.instant`, `e = escalation delay`, `m = missed window`:
 * - `now < s` → [DoseState.UPCOMING].
 * - `now == s` → [DoseState.DUE] (the scheduled minute is due, not upcoming).
 * - `now == s + e` → [DoseState.DUE]; `now == s + e + 1s` → [DoseState.OVERDUE].
 * - `now == s + m` → [DoseState.OVERDUE]; `now == s + m + 1s` → [DoseState.MISSED].
 * - An effective entry always wins over the clock: a dose confirmed late is
 *   [DoseState.TAKEN], not [DoseState.MISSED].
 *
 * The missed window is checked before the escalation delay, so a patient
 * configured with a shorter missed window than escalation delay still turns
 * MISSED once the window is past (the core validates ranges per setting, not
 * their order).
 *
 * Corrections: entries referenced by another entry's
 * [DoseLogEntry.correctsEntryId] are void. When several effective entries remain
 * (double confirmation), the **latest** by `actualAt` decides TAKEN vs SKIPPED,
 * and [DoseStatus.confirmationCount] reports how many there are.
 */
object DoseStatusRules {

    /**
     * The status of [slot] at [now].
     *
     * @param entries Entries of any slots; only those for [slot]'s identity are
     *   considered.
     * @param escalationDelayMinutes Minutes after the scheduled time before the
     *   slot is overdue (patient's setting).
     * @param missedWindowMinutes Minutes after the scheduled time after which an
     *   unconfirmed slot is missed (patient's setting).
     */
    fun doseStatus(
        slot: DoseSlot,
        entries: List<DoseLogEntry>,
        now: Instant,
        escalationDelayMinutes: Int,
        missedWindowMinutes: Int,
    ): DoseStatus {
        require(escalationDelayMinutes >= 0) { "An escalation delay cannot be negative" }
        require(missedWindowMinutes >= 0) { "A missed window cannot be negative" }

        val effective = DoseLogRules.effectiveEntries(slot.slotId, entries)
        if (effective.isNotEmpty()) {
            val deciding = effective.last()
            val state = when (deciding.outcome) {
                DoseOutcome.TAKEN -> DoseState.TAKEN
                DoseOutcome.SKIPPED -> DoseState.SKIPPED
            }
            return DoseStatus(slot = slot, state = state, effectiveEntries = effective)
        }

        val escalationEnd = slot.instant.plusSeconds(escalationDelayMinutes * 60L)
        val missedEnd = slot.instant.plusSeconds(missedWindowMinutes * 60L)

        val state = when {
            now.isBefore(slot.instant) -> DoseState.UPCOMING
            now.isAfter(missedEnd) -> DoseState.MISSED
            !now.isAfter(escalationEnd) -> DoseState.DUE
            else -> DoseState.OVERDUE
        }
        return DoseStatus(slot = slot, state = state)
    }

    /** The same derivation with the delays read from the patient's settings. */
    fun doseStatus(
        slot: DoseSlot,
        entries: List<DoseLogEntry>,
        now: Instant,
        patient: Patient,
    ): DoseStatus = doseStatus(
        slot = slot,
        entries = entries,
        now = now,
        escalationDelayMinutes = patient.escalationDelayMinutes,
        missedWindowMinutes = patient.missedWindowMinutes,
    )
}
