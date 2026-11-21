package io.github.lgpy.familycare.feature.medications.dashboard

import app.cash.turbine.test
import io.github.lgpy.familycare.feature.medications.FakeStockRepository
import io.github.lgpy.familycare.feature.medications.awaitWhere
import io.github.lgpy.familycare.feature.medications.medication
import io.github.lgpy.familycare.feature.medications.regimen
import io.github.lgpy.familycare.feature.medications.stockOverview
import io.github.lgpy.familycare.feature.medications.stockSummary
import io.github.lgpy.familycare.feature.medications.testPatientId
import io.github.lgpy.familycare.feature.medications.withRegimens
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
 * The Today low-stock rows (M5; D28): only low, counted medications, fewest days
 * first, and nothing at all when none is low.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LowStockViewModelTest {

    private lateinit var stock: FakeStockRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        stock = FakeStockRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = LowStockViewModel(stockRepository = stock)

    /** One overview; [low] defaults to the threshold rule's result. */
    private fun overview(
        id: String,
        name: String,
        daysRemaining: Int?,
        low: Boolean,
        patientName: String = "Grandpa",
    ) = stockOverview(
        item = withRegimens(
            med = medication(id = id, name = name),
            regimens = listOf(regimen(medicationId = id)),
            patientName = patientName,
        ),
        summary = stockSummary(daysRemaining = daysRemaining, low = low),
    )

    @Test
    fun `is hidden when nothing is low`() = runTest {
        stock.overviewsFlow.value = listOf(
            overview("med-1", "Metformin", daysRemaining = 40, low = false),
        )

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded }
            assertFalse(state.isVisible)
            assertEquals(emptyList<LowStockLine>(), state.lines)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `is hidden for an empty ledger`() = runTest {
        viewModel().uiState.test {
            val state = awaitWhere { it.loaded }
            assertFalse(state.isVisible)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lists the low medications, fewest days left first`() = runTest {
        stock.overviewsFlow.value = listOf(
            overview("med-c", "Cebral", daysRemaining = 5, low = true),
            overview("med-z", "Zolpidem", daysRemaining = 2, low = true, patientName = "Grandma"),
            overview("med-a", "Aspirin", daysRemaining = 2, low = true),
            overview("med-plenty", "Vitamin D", daysRemaining = 40, low = false),
        )

        viewModel().uiState.test {
            val state = awaitWhere { it.lines.size == 3 }
            assertTrue(state.isVisible)
            // Equal days: medication name breaks the tie (Aspirin before Zolpidem).
            assertEquals(listOf("med-a", "med-z", "med-c"), state.lines.map { it.medicationId })
            assertEquals(
                listOf("Aspirin", "Zolpidem", "Cebral"),
                state.lines.map { it.medicationName },
            )
            assertEquals(listOf(2, 2, 5), state.lines.map { it.daysRemaining })
            assertEquals("Grandma", state.lines[1].patientName)
            assertEquals(testPatientId.value, state.lines[1].patientId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lists every low medication, uncapped`() = runTest {
        stock.overviewsFlow.value = (1..8).map { index ->
            overview(
                id = "med-$index",
                name = "Medication $index",
                daysRemaining = index,
                low = true,
            )
        }

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded }
            assertEquals((1..8).toList(), state.lines.map { it.daysRemaining })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a medication whose stock was never recorded is not low`() = runTest {
        stock.overviewsFlow.value = listOf(
            stockOverview(
                item = withRegimens(
                    med = medication(id = "med-1", name = "Metformin"),
                    regimens = listOf(regimen(medicationId = "med-1")),
                ),
                summary = stockSummary(daysRemaining = 0, low = true),
                movements = emptyList(),
            ),
        )

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded }
            assertFalse(state.isVisible)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a medication that stops being low leaves the card`() = runTest {
        stock.overviewsFlow.value = listOf(
            overview("med-1", "Metformin", daysRemaining = 2, low = true),
        )

        viewModel().uiState.test {
            assertTrue(awaitWhere { it.isVisible }.isVisible)

            // A refill arrives: the summary is no longer low and the card goes.
            stock.overviewsFlow.value = listOf(
                overview("med-1", "Metformin", daysRemaining = 60, low = false),
            )
            val state = awaitWhere { !it.isVisible }
            assertFalse(state.isVisible)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
