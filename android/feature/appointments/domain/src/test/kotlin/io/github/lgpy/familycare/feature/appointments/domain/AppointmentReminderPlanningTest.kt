package io.github.lgpy.familycare.feature.appointments.domain

import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.PatientId
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The reminder plan (docs/domain/appointments.md "Reminders"): who is
 * reminded, the two instants, and what is skipped.
 */
class AppointmentReminderPlanningTest {

    private val lisbon = ZoneId.of("Europe/Lisbon")

    /** Fixed "now": 2026-11-18 10:00 in Lisbon (WET, UTC+0). */
    private val now: Instant = Instant.parse("2026-11-18T10:00:00Z")

    private fun appointment(
        id: String = "appointment-1",
        startsLocal: LocalDateTime = LocalDateTime.of(2026, 11, 20, 10, 30),
        timeZone: ZoneId = lisbon,
        companionUserId: String? = null,
        cancelledAt: Instant? = null,
        kind: AppointmentKind = AppointmentKind.CONSULTATION,
        title: String? = "Cardiologia",
    ) = Appointment(
        id = AppointmentId(id),
        patientId = PatientId("patient-1"),
        kind = kind,
        startsLocal = startsLocal,
        timeZone = timeZone,
        title = title,
        companionUserId = companionUserId,
        cancelledAt = cancelledAt,
    )

    private fun plan(
        appointments: List<Appointment>,
        myUserId: String? = "u1",
        myRole: MemberRole? = MemberRole.OWNER,
        horizon: Duration = AppointmentReminderPlanning.DEFAULT_HORIZON,
    ): List<AppointmentReminder> = AppointmentReminderPlanning.plan(
        now = now,
        appointments = appointments,
        myUserId = myUserId,
        myRole = myRole,
        horizon = horizon,
    )

    @Test
    fun `an appointment without a companion reminds an owner`() {
        val reminders = plan(listOf(appointment()))

        assertEquals(
            listOf(
                AppointmentReminderKind.EVENING_BEFORE,
                AppointmentReminderKind.HOUR_BEFORE,
            ),
            reminders.map { it.kind },
        )
    }

    @Test
    fun `an appointment without a companion reminds a caregiver`() {
        val reminders = plan(listOf(appointment()), myRole = MemberRole.CAREGIVER)

        assertEquals(2, reminders.size)
    }

    @Test
    fun `a viewer is never reminded`() {
        val reminders = plan(listOf(appointment()), myRole = MemberRole.VIEWER)

        assertTrue(reminders.isEmpty())
    }

    @Test
    fun `the companion is reminded even when the role would not be`() {
        val reminders = plan(
            listOf(appointment(companionUserId = "u1")),
            myUserId = "u1",
            myRole = MemberRole.VIEWER,
        )

        assertEquals(2, reminders.size)
    }

    @Test
    fun `someone else's appointment as companion is not reminded here`() {
        val reminders = plan(
            listOf(appointment(companionUserId = "u2")),
            myUserId = "u1",
            myRole = MemberRole.OWNER,
        )

        assertTrue(reminders.isEmpty())
    }

    @Test
    fun `a cancelled appointment has no reminders`() {
        val reminders = plan(
            listOf(appointment(cancelledAt = Instant.parse("2026-11-18T09:00:00Z"))),
        )

        assertTrue(reminders.isEmpty())
    }

    @Test
    fun `the two instants are 20-00 the evening before and one hour before`() {
        val reminders = plan(listOf(appointment()))

        // Start is 2026-11-20 10:30 WET (UTC+0).
        assertEquals(
            Instant.parse("2026-11-20T09:30:00Z"),
            reminders.first { it.kind == AppointmentReminderKind.HOUR_BEFORE }.instant,
        )
        assertEquals(
            Instant.parse("2026-11-19T20:00:00Z"),
            reminders.first { it.kind == AppointmentReminderKind.EVENING_BEFORE }.instant,
        )
    }

    @Test
    fun `a past reminder is skipped but the other one stays`() {
        // 13:00 today: 20:00 yesterday has passed, 12:00 today (1 h before) has not.
        val reminders = plan(
            listOf(appointment(startsLocal = LocalDateTime.of(2026, 11, 18, 13, 0))),
        )

        assertEquals(
            listOf(AppointmentReminderKind.HOUR_BEFORE),
            reminders.map { it.kind },
        )
    }

    @Test
    fun `an appointment whose reminders have all passed has none`() {
        val reminders = plan(
            listOf(appointment(startsLocal = LocalDateTime.of(2026, 11, 18, 10, 30))),
        )

        assertTrue(reminders.isEmpty())
    }

    @Test
    fun `the evening before keeps 20-00 wall clock across a DST change`() {
        // Europe/Lisbon springs forward on 2026-03-29 at 01:00 (WET -> WEST).
        // The appointment is the next day at 10:00 WEST; the evening before is
        // the 28th, still WET.
        val appointment = appointment(
            startsLocal = LocalDateTime.of(2026, 3, 29, 10, 0),
            timeZone = lisbon,
        )

        val reminders = AppointmentReminderPlanning.plan(
            now = Instant.parse("2026-03-27T12:00:00Z"),
            appointments = listOf(appointment),
            myUserId = "u1",
            myRole = MemberRole.OWNER,
        )

        assertEquals(
            Instant.parse("2026-03-28T20:00:00Z"),
            reminders.first { it.kind == AppointmentReminderKind.EVENING_BEFORE }.instant,
        )
        // 10:00 WEST is 09:00Z, one hour before is 08:00Z: the offset changed
        // between the two reminders and neither moved.
        assertEquals(
            Instant.parse("2026-03-29T08:00:00Z"),
            reminders.first { it.kind == AppointmentReminderKind.HOUR_BEFORE }.instant,
        )
    }

    @Test
    fun `an appointment past the eight-day horizon is not planned`() {
        val beyond = appointment(
            id = "appointment-far",
            startsLocal = LocalDateTime.of(2026, 11, 27, 10, 0),
        )
        val within = appointment(
            id = "appointment-near",
            startsLocal = LocalDateTime.of(2026, 11, 25, 10, 0),
        )

        val reminders = plan(listOf(beyond, within))

        assertEquals(
            setOf("appointment-near"),
            reminders.map { it.appointmentId.value }.toSet(),
        )
    }

    @Test
    fun `the plan is ordered by instant`() {
        val later = appointment(
            id = "appointment-later",
            startsLocal = LocalDateTime.of(2026, 11, 21, 10, 0),
        )
        val sooner = appointment(
            id = "appointment-sooner",
            startsLocal = LocalDateTime.of(2026, 11, 19, 10, 0),
        )

        val reminders = plan(listOf(later, sooner))

        assertEquals(reminders.sortedBy { it.instant }, reminders)
        assertEquals(
            "appointment-sooner",
            reminders.first().appointmentId.value,
        )
    }

    @Test
    fun `request codes differ for the two reminders of one appointment`() {
        val reminders = plan(listOf(appointment()))

        assertEquals(2, reminders.map { AppointmentReminderIds.requestCode(it) }.toSet().size)
    }
}
