package io.github.lgpy.familycare.feature.appointments.domain

import io.github.lgpy.familycare.core.domain.circle.PatientId
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppointmentTest {

    private fun appointment(
        startsLocal: LocalDateTime = LocalDateTime.of(2026, 11, 20, 9, 30),
        timeZone: ZoneId = LISBON,
        title: String? = "Cardiologia",
        durationMinutes: Int? = 30,
        followUpOf: AppointmentId? = null,
    ) = Appointment(
        id = AppointmentId("appointment-1"),
        patientId = PatientId("patient-1"),
        kind = AppointmentKind.CONSULTATION,
        startsLocal = startsLocal,
        timeZone = timeZone,
        title = title,
        durationMinutes = durationMinutes,
        followUpOf = followUpOf,
    )

    @Test
    fun `startInstant is the wall-clock time in the appointment's zone`() {
        assertEquals(
            Instant.parse("2026-11-20T09:30:00Z"),
            appointment().startInstant(),
        )
    }

    @Test
    fun `a wall-clock time in the spring-forward gap moves forward by the gap`() {
        // Europe/Lisbon 2026-03-29: WET -> WEST at 01:00, so 01:30 does not exist.
        val inGap = appointment(startsLocal = LocalDateTime.of(2026, 3, 29, 1, 30))

        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), inGap.startInstant())
    }

    @Test
    fun `an ambiguous time in the fall-back overlap uses the earlier offset`() {
        // Europe/Lisbon 2026-10-25: WEST -> WET at 02:00, so 01:30 happens twice.
        val ambiguous = appointment(startsLocal = LocalDateTime.of(2026, 10, 25, 1, 30))

        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), ambiguous.startInstant())
    }

    @Test
    fun `a start in another zone keeps its local time`() {
        val london = appointment(
            startsLocal = LocalDateTime.of(2026, 11, 20, 9, 30),
            timeZone = ZoneId.of("Europe/London"),
        )

        assertEquals(Instant.parse("2026-11-20T09:30:00Z"), london.startInstant())
    }

    @Test
    fun `isPast compares the instant, not the local clock`() {
        val past = appointment().copy(cancelledAt = null)
        val now = Instant.parse("2026-11-20T09:30:00Z")

        assertFalse(past.isPast(now), "A start exactly now is not past")
        assertTrue(past.isPast(now.plusSeconds(1)))
        assertFalse(past.isPast(now.minusSeconds(1)))
    }

    @Test
    fun `a valid appointment has no field errors`() {
        assertTrue(appointment().validate().isValid)
    }

    @Test
    fun `texts are limited to the server's lengths`() {
        val validation = appointment(
            title = "x".repeat(Appointment.TITLE_MAX_LENGTH + 1),
        ).copy(
            place = "x".repeat(Appointment.PLACE_MAX_LENGTH + 1),
            doctor = "x".repeat(Appointment.DOCTOR_MAX_LENGTH + 1),
            preparation = "x".repeat(Appointment.PREPARATION_MAX_LENGTH + 1),
            notes = "x".repeat(Appointment.NOTES_MAX_LENGTH + 1),
        ).validate()

        assertEquals(
            setOf(
                AppointmentField.TITLE,
                AppointmentField.PLACE,
                AppointmentField.DOCTOR,
                AppointmentField.PREPARATION,
                AppointmentField.NOTES,
            ),
            validation.errors,
        )
    }

    @Test
    fun `a title exactly at the limit is accepted`() {
        val validation = appointment(title = "x".repeat(Appointment.TITLE_MAX_LENGTH)).validate()

        assertTrue(validation.isValid)
    }

    @Test
    fun `the duration must be between one minute and a day`() {
        assertEquals(
            setOf(AppointmentField.DURATION),
            appointment(durationMinutes = 0).validate().errors,
        )
        assertEquals(
            setOf(AppointmentField.DURATION),
            appointment(durationMinutes = Appointment.DURATION_MAX + 1).validate().errors,
        )
        assertTrue(appointment(durationMinutes = Appointment.DURATION_MAX).validate().isValid)
        assertTrue(appointment(durationMinutes = null).validate().isValid)
    }

    @Test
    fun `an appointment cannot follow up itself`() {
        val self = appointment(followUpOf = AppointmentId("appointment-1"))

        assertEquals(setOf(AppointmentField.FOLLOW_UP), self.validate().errors)
    }

    @Test
    fun `normalizing trims text to null and truncates the start to the minute`() {
        val normalized = appointment().copy(
            title = "  Cardiologia  ",
            place = "   ",
            startsLocal = LocalDateTime.of(2026, 11, 20, 9, 30, 42, 123_000_000),
        ).normalized()

        assertEquals("Cardiologia", normalized.title)
        assertEquals(null, normalized.place)
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 30), normalized.startsLocal)
    }

    private companion object {
        val LISBON: ZoneId = ZoneId.of("Europe/Lisbon")
    }
}
