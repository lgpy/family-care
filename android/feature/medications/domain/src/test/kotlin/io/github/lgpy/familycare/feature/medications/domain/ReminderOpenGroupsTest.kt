package io.github.lgpy.familycare.feature.medications.domain

import io.github.lgpy.familycare.core.domain.circle.CareCircleId
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which reminders of slots whose time has passed must stay (the alarm that is
 * due and the notification that is showing) until the dose is confirmed or
 * missed. The scheduler only applies this decision.
 */
class ReminderOpenGroupsTest {

    private val patientId = PatientId("patient-1")
    private val myUserId = "user-primary"

    private fun patient(
        missedWindowMinutes: Int = 120,
        archivedAt: kotlin.time.Instant? = null,
    ): Patient = Patient(
        id = patientId,
        circleId = CareCircleId("circle-1"),
        displayName = "Grandma",
        reminderPolicy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
        primaryCaregiverId = myUserId,
        missedWindowMinutes = missedWindowMinutes,
        archivedAt = archivedAt,
    )

    private fun source(
        medication: Medication = Fixtures.medication(),
        regimen: Regimen = Fixtures.regimen(),
    ) = ReminderSource(medication, listOf(regimen))

    private fun open(
        now: String,
        sources: List<ReminderSource> = listOf(source()),
        patients: Map<PatientId, Patient> = mapOf(patientId to patient()),
        entries: List<DoseLogEntry> = emptyList(),
        role: MemberRole? = MemberRole.CAREGIVER,
        userId: String? = myUserId,
    ): List<ReminderGroup> = ReminderPlanning.openGroups(
        now = Instant.parse(now),
        sources = sources,
        patients = patients,
        entries = entries,
        myUserId = userId,
        myRole = role,
    )

    private fun plan(now: String): List<ReminderGroup> = ReminderPlanning.plan(
        now = Instant.parse(now),
        sources = listOf(source()),
        patients = mapOf(patientId to patient()),
        entries = emptyList(),
        myUserId = myUserId,
        myRole = MemberRole.CAREGIVER,
    )

    @Test
    fun `a slot that is due and unconfirmed keeps its group open`() {
        val groups = open("2026-11-16T08:05:00Z")

        assertEquals(1, groups.size)
        assertEquals(Instant.parse("2026-11-16T08:00:00Z"), groups[0].instant)
        assertEquals(1, groups[0].slotCount)
    }

    @Test
    fun `a slot that has not started yet is not open, the plan owns it`() {
        assertTrue(open("2026-11-16T07:59:00Z").isEmpty())
    }

    @Test
    fun `the group stays open until the missed window has passed`() {
        assertEquals(1, open("2026-11-16T10:00:00Z").size) // exactly 120 min: still overdue
        assertTrue(open("2026-11-16T10:00:01Z").isEmpty()) // missed
    }

    @Test
    fun `the missed window of the patient decides`() {
        val patients = mapOf(patientId to patient(missedWindowMinutes = 30))

        assertEquals(1, open("2026-11-16T08:30:00Z", patients = patients).size)
        assertTrue(open("2026-11-16T08:31:00Z", patients = patients).isEmpty())
    }

    @Test
    fun `a confirmed slot closes the group, a skipped one too`() {
        val taken = Fixtures.entry(scheduledLocal = "2026-11-16T08:00")
        val skipped = Fixtures.skipped()

        assertTrue(open("2026-11-16T08:05:00Z", entries = listOf(taken)).isEmpty())
        assertTrue(open("2026-11-16T08:05:00Z", entries = listOf(skipped)).isEmpty())
    }

    @Test
    fun `a partly confirmed group stays open with the remaining slots only`() {
        val second = Fixtures.medication(id = MedicationId("med-2"), name = "Aspirin")
        val sources = listOf(
            source(),
            source(second, Fixtures.regimen(id = RegimenId("reg-2"), medicationId = second.id)),
        )
        val taken = Fixtures.entry(scheduledLocal = "2026-11-16T08:00")

        val groups = open("2026-11-16T08:05:00Z", sources = sources, entries = listOf(taken))

        assertEquals(1, groups.size)
        assertEquals(listOf(second.id), groups[0].slots.map { it.medicationId })
    }

    @Test
    fun `a stopped medication or an archived or unknown patient closes the group`() {
        val stopped = source(
            medication = Fixtures.medication(stoppedAt = Instant.parse("2026-11-16T08:02:00Z")),
        )
        assertTrue(open("2026-11-16T08:05:00Z", sources = listOf(stopped)).isEmpty())

        val archived = mapOf(
            patientId to patient(archivedAt = kotlin.time.Instant.parse("2026-11-10T00:00:00Z")),
        )
        assertTrue(open("2026-11-16T08:05:00Z", patients = archived).isEmpty())

        assertTrue(open("2026-11-16T08:05:00Z", patients = emptyMap()).isEmpty())
    }

    @Test
    fun `routing still applies, a viewer or a non-primary caregiver is not reminded`() {
        assertTrue(open("2026-11-16T08:05:00Z", role = MemberRole.VIEWER).isEmpty())
        assertTrue(open("2026-11-16T08:05:00Z", role = null).isEmpty())
        assertTrue(open("2026-11-16T08:05:00Z", userId = null).isEmpty())
        assertTrue(open("2026-11-16T08:05:00Z", userId = "someone-else").isEmpty())
    }

    @Test
    fun `slots of different wall clocks form separate open groups`() {
        val regimen = Fixtures.regimen(times = Fixtures.times("08:00" to "1", "09:00" to "1"))

        val groups = open("2026-11-16T09:10:00Z", sources = listOf(source(regimen = regimen)))

        assertEquals(
            listOf(Instant.parse("2026-11-16T08:00:00Z"), Instant.parse("2026-11-16T09:00:00Z")),
            groups.map { it.instant },
        )
    }

    @Test
    fun `an open group has the id the plan gave the same group before it was due`() {
        val planned = plan("2026-11-16T07:00:00Z").first()

        val open = open("2026-11-16T08:05:00Z").single()

        assertEquals(ReminderIds.requestCode(planned), ReminderIds.requestCode(open))
    }

    @Test
    fun `the sweep cancels only ids that are neither planned nor open`() {
        val future = plan("2026-11-16T08:05:00Z").first()
        val due = open("2026-11-16T08:05:00Z").single()
        val futureId = ReminderIds.requestCode(future)
        val dueId = ReminderIds.requestCode(due)
        val unknownId = 42

        val cancelled = ReminderPlanning.idsToCancel(
            candidateIds = setOf(futureId, dueId, unknownId),
            planned = listOf(future),
            open = listOf(due),
        )
        assertEquals(setOf(unknownId), cancelled)

        // Once the group is confirmed or missed it is no longer open: it goes.
        val afterwards = ReminderPlanning.idsToCancel(
            candidateIds = setOf(futureId, dueId, unknownId),
            planned = listOf(future),
            open = emptyList(),
        )
        assertEquals(setOf(dueId, unknownId), afterwards)
    }
}
