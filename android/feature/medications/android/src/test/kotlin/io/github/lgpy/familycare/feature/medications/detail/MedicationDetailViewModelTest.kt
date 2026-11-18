package io.github.lgpy.familycare.feature.medications.detail

import app.cash.turbine.test
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.feature.medications.FakeCareCircleRepository
import io.github.lgpy.familycare.feature.medications.FakeDoseLogRepository
import io.github.lgpy.familycare.feature.medications.FakeMedicationRepository
import io.github.lgpy.familycare.feature.medications.awaitWhere
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.entry
import io.github.lgpy.familycare.feature.medications.medication
import io.github.lgpy.familycare.feature.medications.regimen
import io.github.lgpy.familycare.feature.medications.slot
import io.github.lgpy.familycare.feature.medications.withRegimens
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * The medication detail screen: the current version and its history, the recent
 * doses, and stopping with a last day the domain accepts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MedicationDetailViewModelTest {

    private lateinit var meds: FakeMedicationRepository
    private lateinit var doses: FakeDoseLogRepository
    private lateinit var circles: FakeCareCircleRepository

    private val today = LocalDate.of(2026, 11, 18)
    private val firstVersion = regimen(
        id = "reg-1",
        medicationId = "med-1",
        startDate = LocalDate.of(2026, 1, 1),
        endDate = LocalDate.of(2026, 3, 3),
    )
    private val secondVersion = regimen(
        id = "reg-2",
        medicationId = "med-1",
        startDate = LocalDate.of(2026, 3, 4),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        meds = FakeMedicationRepository()
        doses = FakeDoseLogRepository()
        circles = FakeCareCircleRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = MedicationDetailViewModel(
        medicationId = "med-1",
        medicationRepository = meds,
        doseLogRepository = doses,
        careCircleRepository = circles,
        today = { today },
    )

    @Test
    fun `shows the current version and the earlier ones`() = runTest {
        meds.observed["med-1"] = MutableStateFlow(
            withRegimens(
                medication(id = "med-1", name = "Metformin"),
                listOf(firstVersion, secondVersion),
            ),
        )

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded && it.medication != null }
            assertEquals(secondVersion.id, state.medication?.currentRegimen?.id)
            assertEquals(listOf(firstVersion.id), state.history.map { it.id })
            assertTrue(state.canWrite)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `recent doses are newest first and limited`() = runTest {
        meds.observed["med-1"] = MutableStateFlow(
            withRegimens(medication(id = "med-1"), listOf(secondVersion)),
        )
        val entries = (1..15).map { day ->
            val doseSlot = slot(secondVersion, LocalDateTime.of(2026, 3, day, 8, 0))
            entry(doseSlot, id = "entry-$day", actualAt = Instant.parse("2026-03-${day.toString().padStart(2, '0')}T08:04:00Z"))
        }
        doses.setMedicationEntries(MedicationId("med-1"), entries)

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded && it.recentEntries.isNotEmpty() }
            assertEquals(MedicationDetailUiState.RECENT_ENTRIES, state.recentEntries.size)
            assertEquals("entry-15", state.recentEntries.first().id.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stopping asks for today by default and calls the repository`() = runTest {
        meds.observed["med-1"] = MutableStateFlow(
            withRegimens(medication(id = "med-1"), listOf(secondVersion)),
        )
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded && it.medication != null }
            viewModel.requestStop()
            assertTrue(viewModel.uiState.value.showStop)
            assertEquals(today.toString(), viewModel.uiState.value.stopLastDay)

            viewModel.confirmStop()
            val call = meds.stops.single()
            assertEquals(today, call.lastDay)
            assertEquals(secondVersion.id, call.regimen.id)
            assertFalse(viewModel.uiState.value.showStop)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a version that has not started yet is stopped on its first day`() = runTest {
        val future = regimen(
            id = "reg-future",
            medicationId = "med-1",
            startDate = today.plusDays(10),
        )
        meds.observed["med-1"] = MutableStateFlow(
            withRegimens(medication(id = "med-1"), listOf(future)),
        )
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded && it.medication != null }
            viewModel.requestStop()
            assertEquals(future.startDate.toString(), viewModel.uiState.value.stopLastDay)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a malformed last day is not sent`() = runTest {
        meds.observed["med-1"] = MutableStateFlow(
            withRegimens(medication(id = "med-1"), listOf(secondVersion)),
        )
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitWhere { it.loaded && it.medication != null }
            viewModel.requestStop()
            viewModel.onLastDayChanged("18/11/2026")
            viewModel.confirmStop()

            assertTrue(viewModel.uiState.value.stopDateInvalid)
            assertEquals(0, meds.stops.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a viewer cannot stop`() = runTest {
        circles.roleFlow.value = MemberRole.VIEWER
        meds.observed["med-1"] = MutableStateFlow(
            withRegimens(medication(id = "med-1"), listOf(secondVersion)),
        )
        val viewModel = viewModel()
        viewModel.uiState.test {
            val state = awaitWhere { it.loaded && it.medication != null }
            assertFalse(state.canWrite)
            viewModel.requestStop()
            viewModel.confirmStop()

            assertEquals(0, meds.stops.size)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
