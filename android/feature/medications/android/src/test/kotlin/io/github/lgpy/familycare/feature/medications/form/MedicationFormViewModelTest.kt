package io.github.lgpy.familycare.feature.medications.form

import app.cash.turbine.test
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.medication.PillColor
import io.github.lgpy.familycare.core.domain.medication.PillLook
import io.github.lgpy.familycare.core.domain.medication.PillShape
import io.github.lgpy.familycare.core.domain.medication.PillSize
import io.github.lgpy.familycare.feature.medications.FakeCareCircleRepository
import io.github.lgpy.familycare.feature.medications.FakeMedicationCatalogRepository
import io.github.lgpy.familycare.feature.medications.FakeMedicationRepository
import io.github.lgpy.familycare.feature.medications.FakePatientRepository
import io.github.lgpy.familycare.feature.medications.FakeStockRepository
import io.github.lgpy.familycare.feature.medications.awaitWhere
import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.domain.MedicationForm
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.MedicationSuggestion
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The one-screen add form and its edit mode: the preset and custom times, the
 * shared and per-time amounts, the default pill look, the optional opening
 * count, the domain's field errors and what reaches the repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MedicationFormViewModelTest {

    private lateinit var meds: FakeMedicationRepository
    private lateinit var stock: FakeStockRepository
    private lateinit var patients: FakePatientRepository
    private lateinit var circles: FakeCareCircleRepository
    private lateinit var catalog: FakeMedicationCatalogRepository

    private val lisbon = ZoneId.of("Europe/Lisbon")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        meds = FakeMedicationRepository()
        stock = FakeStockRepository()
        patients = FakePatientRepository()
        circles = FakeCareCircleRepository()
        catalog = FakeMedicationCatalogRepository()
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
        catalogRepository = catalog,
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
            viewModel.onStockQuantityStep(30)
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
    fun `a blank name marks the field and writes nothing`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.save()

            assertTrue(MedicationField.MEDICATION_NAME in viewModel.uiState.value.errors)
            assertEquals(0, meds.created.size)
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

    // --- When and how much ---------------------------------------------------

    @Test
    fun `preset times become the regimen and the shared amount applies to all`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onNameChanged("Metformin")
            viewModel.onPresetTimeToggled(LunchTime)
            viewModel.onTimeQuantityStep(-3)
            viewModel.save()

            val regimen = meds.created.single().regimen
            assertEquals(
                listOf(LocalTime.of(8, 0), LocalTime.of(13, 0)),
                regimen.times.map { it.time },
            )
            assertEquals(
                listOf(BigDecimal("0.25"), BigDecimal("0.25")),
                regimen.times.map { it.quantity },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a typed shared amount applies to every selected time`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onNameChanged("Metformin")
            viewModel.onPresetTimeToggled(LunchTime)
            // A pt-PT keyboard types a comma; it must reach the regimen as 1.5.
            viewModel.onTimeQuantityChanged("1,5")
            viewModel.save()

            assertEquals(
                listOf(BigDecimal("1.5"), BigDecimal("1.5")),
                meds.created.single().regimen.times.map { it.quantity },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an amount that is not a dose marks the times and writes nothing`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onNameChanged("Metformin")
            viewModel.onTimeQuantityChanged("0")
            viewModel.save()

            assertTrue(MedicationField.REGIMEN_TIMES in viewModel.uiState.value.errors)
            assertEquals(0, meds.created.size)

            // Typing a valid amount clears the mark without another save.
            viewModel.onTimeQuantityChanged("1")
            assertFalse(MedicationField.REGIMEN_TIMES in viewModel.uiState.value.errors)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a per-time amount can be typed`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onNameChanged("Metformin")
            viewModel.onPresetTimeToggled(LunchTime)
            viewModel.onDifferentAmountsPerTimeChanged(true)
            viewModel.onTimeQuantityChanged(LunchTime, "2")
            viewModel.save()

            val quantities = meds.created.single().regimen.times.associate { it.time to it.quantity }
            assertEquals(BigDecimal.ONE, quantities[LocalTime.of(8, 0)])
            assertEquals(BigDecimal("2"), quantities[LocalTime.of(13, 0)])
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a custom time is added and removed`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onCustomTimeAdded(14, 30)
            assertEquals(
                listOf(LocalTime.of(8, 0), LocalTime.of(14, 30)),
                viewModel.uiState.value.selectedTimes,
            )

            viewModel.onCustomTimeRemoved(LocalTime.of(14, 30))
            assertEquals(listOf(LocalTime.of(8, 0)), viewModel.uiState.value.selectedTimes)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a custom time that matches a preset selects the preset chip`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onCustomTimeAdded(13, 0)

            assertTrue(LunchTime in viewModel.uiState.value.presetTimes)
            assertTrue(viewModel.uiState.value.customTimes.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a custom time is saved in the regimen`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onNameChanged("Metformin")
            viewModel.onCustomTimeAdded(14, 30)
            viewModel.save()

            assertEquals(
                listOf(LocalTime.of(8, 0), LocalTime.of(14, 30)),
                meds.created.single().regimen.times.map { it.time },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no selected times marks the times and writes nothing`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onNameChanged("Metformin")
            viewModel.onPresetTimeToggled(MorningTime)
            viewModel.save()

            assertTrue(MedicationField.REGIMEN_TIMES in viewModel.uiState.value.errors)
            assertEquals(0, meds.created.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `different amounts per time are stored`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onNameChanged("Metformin")
            viewModel.onPresetTimeToggled(LunchTime)
            viewModel.onDifferentAmountsPerTimeChanged(true)
            viewModel.onTimeQuantityStep(LunchTime, -2)
            viewModel.save()

            val quantities = meds.created.single().regimen.times.associate { it.time to it.quantity }
            assertEquals(BigDecimal.ONE, quantities[LocalTime.of(8, 0)])
            assertEquals(BigDecimal("0.5"), quantities[LocalTime.of(13, 0)])
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Reminders -----------------------------------------------------------

    @Test
    fun `the reminder default is inherited`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onNameChanged("Metformin")
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
            viewModel.onInheritRemindersChanged(false)
            viewModel.onPrimaryCaregiverChanged("u2")
            viewModel.save()

            val medication = meds.created.single().medication
            assertEquals("u2", medication.primaryCaregiverId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Looks like ----------------------------------------------------------

    @Test
    fun `the default look follows the form`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            assertEquals(
                PillLook(shape = PillShape.ROUND, color = PillColor.WHITE),
                viewModel.uiState.value.pillLook,
            )

            viewModel.onFormChanged(MedicationForm.CAPSULE)
            assertEquals(
                PillLook(
                    shape = PillShape.CAPSULE,
                    color = PillColor.WHITE,
                    color2 = PillColor.WHITE,
                ),
                viewModel.uiState.value.pillLook,
            )

            viewModel.onFormChanged(MedicationForm.DROPS)
            assertEquals(
                PillLook(shape = PillShape.DROPS, color = PillColor.WHITE),
                viewModel.uiState.value.pillLook,
            )

            viewModel.onFormChanged(MedicationForm.LIQUID)
            assertNull(viewModel.uiState.value.pillLook)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a chosen look survives a form change`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            val chosen = PillLook(shape = PillShape.OVAL, color = PillColor.PINK)
            viewModel.onPillLookChanged(chosen)
            viewModel.onFormChanged(MedicationForm.CAPSULE)

            assertEquals(chosen, viewModel.uiState.value.pillLook)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the pill look is saved on create`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            val look = PillLook(
                shape = PillShape.OBLONG,
                color = PillColor.YELLOW,
                score = true,
                size = PillSize.LARGE,
            )
            viewModel.onNameChanged("Metformin")
            viewModel.onPillLookChanged(look)
            viewModel.save()

            assertEquals(look, meds.created.single().medication.pillLook)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Edit mode -----------------------------------------------------------

    @Test
    fun `edit mode saves the details without touching the regimen`() = runTest {
        editModeMedication()
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
    fun `edit mode loads and saves the medication's pill look`() = runTest {
        val look = PillLook(shape = PillShape.CAPSULE, color = PillColor.PURPLE, color2 = PillColor.PINK)
        meds.observed["med-1"] =
            kotlinx.coroutines.flow.MutableStateFlow(
                withRegimens(
                    medication(id = "med-1", name = "Omeprazole", pillLook = look),
                    listOf(regimen(id = "reg-1", medicationId = "med-1", startDate = LocalDate.of(2026, 1, 1))),
                ),
            )
        val viewModel = viewModel(medicationId = "med-1")
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            assertEquals(look, viewModel.uiState.value.pillLook)
            viewModel.onNameChanged("Omeprazole 20 mg")
            viewModel.save()

            val updated = meds.updated.single()
            assertEquals("Omeprazole 20 mg", updated.name)
            assertEquals(look, updated.pillLook)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `edit mode saves a changed pill look`() = runTest {
        editModeMedication()
        val changed = PillLook(shape = PillShape.TRIANGLE, color = PillColor.GREEN)
        val viewModel = viewModel(medicationId = "med-1")
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onPillLookChanged(changed)
            viewModel.save()

            assertEquals(changed, meds.updated.single().pillLook)
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
            viewModel.save()

            assertTrue(
                MedicationField.MEDICATION_PRESCRIPTION_VALID_UNTIL in viewModel.uiState.value.errors,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Stock ---------------------------------------------------------------

    @Test
    fun `the opening count is optional`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onNameChanged("Metformin")
            viewModel.save()

            assertEquals(1, meds.created.size)
            assertNull(meds.created.single().openingQuantity)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a threshold outside 0 to 365 blocks the save`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onNameChanged("Metformin")
            viewModel.onLowStockThresholdChanged("400")
            viewModel.save()

            assertTrue(
                MedicationField.MEDICATION_LOW_STOCK_THRESHOLD in viewModel.uiState.value.errors,
            )
            assertEquals(0, meds.created.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the threshold defaults to seven days and accepts zero`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            assertEquals("7", viewModel.uiState.value.lowStockThresholdDays)
            viewModel.onNameChanged("Metformin")
            viewModel.onStockQuantityStep(1)
            viewModel.onStockQuantityStep(-1)
            viewModel.onLowStockThresholdChanged("0")
            viewModel.save()

            assertEquals(0, meds.created.single().medication.lowStockThresholdDays)
            assertEquals(BigDecimal.ZERO, meds.created.single().openingQuantity)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the count is typed and its buttons start from zero`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            assertEquals("", viewModel.uiState.value.stockQuantity)

            viewModel.onStockQuantityStep(1)
            assertEquals("1", viewModel.uiState.value.stockQuantity)

            viewModel.onStockQuantityChanged("60")
            assertEquals("60", viewModel.uiState.value.stockQuantity)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the count says how many days it lasts at the current amounts`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            assertNull("nothing counted, nothing to estimate", viewModel.uiState.value.daysOfStock())

            viewModel.onPresetTimeToggled(LunchTime)
            viewModel.onTimeQuantityChanged("2")
            viewModel.onStockQuantityChanged("60")
            // Two tablets at 08:00 and two at 13:00: four a day, so 15 days.
            assertEquals(15, viewModel.uiState.value.daysOfStock())

            // Half a tablet twice a day is one a day: 60 days from 60.
            viewModel.onTimeQuantityChanged("0,5")
            assertEquals(60, viewModel.uiState.value.daysOfStock())
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

    // --- D31: name suggestions from the bundled catalogue ---------------------

    private fun suggestion(
        name: String = "Paracetamol Sandoz",
        dci: String = "Paracetamol",
        strength: String = "500 mg",
        form: MedicationForm = MedicationForm.TABLET,
        otc: Boolean = false,
        packs: List<Int> = listOf(20, 60),
    ) = MedicationSuggestion(
        name = name,
        dci = dci,
        strength = strength,
        form = form,
        formText = "Comp. revest. p/ película",
        packOptions = packs,
        otc = otc,
    )

    @Test
    fun `suggestions appear from two characters`() = runTest {
        catalog.results = listOf(suggestion())
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }

            viewModel.onNameChanged("p")
            assertTrue("one letter is not a search", catalog.queries.isEmpty())

            viewModel.onNameChanged("par")
            val state = awaitWhere { it.suggestions.isNotEmpty() }
            assertEquals(listOf("par"), catalog.queries)
            assertEquals("Paracetamol Sandoz", state.suggestions.first().name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `picking a suggestion fills the medicine fields and keeps them on edit`() = runTest {
        catalog.results = listOf(suggestion())
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onNameChanged("par")
            awaitWhere { it.suggestions.isNotEmpty() }

            viewModel.onSuggestionPicked(viewModel.uiState.value.suggestions.first())
            var state = viewModel.uiState.value
            assertEquals("Paracetamol Sandoz", state.name)
            assertEquals("Paracetamol", state.activeIngredient)
            assertEquals("500 mg", state.strength)
            assertEquals(MedicationForm.TABLET, state.form)
            assertTrue(state.requiresPrescription)
            assertTrue(state.suggestions.isEmpty())
            assertEquals(listOf(20, 60), state.packOptions)
            assertEquals(20, state.packUnits)

            // Typing the name by hand afterwards keeps what the pick filled.
            viewModel.onNameChanged("Paracetamol Sandoz (caixa antiga)")
            state = viewModel.uiState.value
            assertEquals("Paracetamol", state.activeIngredient)
            assertEquals("500 mg", state.strength)
            assertEquals(MedicationForm.TABLET, state.form)
            // Suggestions may reappear for the new text; that is fine.
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an OTC suggestion means no prescription`() = runTest {
        catalog.results = listOf(suggestion(otc = true, packs = emptyList()))
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onRequiresPrescriptionChanged(true)

            viewModel.onNameChanged("para")
            awaitWhere { it.suggestions.isNotEmpty() }
            viewModel.onSuggestionPicked(viewModel.uiState.value.suggestions.first())

            assertFalse(viewModel.uiState.value.requiresPrescription)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the pack chip fills an empty quantity`() = runTest {
        catalog.results = listOf(suggestion())
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }
            viewModel.onNameChanged("para")
            awaitWhere { it.suggestions.isNotEmpty() }
            viewModel.onSuggestionPicked(viewModel.uiState.value.suggestions.first())

            viewModel.onUsePackQuantity(viewModel.uiState.value.packUnits!!)
            assertEquals("20", viewModel.uiState.value.stockQuantity)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the circle's own medications come first`() = runTest {
        meds.activeFlow.value = listOf(
            withRegimens(medication(id = "med-1", name = "Paracetamol Ben-u-ron", strength = "500 mg")),
        )
        catalog.results = listOf(suggestion())
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }

            viewModel.onNameChanged("paracetamol")
            val state = awaitWhere { it.suggestions.size > 1 }
            assertTrue(state.suggestions.first().fromCircle)
            assertEquals("Paracetamol Ben-u-ron", state.suggestions.first().name)
            assertEquals("Paracetamol Sandoz", state.suggestions.last().name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failing catalogue leaves free text alone`() = runTest {
        catalog.failure = IllegalStateException("no asset")
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded }

            viewModel.onNameChanged("para")
            viewModel.onNameChanged("parac")
            assertTrue(viewModel.uiState.value.suggestions.isEmpty())
            assertEquals("parac", viewModel.uiState.value.name)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
