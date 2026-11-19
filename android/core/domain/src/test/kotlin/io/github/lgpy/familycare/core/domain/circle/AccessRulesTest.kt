package io.github.lgpy.familycare.core.domain.circle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/**
 * The client half of `core.can_circle(circle_id, action)`.
 *
 * The cases are the ones in docs/security-and-privacy.md §3: an owner does
 * everything, a caregiver writes but cannot manage, a viewer only reads, and a
 * user in no circle does nothing at all.
 */
class AccessRulesTest {

    @Test
    fun `an owner can read write and manage`() {
        assertTrue(MemberRole.OWNER.canRead)
        assertTrue(MemberRole.OWNER.canWrite)
        assertTrue(MemberRole.OWNER.canManage)
    }

    @Test
    fun `a caregiver can read and write but not manage`() {
        assertTrue(MemberRole.CAREGIVER.canRead)
        assertTrue(MemberRole.CAREGIVER.canWrite)
        assertFalse(MemberRole.CAREGIVER.canManage)
    }

    @Test
    fun `a viewer can only read`() {
        assertTrue(MemberRole.VIEWER.canRead)
        assertFalse(MemberRole.VIEWER.canWrite)
        assertFalse(MemberRole.VIEWER.canManage)
    }

    @Test
    fun `no membership means no permission at all`() {
        val noRole: MemberRole? = null

        assertFalse(noRole.canRead)
        assertFalse(noRole.canWrite)
        assertFalse(noRole.canManage)
        assertFalse(noRole.can(CircleAction.READ))
        assertFalse(noRole.can(CircleAction.WRITE))
        assertFalse(noRole.can(CircleAction.MANAGE))
    }

    @Test
    fun `an archived patient is read-only for everyone`() {
        val patient = Patient(
            id = PatientId("patient-1"),
            circleId = CareCircleId("circle-1"),
            displayName = "Avó",
        )

        assertTrue(MemberRole.OWNER.canWrite(patient))
        assertTrue(MemberRole.CAREGIVER.canWrite(patient))

        val archived = patient.copy(archivedAt = Instant.parse("2026-11-19T10:00:00Z"))
        assertTrue(archived.isArchived)
        MemberRole.entries.forEach { role ->
            assertFalse(role.canWrite(archived), "$role must not write an archived patient")
        }
    }

    @Test
    fun `every action maps to the server value`() {
        assertEquals("read", CircleAction.READ.serverValue)
        assertEquals("write", CircleAction.WRITE.serverValue)
        assertEquals("manage", CircleAction.MANAGE.serverValue)
    }

    @Test
    fun `roles map from and to the database values`() {
        assertEquals(MemberRole.OWNER, MemberRole.fromServer("owner"))
        assertEquals(MemberRole.CAREGIVER, MemberRole.fromServer("caregiver"))
        assertEquals(MemberRole.VIEWER, MemberRole.fromServer("viewer"))
        assertEquals("owner", MemberRole.OWNER.serverValue)
    }

    @Test
    fun `an unknown or missing role is not guessed`() {
        assertNull(MemberRole.fromServer("admin"))
        assertNull(MemberRole.fromServer(null))
    }

    @Test
    fun `only caregiver and viewer can be invited`() {
        assertEquals(listOf(MemberRole.CAREGIVER, MemberRole.VIEWER), MemberRole.invitables)
        assertFalse(MemberRole.OWNER in MemberRole.invitables)
    }
}
