package io.github.lgpy.familycare.ui.patients

import io.github.lgpy.familycare.core.domain.circle.CareCircleId
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreField
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.PatientRules
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import io.github.lgpy.familycare.ui.FakeCareCircleRepository
import io.github.lgpy.familycare.ui.FakePatientRepository
import io.github.lgpy.familycare.ui.caregiverMember
import io.github.lgpy.familycare.ui.ownerMember
import io.github.lgpy.familycare.ui.testCircle
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
 * The patient form: defaults from [PatientRules], validation before the write,
 * the self-managed primary caregiver rule, and saving offline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PatientFormViewModelTest {

    private lateinit var patients: FakePatientRepository
    private lateinit var circles: FakeCareCircleRepository

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
        circles.setSetupState(
            io.github.lgpy.familycare.core.domain.circle.CircleSetupState.HasCircle(
                testCircle,
                MemberRole.OWNER,
            ),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(patientId: String? = null): PatientFormViewModel =
        PatientFormViewModel(
            patientId = patientId,
            patientRepository = patients,
            careCircleRepository = circles,
        )

    @Test
    fun aNewPatientStartsWithTheDocumentedDefaults() {
        val viewModel = viewModel()

        val state = viewModel.uiState.value
        assertTrue(state.loaded)
        assertTrue(state.isNew)
        assertTrue(state.canWrite)
        assertEquals("", state.displayName)
        assertNull(state.linkedUserId)
        assertNull(state.primaryCaregiverId)
        assertEquals(ReminderPolicy.PRIMARY_WITH_ESCALATION, state.reminderPolicy)
        assertEquals("30", state.escalationDelay)
        assertEquals("120", state.missedWindow)
        assertEquals("", state.agreementNote)
        assertTrue(state.errors.isEmpty())
    }

    @Test
    fun aLinkedUserBecomesThePrimaryCaregiver() {
        val viewModel = viewModel()
        viewModel.onNameChanged("Avó")
        viewModel.onPrimaryCaregiverChanged(ownerMember.userId)

        viewModel.onLinkedUserChanged(caregiverMember.userId)

        assertEquals(caregiverMember.userId, viewModel.uiState.value.primaryCaregiverId)
    }

    @Test
    fun savingASelfManagedPatientKeepsTheLinkedUserAsPrimary() {
        val viewModel = viewModel()
        viewModel.onNameChanged("  Avó  ")
        viewModel.onLinkedUserChanged(caregiverMember.userId)

        viewModel.save()

        val saved = patients.saved.single()
        assertEquals("Avó", saved.displayName)
        assertEquals(caregiverMember.userId, saved.linkedUserId)
        assertEquals(caregiverMember.userId, saved.primaryCaregiverId)
        assertEquals(testCircle.id, saved.circleId)
        assertEquals("30", saved.escalationDelayMinutes.toString())
        assertEquals(120, saved.missedWindowMinutes)
        assertTrue(viewModel.uiState.value.saved)
    }

    @Test
    fun aBlankNameIsRejectedWithoutSaving() {
        val viewModel = viewModel()
        viewModel.onNameChanged("   ")

        viewModel.save()

        assertTrue(CoreField.PATIENT_DISPLAY_NAME in viewModel.uiState.value.errors)
        assertTrue(patients.saved.isEmpty())
    }

    @Test
    fun aPatientWithoutALinkedUserMustChooseAPrimaryCaregiver() {
        val viewModel = viewModel()
        viewModel.onNameChanged("Avô")

        viewModel.save()

        assertTrue(CoreField.PATIENT_PRIMARY_CAREGIVER in viewModel.uiState.value.errors)
        assertTrue(patients.saved.isEmpty())
    }

    @Test
    fun allCaregiversDoesNotRequireAPrimaryCaregiver() {
        val viewModel = viewModel()
        viewModel.onNameChanged("Avô")
        viewModel.onReminderPolicyChanged(ReminderPolicy.ALL_CAREGIVERS)

        viewModel.save()

        assertTrue(patients.saved.isNotEmpty())
        assertEquals(ReminderPolicy.ALL_CAREGIVERS, patients.saved.single().reminderPolicy)
    }

    @Test
    fun aNonNumericDelayIsAFieldError() {
        val viewModel = viewModel()
        viewModel.onNameChanged("Avô")
        viewModel.onPrimaryCaregiverChanged(ownerMember.userId)
        viewModel.onEscalationDelayChanged("meia hora")

        viewModel.save()

        assertTrue(CoreField.PATIENT_ESCALATION_DELAY in viewModel.uiState.value.errors)
        assertTrue(patients.saved.isEmpty())
    }

    @Test
    fun anOutOfRangeDelayIsAFieldError() {
        val viewModel = viewModel()
        viewModel.onNameChanged("Avô")
        viewModel.onPrimaryCaregiverChanged(ownerMember.userId)
        viewModel.onEscalationDelayChanged("5000")

        viewModel.save()

        assertTrue(CoreField.PATIENT_ESCALATION_DELAY in viewModel.uiState.value.errors)
        assertTrue(patients.saved.isEmpty())
    }

    @Test
    fun theMissedWindowCannotBeZero() {
        val viewModel = viewModel()
        viewModel.onNameChanged("Avô")
        viewModel.onPrimaryCaregiverChanged(ownerMember.userId)
        viewModel.onMissedWindowChanged("0")

        viewModel.save()

        assertTrue(CoreField.PATIENT_MISSED_WINDOW in viewModel.uiState.value.errors)
        assertTrue(patients.saved.isEmpty())
    }

    @Test
    fun aPrimaryCaregiverWhoIsOnlyAViewerIsRejected() {
        circles.setCircle(
            circle = testCircle,
            role = MemberRole.OWNER,
            members = listOf(
                ownerMember,
                caregiverMember.copy(role = MemberRole.VIEWER),
            ),
        )
        val viewModel = viewModel()
        viewModel.onNameChanged("Avô")
        viewModel.onPrimaryCaregiverChanged(caregiverMember.userId)

        viewModel.save()

        assertTrue(CoreField.PATIENT_PRIMARY_CAREGIVER in viewModel.uiState.value.errors)
        assertTrue(patients.saved.isEmpty())
    }

    @Test
    fun aLongAgreementNoteIsAFieldError() {
        val viewModel = viewModel()
        viewModel.onNameChanged("Avô")
        viewModel.onPrimaryCaregiverChanged(ownerMember.userId)
        viewModel.onAgreementNoteChanged("x".repeat(PatientRules.MAX_AGREEMENT_NOTE_LENGTH + 1))

        viewModel.save()

        assertTrue(CoreField.PATIENT_AGREEMENT_NOTE in viewModel.uiState.value.errors)
        assertTrue(patients.saved.isEmpty())
    }

    @Test
    fun aViewerCannotSave() {
        circles.setCircle(
            circle = testCircle,
            role = MemberRole.VIEWER,
            members = listOf(ownerMember.copy(isMe = false), caregiverMember.copy(isMe = true)),
        )
        val viewModel = viewModel()
        viewModel.onNameChanged("Avô")
        viewModel.onPrimaryCaregiverChanged(ownerMember.userId)

        assertFalse(viewModel.uiState.value.canWrite)
        viewModel.save()

        assertTrue(patients.saved.isEmpty())
    }

    @Test
    fun savingOfflineShowsTheOfflineMessage() {
        patients.saveResult = CoreResult.Failure(CoreError.Offline)
        val viewModel = viewModel()
        viewModel.onNameChanged("Avó")
        viewModel.onLinkedUserChanged(caregiverMember.userId)

        viewModel.save()

        assertEquals(PatientFormError.Offline, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.saved)
    }

    @Test
    fun theRepositoryInvalidFieldsAreShownInline() {
        patients.saveResult =
            CoreResult.Failure(CoreError.Invalid(setOf(CoreField.PATIENT_DISPLAY_NAME)))
        val viewModel = viewModel()
        viewModel.onNameChanged("Avó")
        viewModel.onLinkedUserChanged(caregiverMember.userId)

        viewModel.save()

        assertTrue(CoreField.PATIENT_DISPLAY_NAME in viewModel.uiState.value.errors)
    }

    @Test
    fun anExistingPatientLoadsAndIsUpdatedInPlace() {
        val existing = Patient(
            id = PatientId("patient-1"),
            circleId = CareCircleId("circle-1"),
            displayName = "Avô Zé",
            linkedUserId = null,
            birthYear = 1940,
            reminderPolicy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
            primaryCaregiverId = ownerMember.userId,
            escalationDelayMinutes = 45,
            missedWindowMinutes = 90,
            agreementNote = "Falámos com ele.",
        )
        patients.setPatient(existing)

        val viewModel = viewModel(patientId = "patient-1")

        assertEquals("Avô Zé", viewModel.uiState.value.displayName)
        assertEquals("1940", viewModel.uiState.value.birthYear)
        assertEquals("45", viewModel.uiState.value.escalationDelay)
        assertEquals("90", viewModel.uiState.value.missedWindow)
        assertEquals("Falámos com ele.", viewModel.uiState.value.agreementNote)
        assertFalse(viewModel.uiState.value.isNew)

        viewModel.onNameChanged("Avô José")
        viewModel.save()

        val saved = patients.saved.single()
        assertEquals("patient-1", saved.id.value)
        assertEquals("Avô José", saved.displayName)
        assertEquals(1940, saved.birthYear)
        assertEquals(45, saved.escalationDelayMinutes)
        assertEquals("Falámos com ele.", saved.agreementNote)
    }

    @Test
    fun anUnknownPatientIsReportedAsNotFound() {
        val viewModel = viewModel(patientId = "missing")

        assertTrue(viewModel.uiState.value.notFound)
        assertTrue(viewModel.uiState.value.loaded)
    }

    @Test
    fun aNewPatientIsNotFlaggedForAMissingPrimaryCaregiver() {
        val viewModel = viewModel()

        assertFalse(viewModel.uiState.value.needsPrimaryCaregiver)
        assertNull(viewModel.uiState.value.primaryCaregiverId)
    }

    @Test
    fun anExistingPatientWithoutAPrimaryCaregiverIsFlaggedAndRefusesToSave() {
        // D24: a removed member left the patient with nobody to remind.
        patients.setPatient(
            Patient(
                id = PatientId("patient-orphan"),
                circleId = CareCircleId("circle-1"),
                displayName = "Avó",
                linkedUserId = null,
                reminderPolicy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
                primaryCaregiverId = null,
            ),
        )

        val viewModel = viewModel(patientId = "patient-orphan")

        assertTrue(viewModel.uiState.value.loaded)
        assertFalse(viewModel.uiState.value.isNew)
        assertTrue(viewModel.uiState.value.needsPrimaryCaregiver)
        // The form opens without crashing and preselects nothing.
        assertNull(viewModel.uiState.value.primaryCaregiverId)
        assertFalse(viewModel.uiState.value.isSelfManaged)

        viewModel.save()

        assertTrue(CoreField.PATIENT_PRIMARY_CAREGIVER in viewModel.uiState.value.errors)
        assertTrue(patients.saved.isEmpty())
    }

    @Test
    fun anExistingPatientWhoseLinkedUserWasClearedLoadsAsANormalPatient() {
        patients.setPatient(
            Patient(
                id = PatientId("patient-1"),
                circleId = CareCircleId("circle-1"),
                displayName = "Avó",
                linkedUserId = null,
                reminderPolicy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
                primaryCaregiverId = caregiverMember.userId,
            ),
        )

        val viewModel = viewModel(patientId = "patient-1")

        assertNull(viewModel.uiState.value.linkedUserId)
        assertFalse(viewModel.uiState.value.needsPrimaryCaregiver)
        assertEquals(caregiverMember.userId, viewModel.uiState.value.primaryCaregiverId)

        viewModel.save()

        val saved = patients.saved.single()
        assertNull(saved.linkedUserId)
        assertEquals(caregiverMember.userId, saved.primaryCaregiverId)
    }

    @Test
    fun anExistingAllCaregiversPatientWithoutAPrimaryIsNotFlagged() {
        patients.setPatient(
            Patient(
                id = PatientId("patient-1"),
                circleId = CareCircleId("circle-1"),
                displayName = "Avó",
                reminderPolicy = ReminderPolicy.ALL_CAREGIVERS,
                primaryCaregiverId = null,
            ),
        )

        val viewModel = viewModel(patientId = "patient-1")

        assertFalse(viewModel.uiState.value.needsPrimaryCaregiver)

        viewModel.save()

        assertEquals(ReminderPolicy.ALL_CAREGIVERS, patients.saved.single().reminderPolicy)
    }

    @Test
    fun errorsAreClearedWhenTheFieldChanges() {
        val viewModel = viewModel()
        viewModel.save()
        assertTrue(CoreField.PATIENT_DISPLAY_NAME in viewModel.uiState.value.errors)

        viewModel.onNameChanged("Avó")

        assertFalse(CoreField.PATIENT_DISPLAY_NAME in viewModel.uiState.value.errors)
    }
}
