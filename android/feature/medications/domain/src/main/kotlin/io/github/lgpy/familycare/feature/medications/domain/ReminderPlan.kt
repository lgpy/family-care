package io.github.lgpy.familycare.feature.medications.domain

import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientId
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/**
 * The medications and regimen versions of one medication, as the reminder
 * planner needs them.
 *
 * The data layer's `MedicationWithRegimens` is deliberately not reused: that
 * type belongs to the module's data layer, and the planner must stay pure
 * Kotlin (D22).
 */
data class ReminderSource(
    val medication: Medication,
    val regimens: List<Regimen>,
)

/**
 * One patient's reminder for one wall-clock slot: every medication due at the
 * same local time, so one notification and one "Given ✓" cover the group.
 *
 * The identity the alarm and the notification use is
 * [ReminderIds.requestCode], derived from [patientId] and [instant]. On a DST
 * gap day two different wall-clock groups can share an instant (see
 * [DoseSlotRules]); they remain separate groups because [scheduledLocal] is
 * part of the key, and their request codes differ only if their instants do —
 * the request code is therefore collision-tolerant rather than an identity.
 *
 * @param instant When the alarm should fire (the earliest slot instant of the
 *   group; all slots of a wall-clock group share it in practice).
 * @param slots The slots to confirm when the notification's "Given ✓" is used.
 */
data class ReminderGroup(
    val patientId: PatientId,
    val scheduledLocal: LocalDateTime,
    val instant: Instant,
    val slots: List<DoseSlot>,
) {

    /** How many medications are due in this group. */
    val slotCount: Int get() = slots.size
}

/**
 * The local reminder plan for the next [DEFAULT_HORIZON]: which time-slot
 * groups to schedule on this phone, already filtered by routing and by doses
 * that are effectively confirmed.
 *
 * ### What is excluded
 * - Stopped medications and archived patients.
 * - A patient with no synced row (the medication is visible but its routing
 *   settings are not): dropping the reminder is the safe choice until the row
 *   arrives.
 * - Any group on which every slot already has an effective entry
 *   ([DoseLogRules.effectiveEntries]). A partly confirmed group is still
 *   planned, so the remaining medications are reminded.
 * - Slots before [now] and after the horizon.
 *
 * ### DST
 * Slot instants come from [DoseSlotRules.slotsDueBetween], which widens each
 * regimen's local range by a day on both sides. A spring-forward slot therefore
 * keeps its configured wall-clock identity while its instant moves to the
 * instant after the gap.
 */
object ReminderPlanning {

    /** The default scheduling horizon (docs/build-plan.md M4: the next 48 h). */
    val DEFAULT_HORIZON: Duration = Duration.ofHours(48)

    /**
     * @param now The instant "the next 48 h" starts from; also the upper bound
     *   of entries the caller passes.
     * @param entries Entries of any slots in the window; confirmed/skipped slots
     *   are removed from the plan.
     */
    fun plan(
        now: Instant,
        sources: List<ReminderSource>,
        patients: Map<PatientId, Patient>,
        entries: List<DoseLogEntry>,
        myUserId: String?,
        myRole: MemberRole?,
        horizon: Duration = DEFAULT_HORIZON,
    ): List<ReminderGroup> {
        if (horizon.isNegative || horizon.isZero) return emptyList()
        val to = now.plus(horizon)

        val grouped = linkedMapOf<Pair<PatientId, LocalDateTime>, MutableList<DoseSlot>>()

        sources.forEach { source ->
            val medication = source.medication
            if (medication.isStopped) return@forEach
            val patient = patients[medication.patientId] ?: return@forEach
            if (patient.isArchived) return@forEach
            if (!ReminderRouting.shouldRemindOnThisDevice(
                    patient = patient,
                    medication = medication,
                    myUserId = myUserId,
                    myRole = myRole,
                )
            ) {
                return@forEach
            }

            val slots = DoseSlotRules.slotsDueBetween(source.regimens, now, to)
                .filter { slot ->
                    slot.medicationId == medication.id &&
                        DoseLogRules.effectiveEntries(slot.slotId, entries).isEmpty()
                }
            slots.forEach { slot ->
                grouped.getOrPut(medication.patientId to slot.scheduledLocal) { mutableListOf() } += slot
            }
        }

        return grouped.map { (key, slots) ->
            ReminderGroup(
                patientId = key.first,
                scheduledLocal = key.second,
                instant = slots.minOf { it.instant },
                slots = slots.sortedWith(
                    compareBy({ it.medicationId.value }, { it.regimenId.value }),
                ),
            )
        }
            .sortedWith(
                compareBy(
                    { it.instant },
                    { it.patientId.value },
                    { it.scheduledLocal },
                ),
            )
            .filter { !it.instant.isBefore(now) && !it.instant.isAfter(to) }
    }

    /**
     * The groups whose time has **passed** but which are still open, so their
     * alarm (due, not yet delivered) and their showing notification must
     * survive a reschedule. [plan] only looks forward, so without this every
     * reschedule after 08:00 would dismiss the unacknowledged 08:00 reminder.
     *
     * A group is open while at least one of its slots has no effective entry
     * ([DoseLogRules.effectiveEntries]) and is not yet
     * [DoseState.MISSED] by [DoseStatusRules] with the patient's missed window,
     * and the same medication, patient and routing checks as [plan] hold. The
     * returned groups hold only the still-open slots, grouped like [plan]
     * groups, so [ReminderIds.requestCode] gives the same id as before the group
     * was due.
     *
     * Slots that start after [now] are not returned: they are [plan]'s.
     */
    fun openGroups(
        now: Instant,
        sources: List<ReminderSource>,
        patients: Map<PatientId, Patient>,
        entries: List<DoseLogEntry>,
        myUserId: String?,
        myRole: MemberRole?,
    ): List<ReminderGroup> {
        val grouped = linkedMapOf<Pair<PatientId, LocalDateTime>, MutableList<DoseSlot>>()

        sources.forEach { source ->
            val medication = source.medication
            if (medication.isStopped) return@forEach
            val patient = patients[medication.patientId] ?: return@forEach
            if (patient.isArchived) return@forEach
            if (!ReminderRouting.shouldRemindOnThisDevice(
                    patient = patient,
                    medication = medication,
                    myUserId = myUserId,
                    myRole = myRole,
                )
            ) {
                return@forEach
            }

            // A slot older than the missed window is missed whatever happens,
            // so the search can start there.
            val from = now.minusSeconds(patient.missedWindowMinutes * 60L)
            DoseSlotRules.slotsDueBetween(source.regimens, from, now)
                .filter { it.medicationId == medication.id }
                .filter { slot ->
                    DoseStatusRules.doseStatus(slot, entries, now, patient).state in OPEN_STATES
                }
                .forEach { slot ->
                    grouped.getOrPut(medication.patientId to slot.scheduledLocal) { mutableListOf() } += slot
                }
        }

        return grouped.map { (key, slots) ->
            ReminderGroup(
                patientId = key.first,
                scheduledLocal = key.second,
                instant = slots.minOf { it.instant },
                slots = slots.sortedWith(
                    compareBy({ it.medicationId.value }, { it.regimenId.value }),
                ),
            )
        }.sortedWith(
            compareBy(
                { it.instant },
                { it.patientId.value },
                { it.scheduledLocal },
            ),
        )
    }

    /**
     * What a reschedule may cancel out of [candidateIds] (scheduled alarms or
     * showing notifications): everything that is neither in the forward [planned]
     * list nor in the [open] list of groups that are due but not yet confirmed
     * or missed.
     */
    fun idsToCancel(
        candidateIds: Collection<Int>,
        planned: List<ReminderGroup>,
        open: List<ReminderGroup>,
    ): Set<Int> {
        val keep = (planned + open).mapTo(hashSetOf()) { ReminderIds.requestCode(it) }
        return candidateIds.filterNotTo(linkedSetOf()) { it in keep }
    }

    private val OPEN_STATES = setOf(DoseState.UPCOMING, DoseState.DUE, DoseState.OVERDUE)
}

/**
 * Identifier rules shared by the alarm, the notification and the pending
 * intents.
 *
 * A 32-bit request code cannot be a collision-free identity for arbitrary
 * patient ids and instants; the plan is small (a day or two of dose times), so
 * the derived code is stable and collisions are practically impossible. The
 * cancelled/scheduled index stores the same codes, which keeps cancellation in
 * step with scheduling.
 */
object ReminderIds {

    /** The stable request code (and notification id) of [patientId] at [instant]. */
    fun requestCode(patientId: PatientId, instant: Instant): Int =
        "${patientId.value}|${instant.epochSecond}".hashCode() and 0x7fffffff

    /** The same rule for a planned group. */
    fun requestCode(group: ReminderGroup): Int = requestCode(group.patientId, group.instant)
}
