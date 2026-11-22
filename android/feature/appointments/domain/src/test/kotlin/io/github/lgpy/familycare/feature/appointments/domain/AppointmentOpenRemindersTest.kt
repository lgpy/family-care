package io.github.lgpy.familycare.feature.appointments.domain

import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.PatientId
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which appointment reminders whose instant has passed must stay (the showing
 * notification, the due alarm) until the appointment has started or ended, was
 * cancelled, moved, or no longer concerns this phone.
 */
class AppointmentOpenRemindersTest {

    private val lisbon = ZoneId.of("Europe/Lisbon")

    private fun appointment(
        id: String = "appointment-1",
        startsLocal: LocalDateTime = LocalDateTime.of(2026, 11, 20, 10, 30),
        durationMinutes: Int? = null,
        companionUserId: String? = null,
        cancelledAt: Instant? = null,
    ) = Appointment(
        id = AppointmentId(id),
        patientId = PatientId("patient-1"),
        kind = AppointmentKind.CONSULTATION,
        startsLocal = startsLocal,
        timeZone = lisbon,
        durationMinutes = durationMinutes,
        companionUserId = companionUserId,
        cancelledAt = cancelledAt,
    )

    private fun open(
        now: String,
        appointments: List<Appointment> = listOf(appointment()),
        myUserId: String? = "u1",
        myRole: MemberRole? = MemberRole.OWNER,
    ): List<AppointmentReminder> = AppointmentReminderPlanning.openReminders(
        now = Instant.parse(now),
        appointments = appointments,
        myUserId = myUserId,
        myRole = myRole,
    )

    private fun plan(now: String, appointments: List<Appointment> = listOf(appointment())) =
        AppointmentReminderPlanning.plan(
            now = Instant.parse(now),
            appointments = appointments,
            myUserId = "u1",
            myRole = MemberRole.OWNER,
        )

    @Test
    fun `the evening reminder stays open the next morning until the start`() {
        // The evening reminder fired 2026-11-19T20:00Z; it is 09:00 on the day.
        val reminders = open("2026-11-20T09:00:00Z")

        assertEquals(listOf(AppointmentReminderKind.EVENING_BEFORE), reminders.map { it.kind })
    }

    @Test
    fun `both reminders stay open between the hour-before reminder and the start`() {
        val reminders = open("2026-11-20T09:45:00Z")

        assertEquals(
            setOf(AppointmentReminderKind.EVENING_BEFORE, AppointmentReminderKind.HOUR_BEFORE),
            reminders.map { it.kind }.toSet(),
        )
    }

    @Test
    fun `nothing is open before the first reminder`() {
        assertTrue(open("2026-11-19T19:59:00Z").isEmpty())
    }

    @Test
    fun `the reminder is gone once the start has passed`() {
        assertEquals(2, open("2026-11-20T10:30:00Z").size) // exactly at the start
        assertTrue(open("2026-11-20T10:30:01Z").isEmpty())
    }

    @Test
    fun `with a duration the reminder stays until the end`() {
        val appointments = listOf(appointment(durationMinutes = 45))

        assertEquals(2, open("2026-11-20T11:15:00Z", appointments).size)
        assertTrue(open("2026-11-20T11:15:01Z", appointments).isEmpty())
    }

    @Test
    fun `a cancelled appointment has nothing open`() {
        val cancelled = appointment(cancelledAt = Instant.parse("2026-11-19T21:00:00Z"))

        assertTrue(open("2026-11-20T09:00:00Z", listOf(cancelled)).isEmpty())
    }

    @Test
    fun `a deleted appointment has nothing open`() {
        assertTrue(open("2026-11-20T09:00:00Z", emptyList()).isEmpty())
    }

    @Test
    fun `a moved appointment keeps nothing of its old times`() {
        val now = "2026-11-20T09:00:00Z"
        val oldIds = open(now, listOf(appointment()))
            .map { AppointmentReminderIds.requestCode(it) }
        assertEquals(1, oldIds.size)

        val moved = listOf(appointment(startsLocal = LocalDateTime.of(2026, 11, 25, 10, 30)))
        val cancel = AppointmentReminderPlanning.idsToCancel(
            candidateIds = oldIds,
            planned = plan(now, moved),
            open = open(now, moved),
        )

        assertEquals(oldIds.toSet(), cancel)
    }

    @Test
    fun `routing still applies`() {
        assertTrue(open("2026-11-20T09:00:00Z", myRole = MemberRole.VIEWER).isEmpty())
        assertTrue(open("2026-11-20T09:00:00Z", myRole = null).isEmpty())

        val withCompanion = listOf(appointment(companionUserId = "someone-else"))
        assertTrue(open("2026-11-20T09:00:00Z", withCompanion).isEmpty())
        assertEquals(1, open("2026-11-20T09:00:00Z", withCompanion, myUserId = "someone-else").size)
    }

    @Test
    fun `an open reminder has the id the plan gave it before it was due`() {
        val planned = plan("2026-11-19T10:00:00Z")
            .first { it.kind == AppointmentReminderKind.EVENING_BEFORE }

        val open = open("2026-11-20T09:00:00Z").single()

        assertEquals(
            AppointmentReminderIds.requestCode(planned),
            AppointmentReminderIds.requestCode(open),
        )
    }

    @Test
    fun `the sweep keeps planned and open ids and cancels the rest`() {
        val now = "2026-11-20T09:00:00Z"
        val planned = plan(now)
        val open = open(now)
        val plannedId = AppointmentReminderIds.requestCode(planned.single())
        val openId = AppointmentReminderIds.requestCode(open.single())

        val cancelled = AppointmentReminderPlanning.idsToCancel(
            candidateIds = setOf(plannedId, openId, 7),
            planned = planned,
            open = open,
        )

        assertEquals(setOf(7), cancelled)
    }
}
