package io.github.lgpy.familycare.feature.medications.dashboard

import app.cash.turbine.test
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.feature.medications.FakeCareCircleRepository
import io.github.lgpy.familycare.feature.medications.FakeDoseLogRepository
import io.github.lgpy.familycare.feature.medications.FakeMedicationRepository
import io.github.lgpy.familycare.feature.medications.awaitWhere
import io.github.lgpy.familycare.feature.medications.data.GivenBy
import io.github.lgpy.familycare.feature.medications.domain.DoseState
import io.github.lgpy.familycare.feature.medications.domain.DoseStatusRules
import io.github.lgpy.familycare.feature.medications.entry
import io.github.lgpy.familycare.feature.medications.medication
import io.github.lgpy.familycare.feature.medications.regimen
import io.github.lgpy.familycare.feature.medications.slot
import io.github.lgpy.familycare.feature.medications.status
import io.github.lgpy.familycare.feature.medications.testPatientId
import io.github.lgpy.familycare.feature.medications.todayGroup
import io.github.lgpy.familycare.feature.medications.withRegimens
import java.time.Instant
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

    @Test
    fun `counts the missed groups of earlier days, not their confirmed ones`() = runTest {
        val yesterday = scheduled.minusDays(1)
        val missedSlot = slot(firstRegimen, yesterday)
        val takenSlot = slot(firstRegimen, yesterday.plusHours(5))
        doses.recentFlow.value = listOf(
            todayGroup(slots = listOf(missedSlot), statuses = listOf(status(missedSlot, DoseState.MISSED))),
            todayGroup(
                slots = listOf(takenSlot),
                statuses = listOf(status(takenSlot, DoseState.TAKEN, listOf(entry(takenSlot)))),
            ),
        )

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded && it.missedEarlier.isNotEmpty() }
            assertEquals(1, state.missedEarlier.size)
            assertTrue(state.groups.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A group of [at]'s slots with the status the domain derives at [now]
     * (patient defaults: 30 minutes to overdue, 120 to missed).
     */
    private fun groupAt(
        now: Instant,
        at: LocalDateTime,
        confirmed: Boolean = false,
    ) = slot(firstRegimen, at).let { doseSlot ->
        val entries = if (confirmed) listOf(entry(doseSlot)) else emptyList()
        todayGroup(
            slots = listOf(doseSlot),
            statuses = listOf(
                DoseStatusRules.doseStatus(
                    slot = doseSlot,
                    entries = entries,
                    now = now,
                    escalationDelayMinutes = 30,
                    missedWindowMinutes = 120,
                ),
            ),
        )
    }

    private val lateEvening: LocalDateTime = LocalDateTime.of(2026, 11, 19, 23, 0)

    @Test
    fun `a late dose still overdue after midnight stays in needs attention`() = runTest {
        // Fixed clock 00:20 on the 20th (Lisbon is UTC+0): the 23:00 dose of the
        // 19th is OVERDUE (past the 30-minute delay, inside the 120-minute window)
        // but it is yesterday's, so only the recent list has it.
        val now = Instant.parse("2026-11-20T00:20:00Z")
        val yesterdays = groupAt(now, lateEvening)
        assertEquals(DoseState.OVERDUE, yesterdays.statuses.single().state)
        doses.todayFlow.value = emptyList()
        doses.recentFlow.value = listOf(yesterdays)

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded && it.groups.isNotEmpty() }
            val row = state.groups.single()
            assertEquals(DoseGroupState.OVERDUE, row.state)
            assertEquals(lateEvening, row.scheduledLocal)
            assertTrue("not missed yet", state.missedEarlier.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a late dose still due after midnight is shown too`() = runTest {
        val now = Instant.parse("2026-11-20T00:10:00Z")
        val yesterdays = groupAt(now, lateEvening.plusMinutes(50))
        assertEquals(DoseState.DUE, yesterdays.statuses.single().state)
        doses.recentFlow.value = listOf(yesterdays)

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded && it.groups.isNotEmpty() }
            assertEquals(DoseGroupState.DUE, state.groups.single().state)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `yesterday's dose leaves needs attention when it turns missed`() = runTest {
        // 01:10: past the 120-minute window of the 23:00 dose.
        val now = Instant.parse("2026-11-20T01:10:00Z")
        val yesterdays = groupAt(now, lateEvening)
        assertEquals(DoseState.MISSED, yesterdays.statuses.single().state)
        doses.recentFlow.value = listOf(yesterdays)

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded && it.missedEarlier.isNotEmpty() }
            assertTrue(state.groups.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `yesterday's dose leaves needs attention when it is confirmed`() = runTest {
        val now = Instant.parse("2026-11-20T00:20:00Z")
        doses.recentFlow.value = listOf(groupAt(now, lateEvening))
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitWhere { it.loaded && it.groups.isNotEmpty() }
            doses.recentFlow.value = listOf(groupAt(now, lateEvening, confirmed = true))

            val state = awaitWhere { it.groups.isEmpty() }
            assertTrue(state.missedEarlier.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `giving yesterday's overdue dose confirms that group`() = runTest {
        val now = Instant.parse("2026-11-20T00:20:00Z")
        doses.recentFlow.value = listOf(groupAt(now, lateEvening))
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitWhere { it.loaded && it.groups.isNotEmpty() }
            viewModel.give(testPatientId, lateEvening)

            assertEquals(lateEvening, doses.confirmations.single().group.scheduledLocal)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `earlier rows come before today's`() = runTest {
        val now = Instant.parse("2026-11-20T00:20:00Z")
        doses.recentFlow.value = listOf(groupAt(now, lateEvening))
        val todays = slot(firstRegimen, LocalDateTime.of(2026, 11, 20, 0, 0))
        doses.todayFlow.value = listOf(
            todayGroup(slots = listOf(todays), statuses = listOf(status(todays, DoseState.OVERDUE))),
        )

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded && it.groups.size == 2 }
            assertEquals(
                listOf(lateEvening, LocalDateTime.of(2026, 11, 20, 0, 0)),
                state.groups.map { it.scheduledLocal },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `give anyway writes only the slots without an entry`() = runTest {
        val taken = entry(firstSlot, givenByUserId = "u2")
        doses.todayFlow.value = listOf(
            todayGroup(
                slots = listOf(firstSlot, secondSlot),
                statuses = listOf(
                    status(firstSlot, DoseState.TAKEN, listOf(taken)),
                    status(secondSlot, DoseState.OVERDUE),
                ),
            ),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitWhere { it.loaded && it.groups.isNotEmpty() }
            viewModel.give(testPatientId, scheduled)
            assertEquals(0, doses.confirmations.size)

            viewModel.giveAnyway()

            val call = doses.confirmations.single()
            assertEquals(listOf(secondSlot.slotId), call.group.slots.map { it.slotId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `give anyway writes nothing once every slot has an entry`() = runTest {
        val taken = entry(firstSlot, givenByUserId = "u2")
        doses.todayFlow.value = listOf(
            todayGroup(
                slots = listOf(firstSlot, secondSlot),
                statuses = listOf(
                    status(firstSlot, DoseState.TAKEN, listOf(taken)),
                    status(secondSlot, DoseState.OVERDUE),
                ),
            ),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitWhere { it.loaded && it.groups.isNotEmpty() }
            viewModel.give(testPatientId, scheduled)

            // Another phone confirms the rest while the dialog is open: the group
            // leaves the card and "Give anyway" has nothing to write.
            val other = entry(secondSlot, id = "entry-2", givenByUserId = "u2")
            doses.todayFlow.value = listOf(
                todayGroup(
                    slots = listOf(firstSlot, secondSlot),
                    statuses = listOf(
                        status(firstSlot, DoseState.TAKEN, listOf(taken)),
                        status(secondSlot, DoseState.TAKEN, listOf(other)),
                    ),
                ),
            )
            awaitWhere { it.groups.isEmpty() }

            viewModel.giveAnyway()
            assertEquals(0, doses.confirmations.size)
            assertNull(viewModel.uiState.value.warning)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
