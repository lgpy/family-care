package io.github.lgpy.familycare.core.domain.circle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The patient rules from docs/domain/care-circle.md, case by case.
 *
 * The numbers are the M2 migration's check constraints; if one of these tests
 * changes, a server-side constraint has to change with it.
 */
class PatientRulesTest {

    private val circle = CareCircleId("circle-1")
    private val owner = CircleMember(
        membershipId = MembershipId("membership-owner"),
        userId = "user-owner",
        displayName = "Me",
        role = MemberRole.OWNER,
        isMe = true,
    )
    private val caregiver = CircleMember(
        membershipId = MembershipId("membership-caregiver"),
        userId = "user-caregiver",
        displayName = "Mom",
        role = MemberRole.CAREGIVER,
        isMe = false,
    )
    private val viewer = CircleMember(
        membershipId = MembershipId("membership-viewer"),
        userId = "user-viewer",
        displayName = "Aunt",
        role = MemberRole.VIEWER,
        isMe = false,
    )

    private fun patient(
        displayName: String = "Grandma",
        linkedUserId: String? = null,
        primaryCaregiverId: String? = null,
        reminderPolicy: ReminderPolicy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
        escalationDelayMinutes: Int = PatientRules.DEFAULT_ESCALATION_DELAY_MINUTES,
        missedWindowMinutes: Int = PatientRules.DEFAULT_MISSED_WINDOW_MINUTES,
        agreementNote: String? = null,
    ) = Patient(
        id = PatientId("patient-1"),
        circleId = circle,
        displayName = displayName,
        linkedUserId = linkedUserId,
        reminderPolicy = reminderPolicy,
        primaryCaregiverId = primaryCaregiverId,
        escalationDelayMinutes = escalationDelayMinutes,
        missedWindowMinutes = missedWindowMinutes,
        agreementNote = agreementNote,
    )

    // --- Defaults -----------------------------------------------------------

    @Test
    fun `a new patient defaults to primary with escalation and the documented delays`() {
        val newPatient = PatientRules.newPatient(
            id = PatientId("patient-1"),
            circleId = circle,
            displayName = "Grandma",
        )

        assertEquals(ReminderPolicy.PRIMARY_WITH_ESCALATION, newPatient.reminderPolicy)
        assertEquals(30, newPatient.escalationDelayMinutes)
        assertEquals(120, newPatient.missedWindowMinutes)
        assertNull(newPatient.primaryCaregiverId)
        assertNull(newPatient.linkedUserId)
        assertFalse(newPatient.isArchived)
    }

    @Test
    fun `a self-managed patient is their own primary caregiver`() {
        val newPatient = PatientRules.newPatient(
            id = PatientId("patient-1"),
            circleId = circle,
            displayName = "Mom",
            linkedUserId = "user-caregiver",
        )

        assertEquals("user-caregiver", newPatient.primaryCaregiverId)
    }

    @Test
    fun `the linked user wins over a chosen caregiver`() {
        val newPatient = PatientRules.newPatient(
            id = PatientId("patient-1"),
            circleId = circle,
            displayName = "Mom",
            linkedUserId = "user-caregiver",
            chosenPrimaryCaregiverId = "user-owner",
        )

        assertEquals("user-caregiver", newPatient.primaryCaregiverId)
    }

    @Test
    fun `a patient without a linked user keeps the chosen caregiver`() {
        val newPatient = PatientRules.newPatient(
            id = PatientId("patient-1"),
            circleId = circle,
            displayName = "Grandma",
            chosenPrimaryCaregiverId = "user-caregiver",
        )

        assertEquals("user-caregiver", newPatient.primaryCaregiverId)
    }

    @Test
    fun `the default primary caregiver helper follows the same rule`() {
        assertEquals("linked", PatientRules.defaultPrimaryCaregiver("linked", "chosen"))
        assertEquals("chosen", PatientRules.defaultPrimaryCaregiver(null, "chosen"))
        assertNull(PatientRules.defaultPrimaryCaregiver(null, null))
    }

    // --- Normalisation ------------------------------------------------------

    @Test
    fun `names are trimmed and a blank agreement note becomes null`() {
        val newPatient = PatientRules.newPatient(
            id = PatientId("patient-1"),
            circleId = circle,
            displayName = "  Grandma  ",
            agreementNote = "   ",
        )

        assertEquals("Grandma", newPatient.displayName)
        assertNull(newPatient.agreementNote)
    }

    @Test
    fun `a note with content is trimmed but kept`() {
        val newPatient = PatientRules.newPatient(
            id = PatientId("patient-1"),
            circleId = circle,
            displayName = "Grandma",
            agreementNote = "  Told Grandma about the app  ",
        )

        assertEquals("Told Grandma about the app", newPatient.agreementNote)
    }

    // --- Name length --------------------------------------------------------

    @Test
    fun `the name must be one to a hundred characters after trimming`() {
        assertFalse(PatientRules.isValidName(""))
        assertFalse(PatientRules.isValidName("   "))
        assertTrue(PatientRules.isValidName("a"))
        assertTrue(PatientRules.isValidName("a".repeat(100)))
        assertFalse(PatientRules.isValidName("a".repeat(101)))
    }

    @Test
    fun `a too long name is reported on the name field`() {
        val validation = patient(displayName = "a".repeat(101)).validate()

        assertEquals(setOf(CoreField.PATIENT_DISPLAY_NAME), validation.errors)
    }

    // --- Ranges -------------------------------------------------------------

    @Test
    fun `the escalation delay accepts exactly zero to 1440 minutes`() {
        assertEquals(setOf<CoreField>(), patient(escalationDelayMinutes = 0).validate().errors)
        assertEquals(setOf<CoreField>(), patient(escalationDelayMinutes = 1440).validate().errors)
        assertEquals(
            setOf(CoreField.PATIENT_ESCALATION_DELAY),
            patient(escalationDelayMinutes = -1).validate().errors,
        )
        assertEquals(
            setOf(CoreField.PATIENT_ESCALATION_DELAY),
            patient(escalationDelayMinutes = 1441).validate().errors,
        )
    }

    @Test
    fun `the missed window accepts exactly one to 1440 minutes`() {
        assertEquals(setOf<CoreField>(), patient(missedWindowMinutes = 1).validate().errors)
        assertEquals(setOf<CoreField>(), patient(missedWindowMinutes = 1440).validate().errors)
        assertEquals(
            setOf(CoreField.PATIENT_MISSED_WINDOW),
            patient(missedWindowMinutes = 0).validate().errors,
        )
        assertEquals(
            setOf(CoreField.PATIENT_MISSED_WINDOW),
            patient(missedWindowMinutes = 1441).validate().errors,
        )
    }

    @Test
    fun `the agreement note allows up to 2000 characters`() {
        assertEquals(
            setOf<CoreField>(),
            patient(agreementNote = "a".repeat(2000)).validate().errors,
        )
        assertEquals(
            setOf(CoreField.PATIENT_AGREEMENT_NOTE),
            patient(agreementNote = "a".repeat(2001)).validate().errors,
        )
    }

    @Test
    fun `a blank note is not an error even though the database check would allow it`() {
        assertTrue(patient(agreementNote = "   ").validate().isValid)
    }

    // --- Policies -----------------------------------------------------------

    @Test
    fun `rota is modelled but never offered or saved`() {
        assertFalse(ReminderPolicy.ROTA.isOffered)
        assertTrue(ReminderPolicy.PRIMARY_WITH_ESCALATION.isOffered)
        assertTrue(ReminderPolicy.ALL_CAREGIVERS.isOffered)
        assertEquals(
            listOf(ReminderPolicy.PRIMARY_WITH_ESCALATION, ReminderPolicy.ALL_CAREGIVERS),
            ReminderPolicy.offered,
        )

        assertEquals(
            setOf(CoreField.PATIENT_REMINDER_POLICY),
            patient(reminderPolicy = ReminderPolicy.ROTA).validate().errors,
        )
    }

    @Test
    fun `policies map from the database values and unknown ones are not guessed`() {
        assertEquals(
            ReminderPolicy.PRIMARY_WITH_ESCALATION,
            ReminderPolicy.fromServer("primary_with_escalation"),
        )
        assertEquals(ReminderPolicy.ALL_CAREGIVERS, ReminderPolicy.fromServer("all_caregivers"))
        assertEquals(ReminderPolicy.ROTA, ReminderPolicy.fromServer("rota"))
        assertNull(ReminderPolicy.fromServer("weekly"))
        assertNull(ReminderPolicy.fromServer(null))
    }

    // --- Circle relationships ----------------------------------------------

    @Test
    fun `a caregiver can be the primary caregiver`() {
        val validation = patient(primaryCaregiverId = caregiver.userId)
            .validateAgainst(listOf(owner, caregiver, viewer))

        assertTrue(validation.isValid)
    }

    @Test
    fun `a viewer cannot be the primary caregiver`() {
        val validation = patient(primaryCaregiverId = viewer.userId)
            .validateAgainst(listOf(owner, caregiver, viewer))

        assertEquals(setOf(CoreField.PATIENT_PRIMARY_CAREGIVER), validation.errors)
    }

    @Test
    fun `a primary caregiver outside the circle is rejected`() {
        val validation = patient(primaryCaregiverId = "user-stranger")
            .validateAgainst(listOf(owner, caregiver))

        assertEquals(setOf(CoreField.PATIENT_PRIMARY_CAREGIVER), validation.errors)
    }

    @Test
    fun `a linked user must be a member of the circle`() {
        assertEquals(
            setOf<CoreField>(),
            patient(linkedUserId = caregiver.userId)
                .validateAgainst(listOf(owner, caregiver)).errors,
        )
        assertEquals(
            setOf(CoreField.PATIENT_LINKED_USER),
            patient(linkedUserId = "user-stranger")
                .validateAgainst(listOf(owner, caregiver)).errors,
        )
    }

    @Test
    fun `the relationship rules build on the intrinsic ones`() {
        val validation = patient(displayName = "")
            .validateAgainst(listOf(owner, caregiver))

        assertEquals(setOf(CoreField.PATIENT_DISPLAY_NAME), validation.errors)
    }

    @Test
    fun `an archived patient is still a valid patient`() {
        val archived = patient().copy(archivedAt = kotlin.time.Instant.parse("2026-11-18T10:00:00Z"))

        assertTrue(archived.isArchived)
        assertTrue(archived.validate().isValid)
    }
}
