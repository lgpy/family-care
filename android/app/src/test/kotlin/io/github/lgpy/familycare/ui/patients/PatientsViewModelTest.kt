package io.github.lgpy.familycare.ui.patients

import app.cash.turbine.test
import io.github.lgpy.familycare.core.domain.circle.CareCircleId
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.core.ui.patient.PatientSummaryIcon
import io.github.lgpy.familycare.core.ui.patient.PatientSummaryItem
import io.github.lgpy.familycare.core.ui.patient.PatientSummarySource
import io.github.lgpy.familycare.core.ui.patient.PatientSummarySources
import io.github.lgpy.familycare.ui.FakeCareCircleRepository
import io.github.lgpy.familycare.ui.FakePatientRepository
import io.github.lgpy.familycare.ui.awaitWhere
import io.github.lgpy.familycare.ui.caregiverMember
import io.github.lgpy.familycare.ui.ownerMember
import io.github.lgpy.familycare.ui.testCircle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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

/** The Patients tab: the local list with its hint, and who may add a patient. */
@OptIn(ExperimentalCoroutinesApi::class)
class PatientsViewModelTest {

    private lateinit var patients: FakePatientRepository
    private lateinit var circles: FakeCareCircleRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        patients = FakePatientRepository()
        circles = FakeCareCircleRepository()
        circles.setCircle(
            circle = testCircle,
            role = MemberRole.OWNER,
            members = listOf(ownerMember, caregiverMember),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theListNamesEachPatientsPrimaryCaregiver() = runTest {
        patients.patientsFlow.value = listOf(
            Patient(
                id = PatientId("patient-1"),
                circleId = CareCircleId("circle-1"),
                displayName = "Avó",
                reminderPolicy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
                primaryCaregiverId = caregiverMember.userId,
            ),
            Patient(
                id = PatientId("patient-2"),
                circleId = CareCircleId("circle-1"),
                displayName = "Avô",
                reminderPolicy = ReminderPolicy.ALL_CAREGIVERS,
                primaryCaregiverId = null,
            ),
        )

        val viewModel = PatientsViewModel(patients, circles)

        viewModel.uiState.test {
            val state = awaitWhere { it.loaded }

            assertEquals(2, state.patients.size)
            assertEquals("Ana", state.patients[0].primaryCaregiverName)
            assertNull(state.patients[1].primaryCaregiverName)
            assertTrue(state.canWrite)
        }
    }

    @Test
    fun theRowMergesEverySummarySourceInSourceOrder() = runTest {
        patients.patientsFlow.value = listOf(
            Patient(
                id = PatientId("patient-1"),
                circleId = CareCircleId("circle-1"),
                displayName = "Avó",
            ),
        )
        val appointment = FakeSummary(
            order = 20,
            item = PatientSummaryItem(PatientSummaryIcon.EVENT, "appointment"),
        )
        val medicines = FakeSummary(
            order = 10,
            item = PatientSummaryItem(PatientSummaryIcon.MEDICATION, "medicines"),
        )
        val allergies = FakeSummary(
            order = 30,
            item = PatientSummaryItem(PatientSummaryIcon.WARNING, "allergies"),
        )

        val viewModel = PatientsViewModel(
            patientRepository = patients,
            careCircleRepository = circles,
            summarySources = PatientSummarySources(listOf(appointment, allergies, medicines)),
        )

        viewModel.uiState.test {
            val state = awaitWhere {
                it.patients.singleOrNull()?.summary?.size == 3
            }

            assertEquals(
                listOf("medicines", "appointment", "allergies"),
                state.patients.single().summary.map { it.text },
            )
        }
    }

    @Test
    fun aViewerGetsNoAddButton() = runTest {
        circles.setCircle(
            circle = testCircle,
            role = MemberRole.VIEWER,
            members = listOf(ownerMember, caregiverMember.copy(role = MemberRole.VIEWER)),
        )

        val viewModel = PatientsViewModel(patients, circles)

        viewModel.uiState.test {
            assertFalse(awaitWhere { it.loaded }.canWrite)
        }
    }

    /** One module's fixed contribution, in place of its flow. */
    private class FakeSummary(
        override val order: Int,
        private val item: PatientSummaryItem,
    ) : PatientSummarySource {
        override fun summary(patientId: PatientId): Flow<List<PatientSummaryItem>> =
            flowOf(listOf(item))
    }
}
