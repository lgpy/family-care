package io.github.lgpy.familycare.feature.appointments.profile

import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.core.ui.patient.TimelineKind
import io.github.lgpy.familycare.core.ui.patient.TimelineText
import io.github.lgpy.familycare.feature.appointments.FakeAppointmentRepository
import io.github.lgpy.familycare.feature.appointments.appointment
import io.github.lgpy.familycare.feature.appointments.grandpaId
import java.time.Instant
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The appointments History entries: one per appointment, cancelled marked. */
class AppointmentsTimelineSourceTest {

    private val appointments = FakeAppointmentRepository()
    private val source = AppointmentsTimelineSource(appointments, FakeAppointmentText)

    @Test
    fun `an appointment is one entry at its start instant`() = runBlocking {
        appointments.setPatientAppointments(
            grandpaId,
            listOf(
                appointment(
                    id = "appointment-1",
                    startsLocal = LocalDateTime.of(2026, 11, 20, 10, 30),
                ),
            ),
        )

        val entries = source.entries(grandpaId).first()

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("Cardiologia", entry.title)
        assertEquals("Hospital · Dra. Silva", entry.detail)
        assertEquals(Instant.parse("2026-11-20T10:30:00Z"), entry.at)
        assertEquals(TimelineKind.APPOINTMENT, entry.kind)
        assertEquals("appointments/appointment-1", entry.route)
    }

    @Test
    fun `an untitled appointment falls back to its kind`() = runBlocking {
        appointments.setPatientAppointments(
            grandpaId,
            listOf(appointment(id = "appointment-2", title = null)),
        )

        val entry = source.entries(grandpaId).first().single()

        assertEquals("Consultation", entry.title)
    }

    @Test
    fun `a cancelled appointment stays in the history, marked`() = runBlocking {
        appointments.setPatientAppointments(
            grandpaId,
            listOf(
                appointment(
                    id = "appointment-3",
                    cancelledAt = Instant.parse("2026-11-18T10:00:00Z"),
                ),
            ),
        )

        val entry = source.entries(grandpaId).first().single()

        assertEquals("Hospital · Dra. Silva · Cancelled", entry.detail)
    }

    @Test
    fun `an appointment with no place or doctor has no detail`() = runBlocking {
        appointments.setPatientAppointments(
            grandpaId,
            listOf(appointment(id = "appointment-4", place = null, doctor = null)),
        )

        val entry = source.entries(grandpaId).first().single()

        assertNull(entry.detail)
    }

    @Test
    fun `entries are newest first`() = runBlocking {
        appointments.setPatientAppointments(
            grandpaId,
            listOf(
                appointment(id = "older", startsLocal = LocalDateTime.of(2026, 10, 1, 9, 0)),
                appointment(id = "newer", startsLocal = LocalDateTime.of(2026, 11, 1, 9, 0)),
            ),
        )

        val entries = source.entries(grandpaId).first()

        assertEquals(listOf("newer", "older"), entries.map { it.id.split(":").last() })
        assertEquals(entries.sortedByDescending { it.at }, entries)
    }

    /** The English text the production `ResourcesTimelineText` would return. */
    private object FakeAppointmentText : TimelineText {
        override fun get(id: Int, vararg args: Any?): String = when (id) {
            R.string.appointments_kind_consultation -> "Consultation"
            R.string.appointments_kind_blood_test -> "Blood test"
            R.string.appointments_kind_exam -> "Exam"
            R.string.appointments_kind_checkup -> "Check-up"
            R.string.appointments_kind_other -> "Other"
            R.string.appointments_cancelled -> "Cancelled"
            else -> "unknown"
        }
    }
}
