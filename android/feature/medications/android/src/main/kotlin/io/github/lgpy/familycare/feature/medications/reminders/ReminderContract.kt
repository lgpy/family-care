package io.github.lgpy.familycare.feature.medications.reminders

import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.feature.medications.domain.DoseSlot
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.RegimenId
import io.github.lgpy.familycare.feature.medications.domain.TimeSlotGroup
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime

/**
 * The extras and actions the alarm, notification and action broadcasts share.
 *
 * A dose group is passed as plain strings so a receiver never needs the database
 * to build the notification: the plan is computed when the alarm is scheduled,
 * and the alarm carries exactly what the notification and "Given ✓" need.
 * Nothing here is persisted beyond the alarm's PendingIntent.
 */
object ReminderContract {

    /** The notification channel (D26). */
    const val CHANNEL_ID: String = "dose_reminders"

    /** Fired by the alarm scheduled for one time-slot group. */
    const val ACTION_REMIND = "io.github.lgpy.familycare.action.DOSE_REMIND"

    /** Fired by "Given ✓" on the notification. */
    const val ACTION_GIVE = "io.github.lgpy.familycare.action.DOSE_GIVE"

    /** Fired by "Snooze 15 min" on the notification. */
    const val ACTION_SNOOZE = "io.github.lgpy.familycare.action.DOSE_SNOOZE"

    /** Fired by the daily safety alarm and the system broadcasts. */
    const val ACTION_RESCHEDULE = "io.github.lgpy.familycare.action.RESCHEDULE_REMINDERS"

    const val EXTRA_PATIENT_ID = "patientId"
    const val EXTRA_PATIENT_NAME = "patientName"
    const val EXTRA_SCHEDULED_LOCAL = "scheduledLocal"
    const val EXTRA_INSTANT = "instant"
    const val EXTRA_SLOTS = "slots"

    /**
     * Extra the app shell reads to open a screen from a notification: the
     * medication route of the dose slot (`patients/<id>/doses/<date_time>`).
     */
    const val EXTRA_ROUTE = "route"

    private const val SLOT_SEPARATOR = "\u001F"

    /** Encodes the group into the alarm intent's extras. */
    fun putGroup(intent: android.content.Intent, group: TimeSlotGroup, patientName: String): android.content.Intent =
        intent
            .putExtra(EXTRA_PATIENT_ID, group.patientId.value)
            .putExtra(EXTRA_PATIENT_NAME, patientName)
            .putExtra(EXTRA_SCHEDULED_LOCAL, group.scheduledLocal.toString())
            .putStringArrayListExtra(
                EXTRA_SLOTS,
                ArrayList(
                    group.slots.map { slot ->
                        listOf(
                            slot.regimenId.value,
                            slot.medicationId.value,
                            slot.scheduledLocal.toString(),
                            slot.instant.toEpochMilli().toString(),
                            slot.quantity.toPlainString(),
                        ).joinToString(SLOT_SEPARATOR)
                    },
                ),
            )

    /** The group an alarm/action intent carries, or null when it is malformed. */
    fun groupFrom(intent: android.content.Intent): TimeSlotGroup? {
        val patientId = intent.getStringExtra(EXTRA_PATIENT_ID) ?: return null
        val scheduledLocal = intent.getStringExtra(EXTRA_SCHEDULED_LOCAL) ?: return null
        val encoded = intent.getStringArrayListExtra(EXTRA_SLOTS) ?: return null
        val slots = encoded.mapNotNull { row ->
            val parts = row.split(SLOT_SEPARATOR)
            if (parts.size != 5) return@mapNotNull null
            val local = runCatching { LocalDateTime.parse(parts[2]) }.getOrNull()
                ?: return@mapNotNull null
            val instant = parts[3].toLongOrNull() ?: return@mapNotNull null
            val quantity = runCatching { BigDecimal(parts[4]) }.getOrNull()
                ?: return@mapNotNull null
            DoseSlot(
                regimenId = RegimenId(parts[0]),
                medicationId = MedicationId(parts[1]),
                scheduledLocal = local,
                instant = Instant.ofEpochMilli(instant),
                quantity = quantity,
            )
        }
        if (slots.isEmpty()) return null
        val groupLocal = runCatching { LocalDateTime.parse(scheduledLocal) }.getOrNull()
            ?: return null
        return TimeSlotGroup(
            patientId = PatientId(patientId),
            scheduledLocal = groupLocal,
            slots = slots,
        )
    }

    /** The patient's display name carried for the private notification text. */
    fun patientNameFrom(intent: android.content.Intent): String? =
        intent.getStringExtra(EXTRA_PATIENT_NAME)

    /** The route that opens this group's dose-slot screen. */
    fun routeFor(group: TimeSlotGroup): String =
        "patients/${group.patientId.value}/doses/" +
            io.github.lgpy.familycare.feature.medications.MedicationsRoutes.encodeScheduledLocal(
                group.scheduledLocal,
            )
}
