package io.github.lgpy.familycare.core.data.circle

import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Row mapping: database text and PostgREST JSON into domain types, without a
 * device, a database or a network.
 */
class CircleRowsTest {

    private fun patientRow(
        reminderPolicy: String = "primary_with_escalation",
        archivedAt: String? = null,
        birthYear: Long? = null,
    ) = PatientRow(
        id = "patient-1",
        circleId = "circle-1",
        displayName = "Grandma",
        linkedUserId = null,
        birthYear = birthYear,
        reminderPolicy = reminderPolicy,
        primaryCaregiverId = "user-caregiver",
        escalationDelayMinutes = 45,
        missedWindowMinutes = 90,
        agreementNote = "Told Grandma about the app",
        archivedAt = archivedAt,
    )

    @Test
    fun `a patient row becomes the domain patient`() {
        val patient = patientRow().toPatient()!!

        assertEquals("patient-1", patient.id.value)
        assertEquals("circle-1", patient.circleId.value)
        assertEquals("Grandma", patient.displayName)
        assertEquals(ReminderPolicy.PRIMARY_WITH_ESCALATION, patient.reminderPolicy)
        assertEquals("user-caregiver", patient.primaryCaregiverId)
        assertEquals(45, patient.escalationDelayMinutes)
        assertEquals(90, patient.missedWindowMinutes)
        assertEquals("Told Grandma about the app", patient.agreementNote)
        assertNull(patient.linkedUserId)
        assertNull(patient.archivedAt)
        assertFalse(patient.isArchived)
        assertNull(patient.birthYear)
        assertEquals(1942, patientRow(birthYear = 1942).toPatient()!!.birthYear)
    }

    @Test
    fun `an archive stamp is parsed`() {
        val patient = patientRow(archivedAt = "2026-11-18T10:00:00.123456Z").toPatient()!!

        assertTrue(patient.isArchived)
        assertEquals(Instant.parse("2026-11-18T10:00:00.123456Z"), patient.archivedAt)
    }

    @Test
    fun `a policy the app does not know skips the row instead of crashing`() {
        assertNull(patientRow(reminderPolicy = "weekly").toPatient())
    }

    @Test
    fun `postgres timestamps with a space separator and short offset are parsed`() {
        assertEquals(
            Instant.parse("2026-11-18T10:00:00Z"),
            parseServerInstant("2026-11-18 10:00:00+00"),
        )
    }

    @Test
    fun `unparseable or missing timestamps are null`() {
        assertNull(parseServerInstant(null))
        assertNull(parseServerInstant(""))
        assertNull(parseServerInstant("not a timestamp"))
    }

    // --- Invitations --------------------------------------------------------

    private fun invitationJson(
        role: String = "caregiver",
        withExpiry: Boolean = true,
    ) = JsonObject(
        buildMap {
            put("id", JsonPrimitive("invitation-1"))
            put("circle_id", JsonPrimitive("circle-1"))
            put("email", JsonPrimitive("mom@example.test"))
            put("role", JsonPrimitive(role))
            put("created_at", JsonPrimitive("2026-11-18T10:00:00Z"))
            if (withExpiry) put("expires_at", JsonPrimitive("2026-11-25T10:00:00Z"))
            put("accepted_at", kotlinx.serialization.json.JsonNull)
            put("revoked_at", kotlinx.serialization.json.JsonNull)
        },
    )

    @Test
    fun `an invitation row becomes the domain invitation`() {
        val invitation = invitationFromJson(invitationJson())!!

        assertEquals("invitation-1", invitation.id.value)
        assertEquals("circle-1", invitation.circleId.value)
        assertEquals("mom@example.test", invitation.email)
        assertEquals(MemberRole.CAREGIVER, invitation.role)
        assertEquals(Instant.parse("2026-11-25T10:00:00Z"), invitation.expiresAt)
        assertNull(invitation.acceptedAt)
        assertNull(invitation.revokedAt)
        assertTrue(invitation.isPending(Instant.parse("2026-11-18T12:00:00Z")))
    }

    @Test
    fun `an unknown role or a missing email makes the invitation unusable`() {
        assertNull(invitationFromJson(invitationJson(role = "owner")))
        assertNull(
            invitationFromJson(
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive("invitation-1"),
                        "circle_id" to JsonPrimitive("circle-1"),
                        "role" to JsonPrimitive("caregiver"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a missing expiry does not make the invitation invalid`() {
        val invitation = invitationFromJson(invitationJson(withExpiry = false))!!

        assertNull(invitation.expiresAt)
        assertTrue(invitation.isPending(Instant.parse("2026-11-18T12:00:00Z")))
    }
}
