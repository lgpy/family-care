package io.github.lgpy.familycare.feature.medications.list

import app.cash.turbine.test
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.feature.medications.FakeCareCircleRepository
import io.github.lgpy.familycare.feature.medications.FakeMedicationRepository
import io.github.lgpy.familycare.feature.medications.FakePatientRepository
import io.github.lgpy.familycare.feature.medications.FakeStockRepository
import io.github.lgpy.familycare.feature.medications.awaitWhere
import io.github.lgpy.familycare.feature.medications.domain.SkipReason
import io.github.lgpy.familycare.feature.medications.medication
import io.github.lgpy.familycare.feature.medications.patient
import io.github.lgpy.familycare.feature.medications.regimen
import io.github.lgpy.familycare.feature.medications.testPatientId
import io.github.lgpy.familycare.feature.medications.withRegimens
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A patient's medications: active first, stopped collapsed, and who may add. */
@OptIn(ExperimentalCoroutinesApi::class)
class PatientMedicationsViewModelTest {

    private lateinit var meds: FakeMedicationRepository
    private lateinit var patients: FakePatientRepository
    private lateinit var circles: FakeCareCircleRepository
    private lateinit var stock: FakeStockRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        meds = FakeMedicationRepository()
        patients = FakePatientRepository()
        circles = FakeCareCircleRepository()
        stock = FakeStockRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = PatientMedicationsViewModel(
        patientId = testPatientId.value,
        medicationRepository = meds,
        patientRepository = patients,
        stockRepository = stock,
        careCircleRepository = circles,
    )

    @Test
    fun `splits active from stopped and resolves the patient name`() = runTest {
        patients.setPatient(patient())
        meds.setPatientMedications(
            testPatientId,
            listOf(
                withRegimens(medication(id = "med-1", name = "Metformin")),
                withRegimens(
                    medication(
                        id = "med-2",
                        name = "Amlodipine",
                        stoppedAt = Instant.parse("2026-03-03T10:00:00Z"),
                    ),
                ),
            ),
        )

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded && it.active.isNotEmpty() }
            assertEquals("Grandpa", state.patientName)
            assertEquals(listOf("Metformin"), state.active.map { it.medication.name })
            assertEquals(listOf("Amlodipine"), state.stopped.map { it.medication.name })
            assertFalse(state.stoppedExpanded)
            assertTrue(state.canWrite)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the stopped section opens and closes`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.toggleStopped()
            assertTrue(viewModel.uiState.value.stoppedExpanded)
            viewModel.toggleStopped()
            assertFalse(viewModel.uiState.value.stoppedExpanded)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a viewer may not add`() = runTest {
        circles.roleFlow.value = MemberRole.VIEWER

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded }
            assertFalse(state.canWrite)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a patient that is not local is reported`() = runTest {
        patients.patientsFlow.value = emptyList()

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded }
            assertNull(state.patientName)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
