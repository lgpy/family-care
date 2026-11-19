package io.github.lgpy.familycare.feature.medications.dashboard

import app.cash.turbine.test
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.medication.PillColor
import io.github.lgpy.familycare.core.domain.medication.PillLook
import io.github.lgpy.familycare.core.domain.medication.PillShape
import io.github.lgpy.familycare.feature.medications.FakeCareCircleRepository
import io.github.lgpy.familycare.feature.medications.FakeDoseLogRepository
import io.github.lgpy.familycare.feature.medications.FakeMedicationRepository
import io.github.lgpy.familycare.feature.medications.awaitWhere
import io.github.lgpy.familycare.feature.medications.data.GivenBy
import io.github.lgpy.familycare.feature.medications.domain.DoseState
import io.github.lgpy.familycare.feature.medications.entry
import io.github.lgpy.familycare.feature.medications.medication
import io.github.lgpy.familycare.feature.medications.regimen
import io.github.lgpy.familycare.feature.medications.slot
import io.github.lgpy.familycare.feature.medications.status
import io.github.lgpy.familycare.feature.medications.testPatientId
import io.github.lgpy.familycare.feature.medications.todayGroup
import io.github.lgpy.familycare.feature.medications.withRegimens
import java.time.LocalDateTime
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
 * The "Doses today" card: the aggregate state, the one-tap confirm, the
 * double-confirmation warning, and the viewer's missing button.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DosesTodayViewModelTest {

    private lateinit var doses: FakeDoseLogRepository
    private lateinit var meds: FakeMedicationRepository
    private lateinit var circles: FakeCareCircleRepository

    private val scheduled: LocalDateTime = LocalDateTime.of(2026, 11, 16, 8, 0)
    private val firstRegimen = regimen(id = "reg-1", medicationId = "med-1")
    private val secondRegimen = regimen(id = "reg-2", medicationId = "med-2")
    private val firstSlot = slot(firstRegimen, scheduled)
    private val secondSlot = slot(secondRegimen, scheduled)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        doses = FakeDoseLogRepository()
        meds = FakeMedicationRepository()
        circles = FakeCareCircleRepository()
        meds.activeFlow.value = listOf(
            withRegimens(medication(id = "med-1", name = "Metformin"), listOf(firstRegimen)),
            withRegimens(medication(id = "med-2", name = "Amlodipine"), listOf(secondRegimen)),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = DosesTodayViewModel(doses, meds, circles)

    @Test
    fun `shows a patient's slot with its count and the derived state`() = runTest {
        doses.todayFlow.value = listOf(todayGroup(listOf(firstSlot, secondSlot)))

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded && it.groups.isNotEmpty() }
            val row = state.groups.single()
            assertEquals("Grandpa", row.patientName)
            assertEquals(2, row.doseCount)
            assertEquals(DoseGroupState.DUE, row.state)
            assertEquals(8, row.time.hour)
            assertTrue(state.canWrite)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pill looks follow the dose order of the group`() = runTest {
        val metformin = PillLook(
            shape = PillShape.OBLONG,
            color = PillColor.WHITE,
            score = true,
        )
        val amlodipine = PillLook(
            shape = PillShape.ROUND,
            color = PillColor.YELLOW,
        )
        meds.activeFlow.value = listOf(
            withRegimens(
                medication(id = "med-1", name = "Metformin", pillLook = metformin),
                listOf(firstRegimen),
            ),
            withRegimens(
                medication(id = "med-2", name = "Amlodipine", pillLook = amlodipine),
                listOf(secondRegimen),
            ),
        )
        doses.todayFlow.value = listOf(todayGroup(listOf(firstSlot, secondSlot)))

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded && it.groups.isNotEmpty() }
            assertEquals(listOf(metformin, amlodipine), state.groups.single().pills)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `gives a whole group in one confirm call`() = runTest {
        doses.todayFlow.value = listOf(todayGroup(listOf(firstSlot, secondSlot)))
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitWhere { it.loaded && it.groups.isNotEmpty() }
            viewModel.give(testPatientId, scheduled)

            val call = doses.confirmations.single()
            assertEquals(2, call.group.slotCount)
            assertEquals(GivenBy.Me, call.givenBy)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `warns before confirming a group that already has an entry`() = runTest {
        val existing = entry(firstSlot, givenByUserId = "u2")
        doses.todayFlow.value = listOf(
            todayGroup(
                slots = listOf(firstSlot),
                statuses = listOf(status(firstSlot, DoseState.TAKEN, listOf(existing))),
            ),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitWhere { it.loaded && it.groups.isNotEmpty() }
            viewModel.give(testPatientId, scheduled)

            assertEquals("Mom", viewModel.uiState.value.warning?.givenBy?.name)
            assertTrue(viewModel.uiState.value.groups.single().hasConfirmation)
            assertEquals(0, doses.confirmations.size)

            viewModel.giveAnyway()
            assertEquals(1, doses.confirmations.size)
            assertNull(viewModel.uiState.value.warning)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a viewer sees the rows without the button`() = runTest {
        circles.roleFlow.value = MemberRole.VIEWER
        doses.todayFlow.value = listOf(todayGroup(listOf(firstSlot)))

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded && it.groups.isNotEmpty() }
            assertFalse(state.canWrite)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no medications today means an empty card`() = runTest {
        viewModel().uiState.test {
            val state = awaitWhere { it.loaded }
            assertTrue(state.groups.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a taken group reports the giver and the time`() = runTest {
        val existing = entry(
            firstSlot,
            actualAt = scheduled.atZone(firstRegimen.timeZone).toInstant().plusSeconds(4 * 60),
            givenByOther = true,
            givenByName = "Ana",
        )
        doses.todayFlow.value = listOf(
            todayGroup(
                slots = listOf(firstSlot),
                statuses = listOf(status(firstSlot, DoseState.TAKEN, listOf(existing))),
            ),
        )

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded && it.groups.isNotEmpty() }
            val row = state.groups.single()
            assertEquals(DoseGroupState.TAKEN, row.state)
            assertEquals("Ana", row.givenBy?.name)
            assertEquals(8, row.at?.hour)
            assertEquals(4, row.at?.minute)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
