package io.github.lgpy.familycare.feature.patientinfo.profile

import app.cash.turbine.test
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.feature.patientinfo.FakeCareCircleRepository
import io.github.lgpy.familycare.feature.patientinfo.FakePatientInfoRepository
import io.github.lgpy.familycare.feature.patientinfo.patientInfo
import io.github.lgpy.familycare.feature.patientinfo.testPatientId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The allergies banner and the Info tab's state (D29): what is shown, what is
 * not, and who may edit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PatientInfoViewModelTest {

    private lateinit var info: FakePatientInfoRepository
    private lateinit var circles: FakeCareCircleRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        info = FakePatientInfoRepository()
        circles = FakeCareCircleRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = PatientInfoViewModel(
        patientId = testPatientId.value,
        patientInfoRepository = info,
        careCircleRepository = circles,
    )

    @Test
    fun `a patient with no record shows no banner and an empty tab`() = runTest {
        viewModel().uiState.test {
            val state = awaitItem()

            assertTrue(state.loaded)
            assertNull(state.info)
            assertFalse(state.isHeaderVisible)
            assertFalse(state.showsAllergies)
            assertFalse(state.showsConditions)
            assertTrue(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `allergies show the banner and nothing else`() = runTest {
        info.setInfo(info = patientInfo(allergies = listOf("Penicillin", "Nuts")))

        viewModel().uiState.test {
            val state = awaitItem()

            assertTrue(state.isHeaderVisible)
            assertTrue(state.showsAllergies)
            assertFalse("No conditions line without conditions", state.showsConditions)
            assertFalse(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `conditions show the line without a banner`() = runTest {
        info.setInfo(info = patientInfo(conditions = listOf("Type 2 diabetes")))

        viewModel().uiState.test {
            val state = awaitItem()

            assertTrue(state.isHeaderVisible)
            assertFalse("A missing allergy list is never shown as none", state.showsAllergies)
            assertTrue(state.showsConditions)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an empty record hides the header and keeps the tab empty`() = runTest {
        info.setInfo(info = patientInfo())

        viewModel().uiState.test {
            val state = awaitItem()

            assertFalse(state.isHeaderVisible)
            assertTrue(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a record with only a phone number is not empty`() = runTest {
        info.setInfo(info = patientInfo(healthCentrePhone = "210000000"))

        viewModel().uiState.test {
            val state = awaitItem()

            assertFalse("The banner is about allergies and conditions only", state.isHeaderVisible)
            assertFalse(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an owner may edit until the role becomes a viewer's`() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            assertTrue(awaitItem().canWrite)

            circles.roleFlow.value = MemberRole.VIEWER
            assertFalse(awaitItem().canWrite)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a caregiver may edit`() = runTest {
        circles.roleFlow.value = MemberRole.CAREGIVER

        viewModel().uiState.test {
            assertTrue(awaitItem().canWrite)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `another source's write refreshes the banner`() = runTest {
        info.setInfo(info = patientInfo())

        viewModel().uiState.test {
            assertFalse(awaitItem().isHeaderVisible)

            info.setInfo(info = patientInfo(allergies = listOf("Penicillin")))
            val updated = awaitItem()

            assertTrue(updated.isHeaderVisible)
            assertTrue(updated.showsAllergies)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
