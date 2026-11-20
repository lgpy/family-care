package io.github.lgpy.familycare.ui.patients

import app.cash.turbine.test
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.ui.FakeCareCircleRepository
import io.github.lgpy.familycare.ui.FakePatientRepository
import io.github.lgpy.familycare.ui.awaitWhere
import io.github.lgpy.familycare.ui.caregiverMember
import io.github.lgpy.familycare.ui.ownerMember
import io.github.lgpy.familycare.ui.testCircle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * More → "Who gets reminded": each patient carries the caregiver their policy
 * names, so the row can say who is reminded without opening the profile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WhoGetsRemindedViewModelTest {

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
    fun `each patient carries the caregiver its policy names`() = runTest {
        patients.patientsFlow.value = listOf(
            patient(
                id = "grandma",
                name = "Avó",
                policy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
                primaryCaregiverId = caregiverMember.userId,
            ),
            patient(
                id = "grandpa",
                name = "Avô",
                policy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
                primaryCaregiverId = ownerMember.userId,
            ),
            patient(
                id = "everyone",
                name = "Todos",
                policy = ReminderPolicy.ALL_CAREGIVERS,
                primaryCaregiverId = null,
            ),
        )

        WhoGetsRemindedViewModel(patients, circles).uiState.test {
            val state = awaitWhere { it.rows.size == 3 }

            assertEquals(listOf("Avó", "Avô", "Todos"), state.rows.map { it.patient.displayName })
            assertEquals("Ana", state.rows[0].primaryCaregiverName)
            assertFalse(state.rows[0].primaryCaregiverIsMe)
            assertTrue(state.rows[1].primaryCaregiverIsMe)
            assertEquals(ReminderPolicy.ALL_CAREGIVERS, state.rows[2].patient.reminderPolicy)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun patient(
        id: String,
        name: String,
        policy: ReminderPolicy,
        primaryCaregiverId: String?,
    ) = Patient(
        id = PatientId(id),
        circleId = testCircle.id,
        displayName = name,
        reminderPolicy = policy,
        primaryCaregiverId = primaryCaregiverId,
    )
}
