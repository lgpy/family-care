package io.github.lgpy.familycare.ui.patients

import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.core.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The profile head's reminder line: one short phrase per policy instead of the
 * four-line "reminders: Primary with escalation · Primary: Leonardo Gomes".
 */
class ReminderSummaryTest {

    @Test
    fun `primary with escalation names the primary caregiver first`() {
        assertEquals(
            ReminderSummaryKind.PRIMARY_FIRST,
            reminderSummaryKind(
                policy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
                primaryFirstName = "Leonardo",
                primaryIsMe = false,
            ),
        )
    }

    @Test
    fun `primary with escalation says you when the primary is the signed-in user`() {
        assertEquals(
            ReminderSummaryKind.YOU_FIRST,
            reminderSummaryKind(
                policy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
                primaryFirstName = "Leonardo",
                primaryIsMe = true,
            ),
        )
    }

    @Test
    fun `primary with escalation without a name keeps the policy label`() {
        assertEquals(
            ReminderSummaryKind.POLICY,
            reminderSummaryKind(
                policy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
                primaryFirstName = null,
                primaryIsMe = false,
            ),
        )
    }

    @Test
    fun `primary only names the primary caregiver`() {
        assertEquals(
            ReminderSummaryKind.ONLY_PRIMARY,
            reminderSummaryKind(
                policy = ReminderPolicy.PRIMARY_ONLY,
                primaryFirstName = "Leonardo",
                primaryIsMe = false,
            ),
        )
    }

    @Test
    fun `primary only says you when the primary is the signed-in user`() {
        assertEquals(
            ReminderSummaryKind.ONLY_YOU,
            reminderSummaryKind(
                policy = ReminderPolicy.PRIMARY_ONLY,
                primaryFirstName = "Leonardo",
                primaryIsMe = true,
            ),
        )
    }

    @Test
    fun `primary only without a name keeps the policy label`() {
        assertEquals(
            ReminderSummaryKind.POLICY,
            reminderSummaryKind(
                policy = ReminderPolicy.PRIMARY_ONLY,
                primaryFirstName = null,
                primaryIsMe = false,
            ),
        )
    }

    @Test
    fun `all caregivers says everyone is reminded`() {
        assertEquals(
            ReminderSummaryKind.EVERYONE,
            reminderSummaryKind(
                policy = ReminderPolicy.ALL_CAREGIVERS,
                primaryFirstName = null,
                primaryIsMe = false,
            ),
        )
    }

    @Test
    fun `a policy without a short phrase keeps its label`() {
        assertEquals(
            ReminderSummaryKind.POLICY,
            reminderSummaryKind(
                policy = ReminderPolicy.ROTA,
                primaryFirstName = null,
                primaryIsMe = false,
            ),
        )
    }

    @Test
    fun `an unloaded patient has no reminder line`() {
        assertNull(reminderSummaryKind(policy = null, primaryFirstName = null, primaryIsMe = false))
    }

    @Test
    fun `the first name is the first word of the display name`() {
        assertEquals("Leonardo", firstNameOf("Leonardo Gomes"))
        assertEquals("Maria", firstNameOf("  Maria  "))
        assertEquals("Ana", firstNameOf("Ana"))
    }

    @Test
    fun `a missing or blank display name has no first name`() {
        assertNull(firstNameOf(null))
        assertNull(firstNameOf(""))
        assertNull(firstNameOf("   "))
    }

    @Test
    fun `a named primary and the delay share one sentence`() {
        val text = reminderSummaryText(
            policy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
            primaryFirstName = "Leonardo",
            primaryIsMe = false,
            escalationDelayMinutes = 45,
        )

        assertEquals(R.string.patients_reminder_primary_first, text?.resource)
        assertEquals(listOf("Leonardo", 45), text?.args)
    }

    @Test
    fun `the signed-in user is named you and keeps the delay`() {
        val text = reminderSummaryText(
            policy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
            primaryFirstName = "Leonardo",
            primaryIsMe = true,
            escalationDelayMinutes = 30,
        )

        assertEquals(R.string.patients_reminder_you_first, text?.resource)
        assertEquals(listOf(30), text?.args)
    }

    @Test
    fun `a primary with no name still describes the delay`() {
        val text = reminderSummaryText(
            policy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
            primaryFirstName = null,
            primaryIsMe = false,
            escalationDelayMinutes = 20,
        )

        assertEquals(R.string.patients_policy_primary_with_escalation, text?.resource)
        assertEquals(listOf(20), text?.args)
    }

    @Test
    fun `every policy has its own summary`() {
        assertEquals(
            ReminderSummaryText(R.string.patients_reminder_everyone),
            reminderSummaryText(ReminderPolicy.ALL_CAREGIVERS, null, false),
        )
        assertEquals(
            ReminderSummaryText(
                R.string.patients_reminder_only_primary,
                listOf("Leonardo"),
            ),
            reminderSummaryText(ReminderPolicy.PRIMARY_ONLY, "Leonardo", false),
        )
        assertEquals(
            ReminderSummaryText(R.string.patients_reminder_only_you),
            reminderSummaryText(ReminderPolicy.PRIMARY_ONLY, "Leonardo", true),
        )
        assertEquals(
            ReminderSummaryText(R.string.patients_policy_primary_only),
            reminderSummaryText(ReminderPolicy.PRIMARY_ONLY, null, false),
        )
        assertEquals(
            ReminderSummaryText(R.string.patients_policy_unavailable),
            reminderSummaryText(ReminderPolicy.ROTA, null, false),
        )
        assertNull(reminderSummaryText(null, null, false))
    }
}
