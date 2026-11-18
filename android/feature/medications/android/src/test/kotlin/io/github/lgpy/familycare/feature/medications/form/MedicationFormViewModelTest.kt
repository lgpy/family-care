package io.github.lgpy.familycare.feature.medications.form

import app.cash.turbine.test
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.feature.medications.FakeCareCircleRepository
import io.github.lgpy.familycare.feature.medications.FakeMedicationRepository
import io.github.lgpy.familycare.feature.medications.FakePatientRepository
import io.github.lgpy.familycare.feature.medications.FakeStockRepository
import io.github.lgpy.familycare.feature.medications.awaitWhere
import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.domain.MedicationForm
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.SkipReason
import io.github.lgpy.familycare.feature.medications.medication
import io.github.lgpy.familycare.feature.medications.regimen
import io.github.lgpy.familycare.feature.medications.testPatientId
import io.github.lgpy.familycare.feature.medications.withRegimens
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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
 * The four-step add flow and its edit mode: step gating, default stock units,
 * the domain's field errors, the opening count and the edit recount, and what
 * reaches the repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MedicationFormViewModelTest {

    private lateinit var meds: FakeMedicationRepository
    private lateinit var stock: FakeStockRepository
    private lateinit var patients: FakePatientRepository
    private lateinit var circles: FakeCareCircleRepository

    private val lisbon = ZoneId.of("Europe/Lisbon")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        meds = FakeMedicationRepository()
        stock = FakeStockRepository()
        patients = FakePatientRepository()
        circles = FakeCareCircleRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(medicationId: String? = null) = MedicationFormViewModel(
        patientId = testPatientId.value,
        medicationId = medicationId,
        medicationRepository = meds,
        stockRepository = stock,
        patientRepository = patients,
        careCircleRepository = circles,
        newId = { "generated-id" },
        timeZone = lisbon,
    )

    @Test
    fun `creates a medication with its first regimen and the opening count`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onNameChanged(" Metformin ")
            viewModel.onStrengthChanged("500 mg")
            viewModel.next()
            viewModel.next()
            viewModel.onStockQuantityChanged("30")
            viewModel.next()
            viewModel.save()

            val call = meds.created.single()
            val medication = call.medication
            val regimen = call.regimen
            assertEquals("Metformin", medication.name)
            assertEquals(testPatientId, medication.patientId)
            assertEquals("tablet", medication.stockUnit)
            assertEquals(MedicationForm.TABLET, medication.form)
            assertEquals(BigDecimal("30"), call.openingQuantity)
            assertEquals(1, regimen.times.size)
            assertEquals(LocalTime.of(8, 0), regimen.times.single().time)
            assertEquals(BigDecimal.ONE, regimen.times.single().quantity)
            assertEquals(lisbon, regimen.timeZone)
            assertTrue(viewModel.uiState.value.saved)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a blank name keeps the first step and marks the field`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.next()

            assertEquals(0, viewModel.uiState.value.step)
            assertTrue(MedicationField.MEDICATION_NAME in viewModel.uiState.value.errors)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the stock unit follows the form until it is typed`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onFormChanged(MedicationForm.DROPS)
            assertEquals("drop", viewModel.uiState.value.stockUnit)

            viewModel.onStockUnitChanged("bottle")
            viewModel.onFormChanged(MedicationForm.LIQUID)
            assertEquals("bottle", viewModel.uiState.value.stockUnit)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `times keep fractions in 0_25 steps`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onNameChanged("Metformin")
            viewModel.onAddTime()
            viewModel.onQuantityStep(1, -3)
            viewModel.next()
            viewModel.next()
            viewModel.onStockQuantityChanged("10")
            viewModel.next()
            viewModel.save()

            val regimen = meds.created.single().regimen
            assertEquals(2, regimen.times.size)
            assertEquals(BigDecimal("0.25"), regimen.times.last().quantity)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an invalid time keeps the schedule step and marks the times`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onNameChanged("Metformin")
            viewModel.next()
            viewModel.onTimeChanged(0, "25:00")
            viewModel.next()

            assertEquals(1, viewModel.uiState.value.step)
            assertTrue(MedicationField.REGIMEN_TIMES in viewModel.uiState.value.errors)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the reminder default is inherited`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onNameChanged("Metformin")
            viewModel.onStockQuantityChanged("30")
            viewModel.save()

            val medication = meds.created.single().medication
            assertEquals(null, medication.reminderPolicy)
            assertEquals(null, medication.primaryCaregiverId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an override is stored on the medication`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onNameChanged("Metformin")
            viewModel.onStockQuantityChanged("30")
            viewModel.onInheritRemindersChanged(false)
            viewModel.onPrimaryCaregiverChanged("u2")
            viewModel.save()

            val medication = meds.created.single().medication
            assertEquals("u2", medication.primaryCaregiverId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `edit mode saves the details without touching the regimen`() = runTest {
        meds.observed["med-1"] =
            kotlinx.coroutines.flow.MutableStateFlow(
                withRegimens(
                    medication(id = "med-1", name = "Metformin"),
                    listOf(regimen(id = "reg-1", medicationId = "med-1", startDate = LocalDate.of(2026, 1, 1))),
                ),
            )
        val viewModel = viewModel(medicationId = "med-1")
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            assertEquals("Metformin", viewModel.uiState.value.name)
            viewModel.onNameChanged("Metformin 850")
            viewModel.save()

            assertEquals("Metformin 850", meds.updated.single().name)
            assertEquals(0, meds.created.size)
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
            viewModel.onNameChanged("Metformin")
            viewModel.save()

            assertEquals(0, meds.created.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a malformed prescription date is marked`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onNameChanged("Metformin")
            viewModel.onRequiresPrescriptionChanged(true)
            viewModel.onPrescriptionValidUntilChanged("31/12/2027")
            viewModel.next()

            assertTrue(
                MedicationField.MEDICATION_PRESCRIPTION_VALID_UNTIL in viewModel.uiState.value.errors,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the stock step requires a quantity on create`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onNameChanged("Metformin")
            viewModel.next()
            viewModel.next()
            assertEquals(2, viewModel.uiState.value.step)
            viewModel.next()

            assertEquals(2, viewModel.uiState.value.step)
            assertTrue(MedicationField.STOCK_QUANTITY in viewModel.uiState.value.errors)
            assertEquals(0, meds.created.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a threshold outside 0 to 365 keeps the stock step`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onNameChanged("Metformin")
            viewModel.next()
            viewModel.next()
            viewModel.onStockQuantityChanged("10")
            viewModel.onLowStockThresholdChanged("400")
            viewModel.next()

            assertEquals(2, viewModel.uiState.value.step)
            assertTrue(
                MedicationField.MEDICATION_LOW_STOCK_THRESHOLD in viewModel.uiState.value.errors,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the stock step defaults to seven days and accepts zero`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            assertEquals("7", viewModel.uiState.value.lowStockThresholdDays)
            viewModel.onNameChanged("Metformin")
            viewModel.onStockQuantityChanged("0")
            viewModel.onLowStockThresholdChanged("0")
            viewModel.next()
            viewModel.next()
            viewModel.next()
            viewModel.save()

            assertEquals(0, meds.created.single().medication.lowStockThresholdDays)
            assertEquals(BigDecimal.ZERO, meds.created.single().openingQuantity)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `edit mode saves the threshold`() = runTest {
        editModeMedication()
        val viewModel = viewModel(medicationId = "med-1")
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            assertEquals("7", viewModel.uiState.value.lowStockThresholdDays)
            viewModel.onLowStockThresholdChanged("10")
            viewModel.save()

            assertEquals(10, meds.updated.single().lowStockThresholdDays)
            assertEquals(0, stock.counts.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `edit mode with a recount records it after the update`() = runTest {
        editModeMedication()
        val viewModel = viewModel(medicationId = "med-1")
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onRecountQuantityChanged("12")
            viewModel.save()

            assertEquals(1, meds.updated.size)
            val count = stock.counts.single()
            assertEquals(MedicationId("med-1"), count.medicationId)
            assertEquals(BigDecimal("12"), count.quantity)
            assertTrue(viewModel.uiState.value.saved)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `edit mode without a recount writes no count`() = runTest {
        editModeMedication()
        val viewModel = viewModel(medicationId = "med-1")
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.save()

            assertEquals(1, meds.updated.size)
            assertEquals(0, stock.counts.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a malformed recount is marked and nothing is written`() = runTest {
        editModeMedication()
        val viewModel = viewModel(medicationId = "med-1")
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onRecountQuantityChanged("many")
            viewModel.save()

            assertTrue(MedicationField.STOCK_QUANTITY in viewModel.uiState.value.errors)
            assertEquals(0, meds.updated.size)
            assertEquals(0, stock.counts.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun editModeMedication() {
        meds.observed["med-1"] =
            kotlinx.coroutines.flow.MutableStateFlow(
                withRegimens(
                    medication(id = "med-1", name = "Metformin"),
                    listOf(regimen(id = "reg-1", medicationId = "med-1", startDate = LocalDate.of(2026, 1, 1))),
                ),
            )
    }
}
