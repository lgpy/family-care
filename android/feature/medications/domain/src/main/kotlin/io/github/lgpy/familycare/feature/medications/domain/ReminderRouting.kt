package io.github.lgpy.familycare.feature.medications.domain

import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy

/**
 * Who should be reminded on **this** phone (docs/domain/care-circle.md
 * "Reminder routing", D7/D11).
 *
 * The server runs escalation; this decides only which local alarms to schedule.
 * A medication may override the patient's policy and primary caregiver, so the
 * *effective* values are computed first: the medication's override when set,
 * otherwise the patient's setting.
 *
 * Rules:
 * - [ReminderPolicy.PRIMARY_WITH_ESCALATION]: only the effective primary
 *   caregiver's phone reminds. A null primary means **nobody locally** — there
 *   is no one to remind, and the escalation safety net is the only path left.
 *   The app flags such a patient elsewhere (D24); the scheduler must not send a
 *   reminder to every caregiver instead.
 * - [ReminderPolicy.ALL_CAREGIVERS]: every owner or caregiver is reminded; the
 *   first confirmation wins and clears the others (`DoseLogRepository`).
 * - [ReminderPolicy.ROTA]: not built; treat it as "nobody locally" so a future
 *   server value cannot start spamming every phone.
 * - A viewer (or a user with no role / not signed in) is never reminded.
 *
 * Pure Kotlin so the rule is unit-tested without Android (D22).
 */
object ReminderRouting {

    /** The policy that actually applies to [medication]. */
    fun effectivePolicy(patient: Patient, medication: Medication): ReminderPolicy =
        medication.reminderPolicy ?: patient.reminderPolicy

    /** The primary caregiver that actually applies to [medication]. */
    fun effectivePrimaryCaregiverId(
        patient: Patient,
        medication: Medication,
    ): String? = medication.primaryCaregiverId ?: patient.primaryCaregiverId

    /**
     * Whether this phone should schedule reminders for [medication] of
     * [patient].
     *
     * @param myUserId `auth.users.id` of the signed-in user, or null when signed out.
     * @param myRole the signed-in user's role in the patient's circle, or null
     *   when they are not a member.
     */
    fun shouldRemindOnThisDevice(
        patient: Patient,
        medication: Medication,
        myUserId: String?,
        myRole: MemberRole?,
    ): Boolean {
        if (myUserId == null) return false
        if (!myRole.canBeReminded) return false

        return when (effectivePolicy(patient, medication)) {
            ReminderPolicy.PRIMARY_WITH_ESCALATION ->
                effectivePrimaryCaregiverId(patient, medication) == myUserId

            ReminderPolicy.ALL_CAREGIVERS -> true

            // Modelled in the core but not built: schedule nothing locally.
            ReminderPolicy.ROTA -> false
        }
    }

    /** Owners and caregivers can act; viewers are read-only and never reminded. */
    private val MemberRole?.canBeReminded: Boolean
        get() = this == MemberRole.OWNER || this == MemberRole.CAREGIVER
}
