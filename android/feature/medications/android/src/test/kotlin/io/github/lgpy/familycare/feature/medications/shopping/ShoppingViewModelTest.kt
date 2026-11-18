package io.github.lgpy.familycare.feature.medications.shopping

import app.cash.turbine.test
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.feature.medications.FakeCareCircleRepository
import io.github.lgpy.familycare.feature.medications.FakeMedicationRepository
import io.github.lgpy.familycare.feature.medications.FakeStockRepository
import io.github.lgpy.familycare.feature.medications.RefillCall
import io.github.lgpy.familycare.feature.medications.awaitWhere
import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.ShoppingStatus
import io.github.lgpy.familycare.feature.medications.medication
import io.github.lgpy.familycare.feature.medications.regimen
import io.github.lgpy.familycare.feature.medications.stockOverview
import io.github.lgpy.familycare.feature.medications.stockSummary
import io.github.lgpy.familycare.feature.medications.withRegimens
import java.math.BigDecimal
import java.time.LocalDate
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
 * The shopping list (D28): grouping by the domain's [ShoppingStatus], the order,
 * buying a refill, and recording a new prescription's last valid day.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingViewModelTest {

    private lateinit var stock: FakeStockRepository
    private lateinit var meds: FakeMedicationRepository
    private lateinit var circles: FakeCareCircleRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        stock = FakeStockRepository()
        meds = FakeMedicationRepository()
        circles = FakeCareCircleRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ShoppingViewModel(
        stockRepository = stock,
        medicationRepository = meds,
        careCircleRepository = circles,
    )

    /** One low line, with the domain's status and the medication's prescription. */
    private fun overview(
        id: String,
        name: String,
        daysRemaining: Int?,
        status: ShoppingStatus?,
        prescriptionValidUntil: LocalDate? = null,
        low: Boolean = true,
    ): io.github.lgpy.familycare.feature.medications.data.StockOverview {
        val med = medication(id = id, name = name).copy(
            prescriptionValidUntil = prescriptionValidUntil,
        )
        return stockOverview(
            item = withRegimens(
                med = med,
                regimens = listOf(regimen(medicationId = id)),
            ),
            summary = stockSummary(
                daysRemaining = daysRemaining,
                low = low,
                shoppingStatus = status,
            ),
        )
    }

    @Test
    fun `groups low medications and keeps the prescription state of each`() = runTest {
        stock.overviewsFlow.value = listOf(
            overview(
                 id = "med-valid",
                 name = "Metformin",
                 daysRemaining = 4,
                 status = ShoppingStatus.READY_TO_BUY,
                 prescriptionValidUntil = LocalDate.of(2027, 3, 31),
            ),
            overview(
                 id = "med-expired",
                 name = "Amlodipine",
                 daysRemaining = 2,
                 status = ShoppingStatus.NEEDS_PRESCRIPTION,
                 prescriptionValidUntil = LocalDate.of(2026, 10, 1),
            ),
            overview(
                 id = "med-missing",
                 name = "Aspirin",
                 daysRemaining = 1,
                 status = ShoppingStatus.NEEDS_PRESCRIPTION,
            ),
            // Not low: the domain's status is null and the line must not appear.
            overview(
                 id = "med-plenty",
                 name = "Vitamin D",
                 daysRemaining = 40,
                 status = null,
                 low = false,
            ),
        )

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded && it.needsPrescription.size == 2 }
            assertEquals(
                listOf("med-missing", "med-expired"),
                state.needsPrescription.map { it.medicationId },
            )
            assertEquals(listOf("med-valid"), state.readyToBuy.map { it.medicationId })

            val missing = state.needsPrescription.first { it.medicationId == "med-missing" }
            assertTrue(missing.prescriptionMissing)
            assertNull(missing.prescriptionValidUntil)

            val expired = state.needsPrescription.first { it.medicationId == "med-expired" }
            assertFalse(expired.prescriptionMissing)
            assertEquals(LocalDate.of(2026, 10, 1), expired.prescriptionValidUntil)

            val ready = state.readyToBuy.single()
            assertEquals(LocalDate.of(2027, 3, 31), ready.prescriptionValidUntil)
            assertEquals(4, ready.daysRemaining)
            assertEquals("Metformin", ready.medication.name)
            assertTrue(state.canWrite)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sorts each group by days left then name`() = runTest {
        stock.overviewsFlow.value = listOf(
            overview("med-c", "Cebral", daysRemaining = 5, status = ShoppingStatus.READY_TO_BUY),
            overview("med-z", "Zolpidem", daysRemaining = 2, status = ShoppingStatus.READY_TO_BUY),
            overview("med-a", "Aspirin", daysRemaining = 2, status = ShoppingStatus.READY_TO_BUY),
        )

        viewModel().uiState.test {
            val state = awaitWhere { it.readyToBuy.size == 3 }
            assertEquals(
                listOf("Aspirin", "Zolpidem", "Cebral"),
                state.readyToBuy.map { it.medication.name },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an empty list is ready and empty`() = runTest {
        viewModel().uiState.test {
            val state = awaitWhere { it.loaded }
            assertTrue(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `buying records a refill and rejects an invalid quantity`() = runTest {
        stock.overviewsFlow.value = listOf(
            overview("med-1", "Metformin", 3, ShoppingStatus.READY_TO_BUY),
        )
        val vm = viewModel()

        vm.uiState.test {
            awaitWhere { it.readyToBuy.isNotEmpty() }
            vm.requestBuy("med-1")
            assertEquals("med-1", vm.uiState.value.buy?.medicationId)
            assertEquals("", vm.uiState.value.buyQuantity)

            // A refill must be greater than 0: nothing is written.
            vm.onBuyQuantityChanged("0")
            vm.confirmBuy()
            assertTrue(MedicationField.STOCK_QUANTITY in vm.uiState.value.buyErrors)
            assertTrue(stock.refills.isEmpty())

            // A comma is a decimal separator, as on a pt-PT keyboard.
            vm.onBuyQuantityChanged("2,5")
            vm.confirmBuy()
            assertNull(vm.uiState.value.buy)
            assertTrue(vm.uiState.value.buyErrors.isEmpty())
            assertEquals(
                listOf(RefillCall(MedicationId("med-1"), BigDecimal("2.5"))),
                stock.refills,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a viewer cannot buy or record a prescription`() = runTest {
        circles.roleFlow.value = MemberRole.VIEWER
        stock.overviewsFlow.value = listOf(
            overview(
                 "med-1",
                 "Metformin",
                 3,
                 ShoppingStatus.NEEDS_PRESCRIPTION,
                 prescriptionValidUntil = null,
            ),
        )
        val vm = viewModel()

        vm.uiState.test {
            val state = awaitWhere { it.loaded && it.needsPrescription.isNotEmpty() }
            assertFalse(state.canWrite)

            vm.requestBuy("med-1")
            vm.requestPrescription("med-1")
            assertNull(vm.uiState.value.buy)
            assertNull(vm.uiState.value.prescription)

            // Even a direct call is refused, the same rule the repository keeps.
            vm.confirmBuy()
            vm.confirmPrescription(LocalDate.of(2027, 1, 31))
            assertTrue(stock.refills.isEmpty())
            assertTrue(meds.updated.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a new prescription updates the medication`() = runTest {
        stock.overviewsFlow.value = listOf(
            overview("med-1", "Metformin", 3, ShoppingStatus.NEEDS_PRESCRIPTION),
        )
        val vm = viewModel()
        val validUntil = LocalDate.of(2027, 1, 31)

        vm.uiState.test {
            awaitWhere { it.needsPrescription.isNotEmpty() }
            vm.requestPrescription("med-1")
            assertEquals("med-1", vm.uiState.value.prescription?.medicationId)

            vm.confirmPrescription(validUntil)
            assertNull(vm.uiState.value.prescription)
            assertNull(vm.uiState.value.error)

            assertEquals(1, meds.updated.size)
            assertEquals(validUntil, meds.updated.single().prescriptionValidUntil)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
