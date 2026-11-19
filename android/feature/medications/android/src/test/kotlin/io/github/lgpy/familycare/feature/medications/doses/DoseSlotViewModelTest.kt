package io.github.lgpy.familycare.feature.medications.doses

import app.cash.turbine.test
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.feature.medications.FakeCareCircleRepository
import io.github.lgpy.familycare.feature.medications.FakeDoseLogRepository
import io.github.lgpy.familycare.feature.medications.FakeMedicationRepository
import io.github.lgpy.familycare.feature.medications.FakePatientRepository
import io.github.lgpy.familycare.feature.medications.awaitWhere
import io.github.lgpy.familycare.feature.medications.data.GivenBy
import io.github.lgpy.familycare.feature.medications.domain.DoseOutcome
import io.github.lgpy.familycare.feature.medications.domain.DoseState
import io.github.lgpy.familycare.feature.medications.domain.SkipReason
import io.github.lgpy.familycare.feature.medications.entry
import io.github.lgpy.familycare.feature.medications.medication
import io.github.lgpy.familycare.feature.medications.patient
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The dose-slot screen: exceptions per medication, one group confirm for the
 * untouched slots, corrections for the rest, and "given by".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DoseSlotViewModelTest {

    private lateinit var doses: FakeDoseLogRepository
    private lateinit var meds: FakeMedicationRepository
    private lateinit var patients: FakePatientRepository
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
        patients = FakePatientRepository()
        circles = FakeCareCircleRepository()
        patients.setPatient(patient())
        meds.activeFlow.value = listOf(
            withRegimens(medication(id = "med-1", name = "Metformin"), listOf(firstRegimen)),
            withRegimens(medication(id = "med-2", name = "Amlodipine"), listOf(secondRegimen)),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = DoseSlotViewModel(
        patientId = testPatientId.value,
        scheduledLocal = scheduled,
        doseLogRepository = doses,
        medicationRepository = meds,
        patientRepository = patients,
        careCircleRepository = circles,
    )

    @Test
    fun `loads each medication of the slot with its name and quantity`() = runTest {
        doses.todayFlow.value = listOf(todayGroup(listOf(firstSlot, secondSlot)))

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded && it.items.size == 2 }
            assertEquals("Grandpa", state.patientName)
            assertEquals(listOf("Metformin", "Amlodipine"), state.items.map { it.name })
            assertEquals(DoseOutcome.TAKEN, state.items.first().outcome)
            assertTrue(state.canWrite)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saves the untouched slots in one call with an exception`() = runTest {
        doses.todayFlow.value = listOf(todayGroup(listOf(firstSlot, secondSlot)))
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitWhere { it.loaded && it.items.size == 2 }
            viewModel.onOutcome("reg-2", DoseOutcome.SKIPPED)
            viewModel.onSkipReason("reg-2", SkipReason.REFUSED)
            viewModel.onNoteChanged("  refused at breakfast  ")
            viewModel.save()

            val call = doses.confirmations.single()
            assertEquals(2, call.group.slotCount)
            assertEquals(secondSlot.slotId, call.skipped.keys.single())
            assertEquals(SkipReason.REFUSED, call.skipped.values.single())
            assertEquals("refused at breakfast", call.note)
            assertEquals(GivenBy.Me, call.givenBy)
            assertTrue(viewModel.uiState.value.saved)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `changing an existing entry corrects it instead of confirming again`() = runTest {
        val existing = entry(firstSlot, givenByUserId = "u1")
        doses.todayFlow.value = listOf(
            todayGroup(
                slots = listOf(firstSlot),
                statuses = listOf(status(firstSlot, DoseState.TAKEN, listOf(existing))),
            ),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitWhere { it.loaded && it.items.size == 1 }
            viewModel.onOutcome("reg-1", DoseOutcome.SKIPPED)
            viewModel.onSkipReason("reg-1", SkipReason.NOT_NEEDED)
            viewModel.save()

            val correction = doses.corrections.single()
            assertEquals(existing.id, correction.entry.id)
            assertEquals(DoseOutcome.SKIPPED, correction.outcome)
            assertEquals(SkipReason.NOT_NEEDED, correction.skipReason)
            assertEquals(0, doses.confirmations.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saving an unchanged slot writes nothing`() = runTest {
        val existing = entry(firstSlot, givenByUserId = "u1")
        doses.todayFlow.value = listOf(
            todayGroup(
                slots = listOf(firstSlot),
                statuses = listOf(status(firstSlot, DoseState.TAKEN, listOf(existing))),
            ),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitWhere { it.loaded && it.items.size == 1 }
            viewModel.save()

            assertTrue(viewModel.uiState.value.saved)
            assertEquals(0, doses.confirmations.size)
            assertEquals(0, doses.corrections.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `given by another member is passed through`() = runTest {
        doses.todayFlow.value = listOf(todayGroup(listOf(firstSlot)))
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitWhere { it.loaded && it.items.size == 1 }
            viewModel.onGivenByMember("u2")
            viewModel.save()

            assertEquals(GivenBy.Member("u2"), doses.confirmations.single().givenBy)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `someone else keeps the optional name`() = runTest {
        doses.todayFlow.value = listOf(todayGroup(listOf(firstSlot)))
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitWhere { it.loaded && it.items.size == 1 }
            viewModel.onGivenBySomeoneElse()
            viewModel.onSomeoneElseName("  Ana  ")
            viewModel.save()

            assertEquals(GivenBy.SomeoneElse("Ana"), doses.confirmations.single().givenBy)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a viewer cannot save`() = runTest {
        circles.roleFlow.value = MemberRole.VIEWER
        doses.todayFlow.value = listOf(todayGroup(listOf(firstSlot)))
        val viewModel = viewModel()

        viewModel.uiState.test {
            val state = awaitWhere { it.loaded && it.items.size == 1 }
            assertFalse(state.canWrite)
            viewModel.save()

            assertEquals(0, doses.confirmations.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a slot that is no longer due today is reported`() = runTest {
        viewModel().uiState.test {
            val state = awaitWhere { it.loaded }
            assertTrue(state.notFound)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unchecking a pill marks it skipped and splits the give summary`() = runTest {
        doses.todayFlow.value = listOf(todayGroup(listOf(firstSlot, secondSlot)))
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitWhere { it.loaded && it.items.size == 2 }
            viewModel.onToggle("reg-2")
            val state = awaitWhere { it.items[1].outcome == DoseOutcome.SKIPPED }

            assertEquals(DoseOutcome.TAKEN, state.items[0].outcome)
            assertEquals(SkipReason.REFUSED, state.items[1].skipReason)
            assertEquals(1, state.givenCount)
            assertEquals(1, state.skippedCount)
            assertEquals(GiveSummary.GiveSome(given = 1, skipped = 1), state.giveSummary)
            assertFalse(state.confirmed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the button follows the toggles - give all, give some, skip all`() = runTest {
        doses.todayFlow.value = listOf(todayGroup(listOf(firstSlot, secondSlot)))
        val viewModel = viewModel()

        viewModel.uiState.test {
            var state = awaitWhere { it.loaded && it.items.size == 2 }
            assertEquals(GiveSummary.GiveAll(2), state.giveSummary)

            viewModel.onToggle("reg-1")
            state = awaitWhere { it.items[0].outcome == DoseOutcome.SKIPPED }
            assertEquals(GiveSummary.GiveSome(given = 1, skipped = 1), state.giveSummary)

            viewModel.onToggle("reg-2")
            state = awaitWhere { it.skippedCount == 2 }
            assertEquals(GiveSummary.SkipAll(2), state.giveSummary)

            viewModel.onToggle("reg-2")
            state = awaitWhere { it.items[1].outcome == DoseOutcome.TAKEN }
            assertEquals(GiveSummary.GiveSome(given = 1, skipped = 1), state.giveSummary)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saving writes each medication's toggled outcome`() = runTest {
        doses.todayFlow.value = listOf(todayGroup(listOf(firstSlot, secondSlot)))
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitWhere { it.loaded && it.items.size == 2 }
            viewModel.onToggle("reg-2")
            viewModel.save()

            val call = doses.confirmations.single()
            assertEquals(2, call.group.slotCount)
            assertEquals(
                listOf(firstSlot.slotId, secondSlot.slotId),
                call.group.slots.map { it.slotId },
            )
            assertEquals(mapOf(secondSlot.slotId to SkipReason.REFUSED), call.skipped)
            assertTrue(viewModel.uiState.value.saved)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a fully recorded slot opens read-only until edit`() = runTest {
        val firstEntry = entry(firstSlot, id = "entry-1")
        val secondEntry = entry(secondSlot, id = "entry-2")
        doses.todayFlow.value = listOf(
            todayGroup(
                slots = listOf(firstSlot, secondSlot),
                statuses = listOf(
                    status(firstSlot, DoseState.TAKEN, listOf(firstEntry)),
                    status(secondSlot, DoseState.TAKEN, listOf(secondEntry)),
                ),
            ),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            var state = awaitWhere { it.loaded && it.items.size == 2 }
            assertTrue(state.confirmed)

            viewModel.onEdit()
            state = awaitWhere { !it.confirmed }
            assertTrue(state.editing)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
