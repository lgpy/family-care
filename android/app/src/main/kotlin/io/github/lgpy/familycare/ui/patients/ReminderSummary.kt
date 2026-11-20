package io.github.lgpy.familycare.ui.patients

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.lgpy.familycare.core.domain.circle.PatientRules
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.core.ui.R

/**
 * What a patient's reminder line says, as data — the screen renders each kind
 * with its own short string.
 *
 * The profile head used to print the policy's full label plus the primary
 * caregiver's name ("reminders: Primary with escalation · Primary: Leonardo
 * Gomes"): four lines at 200% for information the user can read at a glance.
 * Each kind is one short phrase instead (docs/ux/design-system.md §6).
 */
internal enum class ReminderSummaryKind {

    /** "Leonardo first, then everyone" — a named primary caregiver. */
    PRIMARY_FIRST,

    /** "You first, then everyone" — the primary caregiver is the signed-in user. */
    YOU_FIRST,

    /** "Everyone is reminded" — every caregiver gets the reminder. */
    EVERYONE,

    /** No short human summary exists: fall back to the policy's own label. */
    POLICY,
}

/**
 * The kind of reminder summary for [policy], given the primary caregiver's
 * first name (already reduced by [firstNameOf]) and whether that caregiver is
 * the signed-in user.
 *
 * Pure, so the mapping is unit-tested without a device: the choice must not
 * depend on anything the composable measures or on `fontScale`.
 *
 * A `primary_with_escalation` patient whose primary is neither named nor the
 * user keeps the policy label — there is no name to put in the sentence.
 * `null` means there is nothing to say (the patient is not loaded).
 */
internal fun reminderSummaryKind(
    policy: ReminderPolicy?,
    primaryFirstName: String?,
    primaryIsMe: Boolean,
): ReminderSummaryKind? = when (policy) {
    null -> null
    ReminderPolicy.PRIMARY_WITH_ESCALATION -> when {
        primaryIsMe -> ReminderSummaryKind.YOU_FIRST
        primaryFirstName != null -> ReminderSummaryKind.PRIMARY_FIRST
        else -> ReminderSummaryKind.POLICY
    }

    ReminderPolicy.ALL_CAREGIVERS -> ReminderSummaryKind.EVERYONE
    ReminderPolicy.ROTA -> ReminderSummaryKind.POLICY
}

/**
 * The first word of a caregiver's display name, or null when there is none:
 * "Leonardo Gomes" → "Leonardo", "  " → null.
 */
internal fun firstNameOf(displayName: String?): String? = displayName
    ?.trim()
    ?.substringBefore(' ')
    ?.takeIf { it.isNotEmpty() }

/**
 * One resource and the arguments that fill it: the summary's wording before it
 * is rendered. Pure, so every policy value is unit-tested without a device.
 */
internal data class ReminderSummaryText(
    val resource: Int,
    val args: List<Any> = emptyList(),
)

/**
 * The one-line reminder summary of the policy, or the policy's own label when
 * it has no short human phrase; null while the policy is not loaded.
 *
 * Shared by the profile head and the "Who gets reminded" list so both describe
 * the same routing with the same words, now including the escalation delay
 * (finding 20 of the UX audit).
 */
internal fun reminderSummaryText(
    policy: ReminderPolicy?,
    primaryFirstName: String?,
    primaryIsMe: Boolean,
    escalationDelayMinutes: Int = PatientRules.DEFAULT_ESCALATION_DELAY_MINUTES,
): ReminderSummaryText? = when (reminderSummaryKind(policy, primaryFirstName, primaryIsMe)) {
    null -> null
    ReminderSummaryKind.PRIMARY_FIRST -> ReminderSummaryText(
        resource = R.string.patients_reminder_primary_first,
        args = listOf(primaryFirstName.orEmpty(), escalationDelayMinutes),
    )

    ReminderSummaryKind.YOU_FIRST -> ReminderSummaryText(
        resource = R.string.patients_reminder_you_first,
        args = listOf(escalationDelayMinutes),
    )

    ReminderSummaryKind.EVERYONE -> ReminderSummaryText(
        resource = R.string.patients_reminder_everyone,
    )

    ReminderSummaryKind.POLICY -> if (policy == ReminderPolicy.PRIMARY_WITH_ESCALATION) {
        ReminderSummaryText(
            resource = R.string.patients_policy_primary_with_escalation,
            args = listOf(escalationDelayMinutes),
        )
    } else {
        ReminderSummaryText(resource = policy!!.labelRes())
    }
}

/**
 * The one-line reminder summary of the policy, rendered from
 * [reminderSummaryText]; null while the policy is not loaded.
 */
@Composable
internal fun reminderSummary(
    policy: ReminderPolicy?,
    primaryCaregiverName: String?,
    primaryCaregiverIsMe: Boolean,
    escalationDelayMinutes: Int = PatientRules.DEFAULT_ESCALATION_DELAY_MINUTES,
): String? {
    val text = reminderSummaryText(
        policy = policy,
        primaryFirstName = firstNameOf(primaryCaregiverName),
        primaryIsMe = primaryCaregiverIsMe,
        escalationDelayMinutes = escalationDelayMinutes,
    ) ?: return null
    return stringResource(text.resource, *text.args.toTypedArray())
}

/**
 * The policy option in a form, with the same words as the profile summary and
 * the current escalation delay (finding 20 of the UX audit).
 */
@Composable
internal fun reminderPolicyOption(policy: ReminderPolicy, escalationDelayMinutes: Int): String =
    when (policy) {
        ReminderPolicy.PRIMARY_WITH_ESCALATION -> stringResource(
            R.string.patients_policy_primary_with_escalation,
            escalationDelayMinutes,
        )

        ReminderPolicy.ALL_CAREGIVERS -> stringResource(R.string.patients_policy_all_caregivers)
        ReminderPolicy.ROTA -> stringResource(R.string.patients_policy_unavailable)
    }
