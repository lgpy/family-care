package io.github.lgpy.familycare.feature.medications.missed

import app.cash.turbine.test
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.feature.medications.FakeCareCircleRepository
import io.github.lgpy.familycare.feature.medications.FakeDoseLogRepository
import io.github.lgpy.familycare.feature.medications.FakeMedicationRepository
import io.github.lgpy.familycare.feature.medications.awaitWhere
import io.github.lgpy.familycare.feature.medications.domain.DoseOutcome
import io.github.lgpy.familycare.feature.medications.domain.DoseSlot
import io.github.lgpy.familycare.feature.medications.domain.DoseState
import io.github.lgpy.familycare.feature.medications.domain.SkipReason
import io.github.lgpy.familycare.feature.medications.entry
import io.github.lgpy.familycare.feature.medications.medication
import io.github.lgpy.familycare.feature.medications.regimen
import io.github.lgpy.familycare.feature.medications.slot
import io.github.lgpy.familycare.feature.medications.status
import io.github.lgpy.familycare.feature.medications.testPatientId
import io.github.lgpy.familycare.feature.medications.todayGroup
import io.github.lgpy.familycare.feature.medications.withRegimens
import java.time.LocalDate
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The missed-doses list: what it shows, "not given", and the viewer. */
@OptIn(ExperimentalCoroutinesApi::class)
class MissedDosesViewModelTest {

    private lateinit var doses: FakeDoseLogRepository
    private lateinit var meds: FakeMedicationRepository
    private lateinit var circles: FakeCareCircleRepository

    private val yesterday: LocalDateTime = LocalDateTime.of(2026, 11, 21, 22, 0)
    private val twoDaysAgo: LocalDateTime = LocalDateTime.of(2026, 11, 20, 13, 0)
    private val firstRegimen = regimen(id = "reg-1", medicationId = "med-1")
    private val secondRegimen = regimen(id = "reg-2", medicationId = "med-2")

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

    private fun viewModel() = MissedDosesViewModel(doses, meds, circles)

    private fun group(at: LocalDateTime, vararg states: DoseState): Pair<List<DoseSlot>, List<DoseState>> {
        val slots = listOf(slot(firstRegimen, at), slot(secondRegimen, at)).take(states.size)
        return slots to states.toList()
    }

    private fun recent(vararg groups: Pair<List<DoseSlot>, List<DoseState>>) {
        doses.recentFlow.value = groups.map { (slots, states) ->
            todayGroup(
                slots = slots,
                statuses = slots.zip(states).map { (slot, state) ->
                    val entries = when (state) {
                        DoseState.TAKEN -> listOf(entry(slot))
                        DoseState.SKIPPED -> listOf(entry(slot, outcome = DoseOutcome.SKIPPED, skipReason = SkipReason.OTHER))
                        else -> emptyList()
                    }
                    status(slot, state, entries)
                },
            )
        }
    }

    @Test
    fun `lists only groups with a missed dose, by day`() = runTest {
        recent(
            group(yesterday, DoseState.MISSED, DoseState.MISSED),
            group(twoDaysAgo, DoseState.TAKEN, DoseState.TAKEN),
            group(twoDaysAgo.plusHours(7), DoseState.TAKEN, DoseState.MISSED),
        )

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded && it.days.isNotEmpty() }
            assertEquals(
                listOf(LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 20)),
                state.days.map { it.date },
            )
            assertEquals(1, state.days[1].groups.size)
            assertEquals(twoDaysAgo.plusHours(7), state.days[1].groups.single().dose.scheduledLocal)
            assertTrue(state.days.flatMap { it.groups }.all { it.open })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a dose still overdue is not missed yet`() = runTest {
        recent(group(yesterday, DoseState.OVERDUE), group(twoDaysAgo, DoseState.DUE))

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded }
            assertTrue(state.days.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `not given skips only the open slots, at the scheduled time`() = runTest {
        val at = twoDaysAgo.plusHours(7)
        recent(group(at, DoseState.TAKEN, DoseState.MISSED))
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitWhere { it.loaded && it.days.isNotEmpty() }
            viewModel.onNotGiven(testPatientId, at)

            val call = doses.confirmations.single()
            val open = slot(secondRegimen, at)
            assertEquals(listOf(open), call.group.slots)
            assertEquals(mapOf(open.slotId to SkipReason.OTHER), call.skipped)
            assertEquals(open.instant, call.actualAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a group acted on stays on the list, folded, once it is recorded`() = runTest {
        recent(group(yesterday, DoseState.MISSED))
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitWhere { it.loaded && it.days.isNotEmpty() }
            viewModel.onNotGiven(testPatientId, yesterday)
            recent(group(yesterday, DoseState.SKIPPED))

            val state = awaitWhere { it.days.singleOrNull()?.groups?.singleOrNull()?.open == false }
            assertEquals(yesterday, state.days.single().groups.single().dose.scheduledLocal)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a group recorded elsewhere leaves the list`() = runTest {
        recent(group(yesterday, DoseState.MISSED))

        viewModel().uiState.test {
            awaitWhere { it.loaded && it.days.isNotEmpty() }
            recent(group(yesterday, DoseState.TAKEN))
            awaitWhere { it.days.isEmpty() }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a viewer cannot record anything`() = runTest {
        circles.roleFlow.value = MemberRole.VIEWER
        recent(group(yesterday, DoseState.MISSED))
        val viewModel = viewModel()

        viewModel.uiState.test {
            val state = awaitWhere { it.loaded && it.days.isNotEmpty() }
            assertFalse(state.canWrite)
            viewModel.onNotGiven(testPatientId, yesterday)
            assertTrue(doses.confirmations.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
