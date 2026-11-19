package io.github.lgpy.familycare.ui.patients

import app.cash.turbine.test
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientExportFormat
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.ui.export.PatientExportFiles
import io.github.lgpy.familycare.core.ui.export.PatientExportSources
import io.github.lgpy.familycare.core.ui.export.PatientExporter
import io.github.lgpy.familycare.core.ui.patient.TimelineSources
import io.github.lgpy.familycare.ui.FakeCareCircleRepository
import io.github.lgpy.familycare.ui.FakePatientRepository
import io.github.lgpy.familycare.ui.awaitWhere
import io.github.lgpy.familycare.ui.caregiverMember
import io.github.lgpy.familycare.ui.ownerMember
import io.github.lgpy.familycare.ui.testCircle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The export action (D30): who may export, the log-before-file rule, and the file. */
@OptIn(ExperimentalCoroutinesApi::class)
class PatientExportTest {

    private val patientId = PatientId("patient-1")

    private lateinit var patients: FakePatientRepository
    private lateinit var circles: FakeCareCircleRepository
    private lateinit var directory: File

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        directory = File(System.getProperty("java.io.tmpdir") ?: ".", "export-test-${System.nanoTime()}")
        patients = FakePatientRepository().apply {
            setPatient(
                Patient(
                    id = patientId,
                    circleId = testCircle.id,
                    displayName = "Avó Maria",
                ),
            )
        }
        circles = FakeCareCircleRepository().apply {
            setCircle(
                circle = testCircle,
                role = MemberRole.OWNER,
                members = listOf(ownerMember, caregiverMember),
            )
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        directory.deleteRecursively()
    }

    private fun viewModel() = PatientProfileViewModel(
        patientId = patientId.value,
        patientRepository = patients,
        careCircleRepository = circles,
        timelineSources = TimelineSources(emptyList()),
        exporter = PatientExporter(
            patientRepository = patients,
            sources = PatientExportSources(emptyList()),
            directory = directory,
        ),
    )

    @Test
    fun aViewerCannotExport() = runTest {
        circles.myRoleFlow.value = MemberRole.VIEWER

        viewModel().uiState.test {
            assertFalse(awaitWhere { it.loaded }.canExport)
        }
    }

    @Test
    fun aFailedLogWritesNoFile() = runTest {
        patients.exportResult = CoreResult.Failure(CoreError.Offline)
        val viewModel = viewModel()

        viewModel.export(PatientExportFormat.JSON)

        viewModel.uiState.test {
            val state = awaitWhere { it.exportError != null }
            assertEquals(CoreError.Offline, state.exportError)
            assertTrue(directory.listFiles().isNullOrEmpty())
            assertEquals(1, patients.exports.size)
        }
    }

    @Test
    fun aSuccessfulExportProducesAFileToShare() = runTest {
        val viewModel = viewModel()

        viewModel.export(PatientExportFormat.JSON)

        viewModel.uiState.test {
            val state = awaitWhere { it.exportShare != null }
            val share = state.exportShare!!
            assertTrue(share.file.exists())
            assertEquals(PatientExportFormat.JSON, share.format)
            assertTrue(share.file.name.contains("Avó-Maria"))
            assertTrue(share.file.name.startsWith("family-care-"))
            assertTrue(share.file.parentFile?.name == directory.name)
            assertEquals(patientId to PatientExportFormat.JSON, patients.exports.single())
            assertTrue(share.file.readText().contains("family-care-patient-export"))

            viewModel.onExportShared()
            assertTrue(awaitWhere { it.exportShare == null }.exportShare == null)
        }
    }
}
