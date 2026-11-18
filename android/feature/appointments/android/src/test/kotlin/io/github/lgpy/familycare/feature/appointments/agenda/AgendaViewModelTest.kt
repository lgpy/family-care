package io.github.lgpy.familycare.feature.appointments.agenda

import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.feature.appointments.FakeAppointmentRepository
import io.github.lgpy.familycare.feature.appointments.FakeCareCircleRepository
import io.github.lgpy.familycare.feature.appointments.FakePatientRepository
import io.github.lgpy.familycare.feature.appointments.appointment
import io.github.lgpy.familycare.feature.appointments.entry
import io.github.lgpy.familycare.feature.appointments.grandmaId
import io.github.lgpy.familycare.feature.appointments.patient
import io.github.lgpy.familycare.feature.appointments.testNow
import io.github.lgpy.familycare.feature.appointments.testToday
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Agenda: grouping by day, the patient filter, the past toggle, who writes. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgendaViewModelTest {

    private lateinit var appointments: FakeAppointmentRepository
    private lateinit var patients: FakePatientRepository
    private lateinit var circles: FakeCareCircleRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        appointments = FakeAppointmentRepository()
        patients = FakePatientRepository()
        circles = FakeCareCircleRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = AgendaViewModel(
        appointmentRepository = appointments,
        patientRepository = patients,
        careCircleRepository = circles,
        clock = { testNow },
        zone = ZoneId.of("Europe/Lisbon"),
    )

    @Test
    fun `upcoming appointments are grouped by day, earliest first`() = runTest {
        patients.patientsFlow.value = listOf(patient(), patient(grandmaId, "Grandma"))
        appointments.agendaFlow.value = listOf(
            entry(appointment(id = "a-2", startsLocal = LocalDateTime.of(2026, 11, 20, 8, 0))),
            entry(appointment(id = "a-1", startsLocal = LocalDateTime.of(2026, 11, 18, 15, 0))),
            entry(
                appointment(id = "a-3", patientId = grandmaId, startsLocal = LocalDateTime.of(2026, 11, 20, 9, 0)),
                patientName = "Grandma",
            ),
        )

        val state = viewModel().uiState.first { it.loaded }

        assertEquals(testToday, state.today)
        assertEquals(listOf(testToday, testToday.plusDays(2)), state.upcomingDays.map { it.date })
        assertEquals(
            listOf("a-2", "a-3"),
            state.upcomingDays[1].entries.map { it.appointment.id.value },
        )
        assertTrue(state.pastDays.isEmpty())
    }

    @Test
    fun `today's and tomorrow's groups carry today's date for the labels`() = runTest {
        appointments.agendaFlow.value = listOf(
            entry(appointment(id = "a-1", startsLocal = LocalDateTime.of(2026, 11, 18, 15, 0))),
            entry(appointment(id = "a-2", startsLocal = LocalDateTime.of(2026, 11, 19, 9, 0))),
        )

        val state = viewModel().uiState.first { it.loaded }

        assertEquals(listOf(testToday, testToday.plusDays(1)), state.upcomingDays.map { it.date })
    }

    @Test
    fun `past appointments appear only when the toggle is on`() = runTest {
        appointments.agendaFlow.value = listOf(
            entry(appointment(id = "past", startsLocal = LocalDateTime.of(2026, 11, 1, 9, 0))),
            entry(appointment(id = "future", startsLocal = LocalDateTime.of(2026, 11, 20, 9, 0))),
        )
        val viewModel = viewModel()
        val before = viewModel.uiState.first { it.loaded }

        assertEquals(listOf("future"), before.upcomingDays.flatMap { day -> day.entries }.map { it.appointment.id.value })
        assertTrue(before.pastDays.isEmpty())

        viewModel.togglePast()
        val after = viewModel.uiState.first { it.showPast }

        assertEquals(listOf("past"), after.pastDays.flatMap { day -> day.entries }.map { it.appointment.id.value })
    }

    @Test
    fun `the patient filter keeps that patient's appointments only`() = runTest {
        patients.patientsFlow.value = listOf(patient(), patient(grandmaId, "Grandma"))
        appointments.agendaFlow.value = listOf(
            entry(appointment(id = "grandpa")),
            entry(appointment(id = "grandma", patientId = grandmaId), patientName = "Grandma"),
        )
        val viewModel = viewModel()
        viewModel.uiState.first { it.loaded }

        viewModel.selectPatient(grandmaId.value)
        val filtered = viewModel.uiState.first { it.selectedPatientId == grandmaId.value }

        assertEquals(listOf("grandma"), filtered.upcomingDays.single().entries.map { it.appointment.id.value })
        assertEquals(listOf(grandmaId.value, "patient-grandpa").sorted(), filtered.patients.map { it.id }.sorted())
    }

    @Test
    fun `a cancelled upcoming appointment stays visible and marked`() = runTest {
        appointments.agendaFlow.value = listOf(
            entry(appointment(id = "cancelled", cancelledAt = testNow.minusSeconds(60))),
        )

        val state = viewModel().uiState.first { it.loaded }

        val stored = state.upcomingDays.single().entries.single().appointment
        assertTrue(stored.isCancelled)
    }

    @Test
    fun `a viewer may not write and a caregiver may`() = runTest {
        circles.roleFlow.value = MemberRole.VIEWER
        assertFalse(viewModel().uiState.first { it.loaded }.canWrite)

        circles.roleFlow.value = MemberRole.CAREGIVER
        assertTrue(viewModel().uiState.first { it.loaded }.canWrite)
    }
}
