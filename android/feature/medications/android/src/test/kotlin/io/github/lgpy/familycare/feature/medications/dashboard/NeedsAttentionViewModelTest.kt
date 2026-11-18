package io.github.lgpy.familycare.feature.medications.dashboard

import app.cash.turbine.test
import io.github.lgpy.familycare.core.domain.circle.MemberRole
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
 * The "Needs attention" card: only overdue/missed groups, the one-tap confirm
 * and the viewer's missing button.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NeedsAttentionViewModelTest {

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

    private fun viewModel() = NeedsAttentionViewModel(doses, meds, circles)

    private fun group(state: DoseState, slots: List<io.github.lgpy.familycare.feature.medications.domain.DoseSlot>) =
        todayGroup(
            slots = slots,
            statuses = slots.map { status(it, state) },
        )

    @Test
    fun `shows overdue and missed groups but not due ones`() = runTest {
        doses.todayFlow.value = listOf(
            group(DoseState.OVERDUE, listOf(firstSlot, secondSlot)),
            group(DoseState.MISSED, listOf(firstSlot)).copy(
                group = io.github.lgpy.familycare.feature.medications.domain.TimeSlotGroup(
                    patientId = testPatientId,
                    scheduledLocal = scheduled.plusHours(8),
                    slots = listOf(
                        io.github.lgpy.familycare.feature.medications.domain.DoseSlot(
                            regimenId = firstRegimen.id,
                            medicationId = firstRegimen.medicationId,
                            scheduledLocal = scheduled.plusHours(8),
                            instant = scheduled.plusHours(8)
                                .atZone(firstRegimen.timeZone)
                                .toInstant(),
                            quantity = java.math.BigDecimal.ONE,
                        ),
                    ),
                ),
            ),
            group(DoseState.DUE, listOf(secondSlot)),
        )

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded && it.groups.isNotEmpty() }
            assertEquals(2, state.groups.size)
            assertTrue(state.groups.all { it.state == DoseGroupState.OVERDUE || it.state == DoseGroupState.MISSED })
            assertEquals(DoseGroupState.OVERDUE, state.groups.first().state)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `confirming an overdue group writes one entry per slot`() = runTest {
        doses.todayFlow.value = listOf(group(DoseState.OVERDUE, listOf(firstSlot, secondSlot)))
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
    fun `a confirmed slot is not shown as needing attention`() = runTest {
        val existing = entry(firstSlot, givenByUserId = "u2")
        doses.todayFlow.value = listOf(
            todayGroup(
                slots = listOf(firstSlot),
                statuses = listOf(status(firstSlot, DoseState.TAKEN, listOf(existing))),
            ),
        )

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded }
            assertTrue(state.groups.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a viewer sees the rows without the button`() = runTest {
        circles.roleFlow.value = MemberRole.VIEWER
        doses.todayFlow.value = listOf(group(DoseState.OVERDUE, listOf(firstSlot)))

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded && it.groups.isNotEmpty() }
            assertFalse(state.canWrite)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a sync confirmation between tap and save is not double-logged`() = runTest {
        doses.todayFlow.value = listOf(group(DoseState.OVERDUE, listOf(firstSlot)))
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitWhere { it.loaded && it.groups.isNotEmpty() }
            // Simulate the row already carrying a confirmation.
            val existing = entry(firstSlot, givenByUserId = "u2")
            doses.todayFlow.value = listOf(
                todayGroup(
                    slots = listOf(firstSlot),
                    statuses = listOf(status(firstSlot, DoseState.TAKEN, listOf(existing))),
                ),
            )
            awaitWhere { it.groups.isEmpty() }

            viewModel.give(testPatientId, scheduled)
            assertEquals(0, doses.confirmations.size)
            assertNull(viewModel.uiState.value.warning?.givenBy)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
