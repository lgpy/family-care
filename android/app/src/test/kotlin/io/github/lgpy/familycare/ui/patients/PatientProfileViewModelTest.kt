package io.github.lgpy.familycare.ui.patients

import app.cash.turbine.test
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.ui.patient.TimelineEntry
import io.github.lgpy.familycare.core.ui.patient.TimelineKind
import io.github.lgpy.familycare.core.ui.patient.TimelineSource
import io.github.lgpy.familycare.core.ui.patient.TimelineSources
import io.github.lgpy.familycare.ui.FakeCareCircleRepository
import io.github.lgpy.familycare.ui.FakePatientRepository
import io.github.lgpy.familycare.ui.awaitWhere
import io.github.lgpy.familycare.ui.caregiverMember
import io.github.lgpy.familycare.ui.ownerMember
import io.github.lgpy.familycare.ui.testCircle
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

/** The patient profile: the merged, month-grouped history and who may edit. */
@OptIn(ExperimentalCoroutinesApi::class)
class PatientProfileViewModelTest {

    private val patientId = PatientId("patient-1")
    private val zone = ZoneId.of("UTC")

    private lateinit var patients: FakePatientRepository
    private lateinit var circles: FakeCareCircleRepository

    private val exporter by lazy {
        io.github.lgpy.familycare.core.ui.export.PatientExporter(
            patientRepository = patients,
            sources = io.github.lgpy.familycare.core.ui.export.PatientExportSources(emptyList()),
            directory = java.io.File(System.getProperty("java.io.tmpdir"), "family-care-test-exports"),
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        patients = FakePatientRepository()
        circles = FakeCareCircleRepository()
        circles.setCircle(
            circle = testCircle,
            role = MemberRole.OWNER,
            members = listOf(ownerMember, caregiverMember),
        )
        patients.setPatient(
            Patient(
                id = patientId,
                circleId = testCircle.id,
                displayName = "Avó Maria",
            ),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun entry(id: String, at: String): TimelineEntry = TimelineEntry(
        id = id,
        at = Instant.parse(at),
        title = id,
        detail = null,
        kind = TimelineKind.OTHER,
        route = null,
    )

    private fun viewModel(vararg sources: TimelineSource) = PatientProfileViewModel(
        patientId = patientId.value,
        patientRepository = patients,
        careCircleRepository = circles,
        timelineSources = TimelineSources(sources.toList()),
        exporter = exporter,
        zone = zone,
    )

    @Test
    fun historyIsMergedNewestFirstAndGroupedByMonth() = runTest {
        val first = FakeSource(
            MutableStateFlow(listOf(entry("october", "2026-10-30T09:00:00Z"))),
        )
        val second = FakeSource(
            MutableStateFlow(
                listOf(
                    entry("november-early", "2026-11-02T09:00:00Z"),
                    entry("november-late", "2026-11-16T09:00:00Z"),
                ),
            ),
        )

        viewModel(first, second).uiState.test {
            val state = awaitWhere { it.loaded && it.history.isNotEmpty() }

            assertEquals("Avó Maria", state.displayName)
            assertTrue(state.canWrite)
            assertEquals(
                listOf(YearMonth.of(2026, 11), YearMonth.of(2026, 10)),
                state.history.map { it.month },
            )
            assertEquals(
                listOf("november-late", "november-early"),
                state.history[0].entries.map { it.id },
            )
            assertEquals(listOf("october"), state.history[1].entries.map { it.id })
        }
    }

    @Test
    fun noSourcesMeansAnEmptyHistory() = runTest {
        viewModel().uiState.test {
            val state = awaitWhere { it.loaded }

            assertTrue(state.history.isEmpty())
        }
    }

    @Test
    fun aViewerCannotEditThePatient() = runTest {
        circles.setCircle(
            circle = testCircle,
            role = MemberRole.VIEWER,
            members = listOf(ownerMember, caregiverMember.copy(role = MemberRole.VIEWER)),
        )

        viewModel().uiState.test {
            assertFalse(awaitWhere { it.loaded }.canWrite)
        }
    }

    @Test
    fun aPatientThatIsNotLocalIsNotFound() = runTest {
        val other = PatientProfileViewModel(
            patientId = "patient-missing",
            patientRepository = patients,
            careCircleRepository = circles,
            timelineSources = TimelineSources(emptyList()),
            exporter = exporter,
            zone = zone,
        )

        other.uiState.test {
            assertTrue(awaitWhere { it.loaded }.notFound)
        }
    }

    @Test
    fun anArchivedPatientIsReadOnlyButStillManageableByAnOwner() = runTest {
        patients.setPatient(patient(archived = true))

        viewModel().uiState.test {
            val state = awaitWhere { it.loaded }

            assertTrue(state.isArchived)
            assertFalse("An archived patient is read-only", state.canWrite)
            assertTrue("The owner may still unarchive or erase", state.canManage)
        }
    }

    @Test
    fun archiveAndUnarchiveAskTheRepository() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitWhere { it.loaded }

            viewModel.archive()
            assertEquals(listOf(patientId), patients.archived)

            viewModel.unarchive()
            assertEquals(listOf(patientId), patients.unarchived)
        }
    }

    @Test
    fun anArchiveFailureIsShownUntilDismissed() = runTest {
        patients.archiveResult = CoreResult.Failure(CoreError.NotAllowed)
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitWhere { it.loaded }

            viewModel.archive()
            assertEquals(CoreError.NotAllowed, awaitWhere { it.actionError != null }.actionError)

            viewModel.clearActionError()
            assertNull(awaitWhere { it.actionError == null }.actionError)
        }
    }

    @Test
    fun eraseEndsInTheErasedState() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitWhere { it.loaded }

            viewModel.erase()

            val state = awaitWhere { it.erased }
            assertFalse("The in-flight flag clears on success", state.erasing)
            assertEquals(listOf(patientId), patients.erased)
        }
    }

    @Test
    fun aFailedEraseReportsTheErrorAndDoesNotNavigateBack() = runTest {
        patients.eraseResult = CoreResult.Failure(CoreError.Offline)
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitWhere { it.loaded }

            viewModel.erase()

            val state = awaitWhere { it.actionError != null }
            assertEquals(CoreError.Offline, state.actionError)
            assertFalse(state.erasing)
            assertFalse(state.erased)
            assertEquals(listOf(patientId), patients.erased)
        }
    }

    private fun patient(archived: Boolean): Patient = Patient(
        id = patientId,
        circleId = testCircle.id,
        displayName = "Avó Maria",
        archivedAt = if (archived) kotlin.time.Instant.parse("2026-11-19T10:00:00Z") else null,
    )

    private class FakeSource(
        private val flow: MutableStateFlow<List<TimelineEntry>>,
    ) : TimelineSource {
        override fun entries(patientId: PatientId): Flow<List<TimelineEntry>> = flow
    }
}
