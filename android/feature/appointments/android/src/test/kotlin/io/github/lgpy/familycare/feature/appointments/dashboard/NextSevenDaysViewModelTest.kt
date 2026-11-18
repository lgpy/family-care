package io.github.lgpy.familycare.feature.appointments.dashboard

import io.github.lgpy.familycare.feature.appointments.FakeAppointmentRepository
import io.github.lgpy.familycare.feature.appointments.appointment
import io.github.lgpy.familycare.feature.appointments.entry
import io.github.lgpy.familycare.feature.appointments.testNow
import java.time.Instant
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Today "Next 7 days" card: visible, ordered, capped at five lines. */
class NextSevenDaysViewModelTest {

    private val appointments = FakeAppointmentRepository()

    private fun viewModel() = NextSevenDaysViewModel(
        appointmentRepository = appointments,
        clock = { testNow },
    )

    @Test
    fun `hidden when there are no appointments`() = runBlocking {
        val state = viewModel().uiState.first { it.loaded }

        assertFalse(state.isVisible)
        assertTrue(state.lines.isEmpty())
        assertEquals(0, state.extraCount)
    }

    @Test
    fun `upcoming appointments become lines, soonest first`() = runBlocking {
        appointments.agendaFlow.value = listOf(
            entry(appointment(id = "later", startsLocal = LocalDateTime.of(2026, 11, 22, 9, 0))),
            entry(
                appointment(id = "sooner", startsLocal = LocalDateTime.of(2026, 11, 19, 15, 30)),
                patientName = "Grandma",
            ),
        )

        val state = viewModel().uiState.first { it.loaded }

        assertTrue(state.isVisible)
        assertEquals(listOf("sooner", "later"), state.lines.map { it.appointmentId })
        assertEquals("Grandma", state.lines.first().patientName)
        assertEquals(15, state.lines.first().time.hour)
        assertEquals(30, state.lines.first().time.minute)
        assertEquals("2026-11-19", state.lines.first().date.toString())
    }

    @Test
    fun `five lines are shown and the rest are counted`() = runBlocking {
        appointments.agendaFlow.value = (1..7).map { day ->
            entry(
                appointment(
                    id = "appointment-$day",
                    startsLocal = LocalDateTime.of(2026, 11, 18 + day, 10, 0),
                ),
            )
        }

        val state = viewModel().uiState.first { it.loaded }

        assertEquals(5, state.lines.size)
        assertEquals(2, state.extraCount)
        assertEquals("appointment-1", state.lines.first().appointmentId)
    }

    @Test
    fun `cancelled and past-the-window appointments are not shown`() = runBlocking {
        appointments.agendaFlow.value = listOf(
            entry(
                appointment(
                    id = "cancelled",
                    startsLocal = LocalDateTime.of(2026, 11, 20, 10, 0),
                    cancelledAt = Instant.parse("2026-11-18T09:00:00Z"),
                ),
            ),
            entry(
                appointment(id = "too-far", startsLocal = LocalDateTime.of(2026, 11, 30, 10, 0)),
            ),
            entry(
                appointment(id = "already-past", startsLocal = LocalDateTime.of(2026, 11, 17, 10, 0)),
            ),
            entry(
                appointment(id = "next-week", startsLocal = LocalDateTime.of(2026, 11, 21, 10, 0)),
            ),
        )

        val state = viewModel().uiState.first { it.loaded }

        assertEquals(listOf("next-week"), state.lines.map { it.appointmentId })
        assertEquals(0, state.extraCount)
    }

    @Test
    fun `the appointment's own title is kept for the line`() = runBlocking {
        appointments.agendaFlow.value = listOf(
            entry(
                appointment(
                    id = "untitled",
                    title = null,
                    startsLocal = LocalDateTime.of(2026, 11, 20, 10, 0),
                ),
            ),
        )

        val state = viewModel().uiState.first { it.loaded }

        assertEquals(null, state.lines.single().title)
    }
}
