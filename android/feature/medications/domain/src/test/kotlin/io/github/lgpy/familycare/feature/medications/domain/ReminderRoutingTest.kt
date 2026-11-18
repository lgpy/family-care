package io.github.lgpy.familycare.feature.medications.domain

import io.github.lgpy.familycare.core.domain.circle.CareCircleId
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reminder routing (docs/domain/care-circle.md) and the pure planning step of
 * the M4 scheduler.
 */
class ReminderRoutingTest {

    private val primary = "user-primary"
    private val other = "user-other"
    private val viewer = "user-viewer"

    private fun patient(
        policy: ReminderPolicy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
        primaryCaregiverId: String? = primary,
    ): Patient = Patient(
        id = Fixtures.patientId,
        circleId = CareCircleId("circle-1"),
        displayName = "Grandma",
        reminderPolicy = policy,
        primaryCaregiverId = primaryCaregiverId,
    )

    @Test
    fun `primary-with-escalation reminds only the primary caregiver`() {
        val patient = patient()
        val medication = Fixtures.medication()

        assertTrue(
            ReminderRouting.shouldRemindOnThisDevice(
                patient, medication, primary, MemberRole.CAREGIVER,
            ),
        )
        assertFalse(
            ReminderRouting.shouldRemindOnThisDevice(
                patient, medication, other, MemberRole.CAREGIVER,
            ),
        )
    }

    @Test
    fun `a medication override replaces the patient policy`() {
        val patient = patient()
        val medication = Fixtures.medication(
            reminderPolicy = ReminderPolicy.ALL_CAREGIVERS,
        )

        assertTrue(
            ReminderRouting.shouldRemindOnThisDevice(
                patient, medication, other, MemberRole.CAREGIVER,
            ),
        )
    }

    @Test
    fun `a medication primary override replaces the patient primary`() {
        val patient = patient()
        val medication = Fixtures.medication(primaryCaregiverId = other)

        assertFalse(
            ReminderRouting.shouldRemindOnThisDevice(
                patient, medication, primary, MemberRole.OWNER,
            ),
        )
        assertTrue(
            ReminderRouting.shouldRemindOnThisDevice(
                patient, medication, other, MemberRole.CAREGIVER,
            ),
        )
    }

    @Test
    fun `all-caregivers reminds every owner or caregiver`() {
        val patient = patient(policy = ReminderPolicy.ALL_CAREGIVERS, primaryCaregiverId = null)
        val medication = Fixtures.medication()

        assertTrue(
            ReminderRouting.shouldRemindOnThisDevice(
                patient, medication, other, MemberRole.OWNER,
            ),
        )
        assertFalse(
            ReminderRouting.shouldRemindOnThisDevice(
                patient, medication, viewer, MemberRole.VIEWER,
            ),
        )
    }

    @Test
    fun `viewers and signed-out users are never reminded`() {
        val patient = patient(policy = ReminderPolicy.ALL_CAREGIVERS)
        val medication = Fixtures.medication()

        assertFalse(
            ReminderRouting.shouldRemindOnThisDevice(
                patient, medication, viewer, MemberRole.VIEWER,
            ),
        )
        assertFalse(
            ReminderRouting.shouldRemindOnThisDevice(patient, medication, null, MemberRole.OWNER),
        )
        assertFalse(
            ReminderRouting.shouldRemindOnThisDevice(patient, medication, primary, null),
        )
    }

    @Test
    fun `a missing primary caregiver means nobody is reminded locally`() {
        val patient = patient(primaryCaregiverId = null)
        val medication = Fixtures.medication()

        assertFalse(
            ReminderRouting.shouldRemindOnThisDevice(
                patient, medication, primary, MemberRole.OWNER,
            ),
        )
    }

    @Test
    fun `rota schedules nothing locally`() {
        val patient = patient(policy = ReminderPolicy.ROTA)
        val medication = Fixtures.medication()

        assertFalse(
            ReminderRouting.shouldRemindOnThisDevice(
                patient, medication, primary, MemberRole.OWNER,
            ),
        )
    }
}

class ReminderPlanningTest {

    private val patientId = PatientId("patient-1")
    private val otherPatientId = PatientId("patient-2")
    private val myUserId = "user-primary"

    private fun patient(
        id: PatientId = patientId,
        policy: ReminderPolicy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
        primary: String? = myUserId,
        archivedAt: kotlin.time.Instant? = null,
    ): Patient = Patient(
        id = id,
        circleId = CareCircleId("circle-1"),
        displayName = "Grandma",
        reminderPolicy = policy,
        primaryCaregiverId = primary,
        archivedAt = archivedAt,
    )

    private fun plan(
        now: String,
        sources: List<ReminderSource>,
        patients: Map<PatientId, Patient> = mapOf(patientId to patient()),
        entries: List<DoseLogEntry> = emptyList(),
        role: MemberRole? = MemberRole.CAREGIVER,
        horizon: Duration = ReminderPlanning.DEFAULT_HORIZON,
    ): List<ReminderGroup> = ReminderPlanning.plan(
        now = Instant.parse(now),
        sources = sources,
        patients = patients,
        entries = entries,
        myUserId = myUserId,
        myRole = role,
        horizon = horizon,
    )

    @Test
    fun `plans the next 48 hours grouped by patient and wall clock`() {
        val source = ReminderSource(
            medication = Fixtures.medication(),
            regimens = listOf(Fixtures.regimen()),
        )

        val groups = plan("2026-11-16T07:00:00Z", listOf(source))

        // 08:00 on the 16th and 17th inside 48 h; the 18th 08:00 is after the
        // horizon (now + 48 h = 18th 07:00Z).
        assertEquals(2, groups.size)
        assertEquals(Instant.parse("2026-11-16T08:00:00Z"), groups[0].instant)
        assertEquals(Instant.parse("2026-11-17T08:00:00Z"), groups[1].instant)
        assertEquals(1, groups[0].slotCount)
    }

    @Test
    fun `groups medications that share a wall clock into one reminder`() {
        val secondMedication = Fixtures.medication(id = MedicationId("med-2"), name = "Aspirin")
        val sources = listOf(
            ReminderSource(Fixtures.medication(), listOf(Fixtures.regimen())),
            ReminderSource(
                secondMedication,
                listOf(
                    Fixtures.regimen(
                        id = RegimenId("reg-2"),
                        medicationId = secondMedication.id,
                    ),
                ),
            ),
        )

        val groups = plan("2026-11-16T07:00:00Z", sources)

        assertEquals(2, groups.size)
        assertEquals(2, groups[0].slotCount)
    }

    @Test
    fun `an effective confirmation removes the slot but a skip counts too`() {
        val source = ReminderSource(Fixtures.medication(), listOf(Fixtures.regimen()))
        val taken = Fixtures.entry(
            scheduledLocal = "2026-11-16T08:00",
            actualAt = "2026-11-16T08:02:00Z",
        )

        val groups = plan("2026-11-16T07:00:00Z", listOf(source), entries = listOf(taken))

        assertEquals(1, groups.size)
        assertEquals(Instant.parse("2026-11-17T08:00:00Z"), groups.single().instant)
    }

    @Test
    fun `routing filters the plan by role and primary caregiver`() {
        val source = ReminderSource(Fixtures.medication(), listOf(Fixtures.regimen()))
        val otherPrimary = mapOf(patientId to patient(primary = "someone-else"))

        assertTrue(plan("2026-11-16T07:00:00Z", listOf(source), otherPrimary).isEmpty())
        assertTrue(
            plan("2026-11-16T07:00:00Z", listOf(source), role = MemberRole.VIEWER).isEmpty(),
        )
    }

    @Test
    fun `stopped medications and archived patients are not planned`() {
        val stopped = ReminderSource(
            Fixtures.medication(stoppedAt = Instant.parse("2026-11-15T00:00:00Z")),
            listOf(Fixtures.regimen()),
        )
        val archived = mapOf(
            patientId to patient(
                archivedAt = kotlin.time.Instant.parse("2026-11-15T00:00:00Z"),
            ),
        )

        assertTrue(plan("2026-11-16T07:00:00Z", listOf(stopped)).isEmpty())
        assertTrue(plan("2026-11-16T07:00:00Z", listOf(ReminderSource(
            Fixtures.medication(),
            listOf(Fixtures.regimen()),
        )), archived).isEmpty())
    }

    @Test
    fun `DST spring-forward keeps the wall clock and moves the instant`() {
        // Europe/Lisbon springs forward at 01:00 on 2026-03-29. A 01:30 dose
        // that day does not exist; java.time resolves it to 02:30+01:00, i.e.
        // 01:30Z, and the plan keeps its 01:30 wall-clock identity.
        val source = ReminderSource(
            medication = Fixtures.medication(),
            regimens = listOf(
                Fixtures.regimen(
                    startDate = LocalDate.parse("2026-03-20"),
                    times = listOf(Fixtures.time("01:30", 1)),
                ),
            ),
        )

        val groups = plan(
            now = "2026-03-28T22:00:00Z",
            sources = listOf(source),
            horizon = Duration.ofHours(48),
        )

        val dstGroup = groups.firstOrNull {
            it.scheduledLocal == java.time.LocalDateTime.parse("2026-03-29T01:30")
        }
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), dstGroup?.instant)
    }

    @Test
    fun `request codes are stable and different per patient and instant`() {
        val now = Instant.parse("2026-11-16T08:00:00Z")
        val a = ReminderIds.requestCode(patientId, now)
        val b = ReminderIds.requestCode(patientId, now)
        val c = ReminderIds.requestCode(otherPatientId, now)

        assertEquals(a, b)
        assertTrue(a != c)
        assertTrue(a >= 0)
    }
}
