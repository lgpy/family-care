package io.github.lgpy.familycare.feature.medications.domain

import io.github.lgpy.familycare.core.domain.circle.PatientId
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * All doses one patient has due at the same wall-clock moment, so the UI can
 * confirm them with one tap ("08:00 · Grandpa (3)"; docs/ux/screens.md §1-2).
 *
 * Group confirmation is a **UI** convenience: storage still gets one
 * [DoseLogEntry] per medication, so one refusal among three doses is a separate
 * skipped entry. The group is keyed by the **local** date and time, not by the
 * instant: the slot identity is local, and after a DST change the same wall
 * clock can be two different instants.
 *
 * @param slots The patient's slots at this local time, sorted by medication
 *   then regimen so the confirm screen is deterministic.
 */
data class TimeSlotGroup(
    val patientId: PatientId,
    val scheduledLocal: LocalDateTime,
    val slots: List<DoseSlot>,
) {

    /** The local date of the group. */
    val localDate: LocalDate get() = scheduledLocal.toLocalDate()

    /** The wall-clock time of the group. */
    val localTime: LocalTime get() = scheduledLocal.toLocalTime()

    /** The medications to confirm, in display order. */
    val medications: List<MedicationId> get() = slots.map { it.medicationId }

    /** How many separate log entries this group produces when confirmed. */
    val slotCount: Int get() = slots.size

    /** The total quantity, summing across medications (for stock previews). */
    val totalQuantity: BigDecimal
        get() = slots.fold(BigDecimal.ZERO) { sum, slot -> sum + slot.quantity }
}

/**
 * Groups [slots] into per-patient, per-local-time confirmations.
 *
 * [patientOf] resolves the patient of a medication (the medication row holds the
 * patient, not the slot). Slots whose medication does not resolve are skipped:
 * that is the "medication just deleted / not synced yet" case, and dropping a
 * dose from the confirm sheet is safer than showing it without a patient.
 *
 * Groups are sorted by local date and time, then patient id; equal keys keep
 * the [DoseSlotRules.SLOT_ORDER] within the group.
 */
fun groupTimeSlots(
    slots: List<DoseSlot>,
    patientOf: (MedicationId) -> PatientId?,
): List<TimeSlotGroup> {
    val grouped = linkedMapOf<Pair<PatientId, LocalDateTime>, MutableList<DoseSlot>>()
    slots.sortedWith(DoseSlotRules.SLOT_ORDER).forEach { slot ->
        val patient = patientOf(slot.medicationId) ?: return@forEach
        grouped.getOrPut(patient to slot.scheduledLocal) { mutableListOf() } += slot
    }
    return grouped.entries
        .sortedWith(
            compareBy(
                { it.key.second },
                { it.key.first.value },
            ),
        )
        .map { (key, groupSlots) ->
            TimeSlotGroup(
                patientId = key.first,
                scheduledLocal = key.second,
                slots = groupSlots.sortedWith(
                    compareBy({ it.medicationId.value }, { it.regimenId.value }),
                ),
            )
        }
}

/** The same grouping, resolving each slot's patient from the medication rows. */
fun groupTimeSlots(
    slots: List<DoseSlot>,
    medications: Collection<Medication>,
): List<TimeSlotGroup> {
    val byId = medications.associateBy { it.id }
    return groupTimeSlots(slots) { medicationId -> byId[medicationId]?.patientId }
}
