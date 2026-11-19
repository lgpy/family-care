package io.github.lgpy.familycare.ui.patients

import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy

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
