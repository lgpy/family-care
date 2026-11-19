package io.github.lgpy.familycare.ui.patients

import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
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
}
