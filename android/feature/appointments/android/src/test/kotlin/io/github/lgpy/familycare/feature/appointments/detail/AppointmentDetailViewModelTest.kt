package io.github.lgpy.familycare.feature.appointments.detail

import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.feature.appointments.FakeAppointmentRepository
import io.github.lgpy.familycare.feature.appointments.FakeCareCircleRepository
import io.github.lgpy.familycare.feature.appointments.FakePatientRepository
import io.github.lgpy.familycare.feature.appointments.appointment
import io.github.lgpy.familycare.feature.appointments.patient
import io.github.lgpy.familycare.feature.appointments.testNow
import io.github.lgpy.familycare.feature.appointments.ui.AppointmentsUiError
import java.time.LocalDateTime
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

/**
 * One appointment: who may edit, cancel, follow up and delete — and that a
 * cancelled appointment offers no edit actions but keeps its row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppointmentDetailViewModelTest {

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

    private fun viewModel(): AppointmentDetailViewModel = AppointmentDetailViewModel(
        appointmentId = "appointment-1",
        appointmentRepository = appointments,
        patientRepository = patients,
        careCircleRepository = circles,
        clock = { testNow },
    )

    private fun seedAppointment(past: Boolean = false) {
        appointments.setAppointment(
            appointment(
                startsLocal = if (past) {
                    LocalDateTime.of(2026, 11, 1, 9, 0)
                } else {
                    LocalDateTime.of(2026, 11, 20, 9, 0)
                },
            ),
        )
        patients.setPatient(patient())
    }

    @Test
    fun `an owner may edit, cancel and delete, a caregiver may not delete`() = runTest {
        seedAppointment()
        circles.roleFlow.value = MemberRole.OWNER
        val owner = viewModel().uiState.first { it.loaded }
        assertTrue(owner.canEdit)
        assertTrue(owner.canDelete)
        assertEquals("Grandpa", owner.patientName)

        circles.roleFlow.value = MemberRole.CAREGIVER
        val caregiver = viewModel().uiState.first { it.loaded }
        assertTrue(caregiver.canEdit)
        assertFalse(caregiver.canDelete)
    }

    @Test
    fun `a viewer sees the appointment but no actions`() = runTest {
        seedAppointment()
        circles.roleFlow.value = MemberRole.VIEWER

        val state = viewModel().uiState.first { it.loaded }

        assertFalse(state.canEdit)
        assertFalse(state.canDelete)
    }

    @Test
    fun `a past appointment is marked past`() = runTest {
        seedAppointment(past = true)

        val state = viewModel().uiState.first { it.loaded }

        assertTrue(state.isPast)
    }

    @Test
    fun `cancelling stamps the appointment and keeps the screen open`() = runTest {
        seedAppointment()
        val viewModel = viewModel()
        viewModel.uiState.first { it.loaded }

        viewModel.cancel()

        assertEquals(listOf("appointment-1"), appointments.cancelled.map { it.value })
        assertFalse(viewModel.uiState.first { it.actionError == null }.deleted)
    }

    @Test
    fun `deleting closes the screen`() = runTest {
        seedAppointment()
        val viewModel = viewModel()
        viewModel.uiState.first { it.loaded }

        viewModel.delete()

        assertEquals(listOf("appointment-1"), appointments.deleted.map { it.value })
        assertTrue(viewModel.uiState.first { it.deleted }.deleted)
    }

    @Test
    fun `a refused cancel becomes an action error`() = runTest {
        seedAppointment()
        appointments.writeError =
            io.github.lgpy.familycare.feature.appointments.data.AppointmentsError.NotAllowed
        val viewModel = viewModel()
        viewModel.uiState.first { it.loaded }

        viewModel.cancel()

        val state = viewModel.uiState.first { it.actionError != null }
        assertEquals(AppointmentsUiError.NotAllowed, state.actionError)
        assertFalse(state.cancelling)
    }

    @Test
    fun `a cancelled appointment offers no edit`() = runTest {
        appointments.setAppointment(
            appointment(cancelledAt = testNow.minusSeconds(60)),
        )

        val state = viewModel().uiState.first { it.loaded }

        assertFalse(state.canEdit)
        assertTrue(state.appointment?.isCancelled == true)
    }
}
