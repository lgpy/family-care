package io.github.lgpy.familycare.feature.medications.regimen

import app.cash.turbine.test
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.feature.medications.FakeCareCircleRepository
import io.github.lgpy.familycare.feature.medications.FakeMedicationRepository
import io.github.lgpy.familycare.feature.medications.awaitWhere
import io.github.lgpy.familycare.feature.medications.domain.DoseTime
import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.medication
import io.github.lgpy.familycare.feature.medications.regimen
import io.github.lgpy.familycare.feature.medications.withRegimens
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
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
 * "Change dose or schedule": tomorrow by default, the domain's date rule mirrored
 * before the write, and the new times handed to `changeRegimen`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangeRegimenViewModelTest {

    private lateinit var meds: FakeMedicationRepository
    private lateinit var circles: FakeCareCircleRepository

    private val today = LocalDate.of(2026, 11, 16)
    private val startDate = LocalDate.of(2026, 1, 1)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        meds = FakeMedicationRepository()
        circles = FakeCareCircleRepository()
        meds.observed["med-1"] = kotlinx.coroutines.flow.MutableStateFlow(
            withRegimens(
                medication(id = "med-1", name = "Metformin"),
                listOf(
                    regimen(
                        id = "reg-1",
                        medicationId = "med-1",
                        startDate = startDate,
                        times = listOf(DoseTime(LocalTime.of(8, 0), BigDecimal.ONE)),
                    ),
                ),
            ),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ChangeRegimenViewModel(
        medicationId = "med-1",
        medicationRepository = meds,
        careCircleRepository = circles,
        newId = { "reg-new" },
        today = { today },
    )

    @Test
    fun `defaults to tomorrow and keeps the current times`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            val state = awaitWhere { it.loaded }
            assertEquals(today.plusDays(1).toString(), state.effectiveFrom)
            assertEquals("08:00", state.schedule.times.single().time)

            viewModel.save()
            val call = meds.changes.single()
            assertEquals(today.plusDays(1), call.effectiveFrom)
            assertEquals(1, call.times.size)
            assertTrue(viewModel.uiState.value.saved)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the day the version started is rejected before the write`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onEffectiveFromChanged(startDate.toString())
            viewModel.save()

            assertTrue(MedicationField.REGIMEN_START_DATE in viewModel.uiState.value.errors)
            assertEquals(0, meds.changes.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a new quantity reaches changeRegimen`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onQuantityStep(0, 1)
            viewModel.save()

            assertEquals(
                BigDecimal("1.5"),
                meds.changes.single().times.single().quantity,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a medication without a version cannot be changed`() = runTest {
        meds.observed["med-1"] = kotlinx.coroutines.flow.MutableStateFlow(null)

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded }
            assertTrue(state.notFound)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a viewer cannot save`() = runTest {
        circles.roleFlow.value = MemberRole.VIEWER
        val viewModel = viewModel()
        viewModel.uiState.test {
            val state = awaitWhere { it.loaded }
            assertFalse(state.canWrite)
            viewModel.save()

            assertEquals(0, meds.changes.size)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
