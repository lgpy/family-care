package io.github.lgpy.familycare.feature.medications.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Identity of one dose slot: `(regimenId, scheduledLocal)`.
 *
 * Slots are computed, never stored (docs/domain/medications.md), so their
 * identity has to be reproducible: the regimen version plus the wall-clock time
 * the dose was due. A dose-log entry points at it, which is how a late
 * confirmation from an offline phone still resolves the right dose.
 */
data class DoseSlotId(
    val regimenId: RegimenId,
    val scheduledLocal: LocalDateTime,
)

/**
 * One dose due at a concrete instant.
 *
 * @param scheduledLocal The wall-clock time the dose was due **in the regimen's
 *   zone**. This is the slot identity ([slotId]) and what "08:00" means to a
 *   person; it stays the same across a DST change even though the instant moves.
 * @param instant When the slot is due in absolute time, derived from
 *   [scheduledLocal] and the regimen's zone. On a spring-forward gap the
 *   wall-clock time does not exist and the instant is the one after the gap (see
 *   [DoseSlotRules.doseSlots]).
 * @param quantity How much to take at this slot, copied from the regimen.
 */
data class DoseSlot(
    val regimenId: RegimenId,
    val medicationId: MedicationId,
    val scheduledLocal: LocalDateTime,
    val instant: Instant,
    val quantity: BigDecimal,
) {

    /** The `(regimenId, scheduledLocal)` identity. */
    val slotId: DoseSlotId get() = DoseSlotId(regimenId, scheduledLocal)

    /** The local date of the slot, in the regimen's zone. */
    val localDate: LocalDate get() = scheduledLocal.toLocalDate()

    /** The local wall-clock time of the slot. */
    val localTime: LocalTime get() = scheduledLocal.toLocalTime()

    /** The quantity as vectors and reports write it (`0.5`, `1`). */
    val formattedQuantity: String get() = QuantityRules.format(quantity)
}

/**
 * Dose-slot calculation (docs/domain/medications.md "Dose slots").
 *
 * The same rules are implemented in TypeScript on the server for escalation;
 * the shared vectors in `shared/test-vectors` keep the two honest (D22).
 *
 * ### Dates and time zones
 * A regimen's dates are **local dates in the regimen's own zone**. The zone is
 * part of the regimen version, so changing the phone's zone never rewrites past
 * slots.
 *
 * ### DST resolution (the rule the vectors pin down)
 * - **Gap (spring forward):** a wall-clock time that does not exist is moved
 *   **forward by the length of the gap**, using java.time's default resolution.
 *   Europe/Lisbon 2026-03-29 01:30 (WET → WEST at 01:00, a one-hour gap)
 *   becomes 02:30+01:00, i.e. `2026-03-29T01:30:00Z`.
 * - **Overlap (fall back):** an ambiguous wall-clock time uses the **earlier
 *   offset**. Europe/Lisbon 2026-10-25 01:30 (WEST → WET at 02:00) is
 *   01:30+01:00, i.e. `2026-10-25T00:30:00Z`, not the 01:30Z of the second
 *   occurrence.
 * - The slot identity remains the **local** time, so each slot appears exactly
 *   once even though a gap day has a 01:30 slot that lands at 02:30 local. Two
 *   distinct configured times may therefore share an instant on a gap day
 *   (01:30 and 02:30); both slots still exist, because a person was told to take
 *   a dose at each of those wall-clock times.
 *
 * ### Ordering
 * Slots are sorted by [DoseSlot.instant], then medication id, then local time,
 * then regimen id, so output is deterministic when several medications share a
 * time.
 */
object DoseSlotRules {

    /**
     * Every slot due between [from] and [to], **both dates inclusive**, for the
     * given regimen versions.
     *
     * Only dates the regimen occurs on are produced ([Regimen.occursOn]); a
     * version's [Regimen.endDate] is inclusive. Returns an empty list when
     * [to] is before [from]. Passing versions that overlap (a broken version
     * chain) produces duplicate slots; [RegimenRules.findOverlaps] is how a
     * caller checks first.
     */
    fun doseSlots(
        regimens: List<Regimen>,
        from: LocalDate,
        to: LocalDate,
    ): List<DoseSlot> {
        if (to.isBefore(from)) return emptyList()

        val slots = mutableListOf<DoseSlot>()
        regimens.forEach { regimen ->
            val first = if (from.isAfter(regimen.startDate)) from else regimen.startDate
            val last = when (val end = regimen.endDate) {
                null -> to
                else -> if (to.isBefore(end)) to else end
            }
            if (last.isBefore(first)) return@forEach

            val times = regimen.sortedTimes()
            var date = first
            while (!date.isAfter(last)) {
                if (regimen.occursOn(date)) {
                    times.forEach { doseTime ->
                        val local = date.atTime(doseTime.time)
                        slots += DoseSlot(
                            regimenId = regimen.id,
                            medicationId = regimen.medicationId,
                            scheduledLocal = local,
                            // LocalDateTime.atZone applies the documented gap and
                            // overlap resolution and keeps the local time.
                            instant = local.atZone(regimen.timeZone).toInstant(),
                            quantity = doseTime.quantity,
                        )
                    }
                }
                date = date.plusDays(1)
            }
        }
        return slots.sortedWith(SLOT_ORDER)
    }

    /**
     * The slots whose instant falls between [from] and [to], **both inclusive**.
     *
     * For the M4 reminder scheduler, which thinks in instants: it asks for the
     * next window rather than for calendar days. Each regimen's range is widened
     * by one local day on both sides before filtering, because a slot near
     * midnight (or moved by a DST gap) can belong to the neighbouring local date.
     */
    fun slotsDueBetween(
        regimens: List<Regimen>,
        from: Instant,
        to: Instant,
    ): List<DoseSlot> {
        if (to.isBefore(from)) return emptyList()

        val slots = mutableListOf<DoseSlot>()
        regimens.forEach { regimen ->
            val first = from.atZone(regimen.timeZone).toLocalDate().minusDays(1)
            val last = to.atZone(regimen.timeZone).toLocalDate().plusDays(1)
            doseSlots(listOf(regimen), first, last)
                .filter { !it.instant.isBefore(from) && !it.instant.isAfter(to) }
                .forEach { slots += it }
        }
        return slots.sortedWith(SLOT_ORDER)
    }

    /** The order every slot-producing function returns. */
    val SLOT_ORDER: Comparator<DoseSlot> = compareBy(
        { it.instant },
        { it.medicationId.value },
        { it.scheduledLocal },
        { it.regimenId.value },
    )
}
