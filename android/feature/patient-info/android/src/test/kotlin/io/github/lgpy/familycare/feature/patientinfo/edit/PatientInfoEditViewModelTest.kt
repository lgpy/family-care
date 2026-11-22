package io.github.lgpy.familycare.feature.patientinfo.edit

import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.feature.patientinfo.FakeCareCircleRepository
import io.github.lgpy.familycare.feature.patientinfo.FakePatientInfoRepository
import io.github.lgpy.familycare.feature.patientinfo.FakePatientRepository
import io.github.lgpy.familycare.feature.patientinfo.data.PatientInfoError
import io.github.lgpy.familycare.feature.patientinfo.data.PatientInfoResult
import io.github.lgpy.familycare.feature.patientinfo.domain.EmergencyContact
import io.github.lgpy.familycare.feature.patientinfo.domain.EmergencyContactField
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfoField
import io.github.lgpy.familycare.feature.patientinfo.patientInfo
import io.github.lgpy.familycare.feature.patientinfo.testPatientId
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
 * The patient-info edit form: what it loads, what it refuses and what it saves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PatientInfoEditViewModelTest {

    private lateinit var info: FakePatientInfoRepository
    private lateinit var circles: FakeCareCircleRepository
    private lateinit var patients: FakePatientRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        info = FakePatientInfoRepository()
        circles = FakeCareCircleRepository()
        patients = FakePatientRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = PatientInfoEditViewModel(
        patientId = testPatientId.value,
        patientInfoRepository = info,
        patientRepository = patients,
        careCircleRepository = circles,
    )

    // --- Loading -------------------------------------------------------------

    @Test
    fun `an existing record fills the form`() {
        info.setInfo(
            info = patientInfo(
                allergies = listOf("Penicillin"),
                conditions = listOf("Type 2 diabetes"),
                snsNumber = "123456789",
                familyDoctor = "Dra. Silva",
                healthCentre = "Centro de Belém",
                healthCentrePhone = "210000000",
                emergencyContacts = listOf(
                    EmergencyContact("Ana", relationship = "Daughter", phone = "912345678"),
                ),
                notes = "Morning appointments.",
            ),
        )

        val state = viewModel().uiState.value

        assertTrue(state.loaded)
        assertTrue(state.canWrite)
        assertEquals(listOf("Penicillin"), state.allergies)
        assertEquals(listOf("Type 2 diabetes"), state.conditions)
        assertEquals("123456789", state.snsNumber)
        assertEquals("Dra. Silva", state.familyDoctor)
        assertEquals("210000000", state.healthCentrePhone)
        assertEquals(
            listOf(ContactDraft(name = "Ana", relationship = "Daughter", phone = "912345678")),
            state.contacts,
        )
        assertEquals("Morning appointments.", state.notes)
    }

    @Test
    fun `no record yet starts from an empty form`() {
        val state = viewModel().uiState.value

        assertTrue(state.loaded)
        assertTrue(state.allergies.isEmpty())
        assertTrue(state.conditions.isEmpty())
        assertEquals("", state.snsNumber)
        assertTrue(state.contacts.isEmpty())
    }

    // --- Lists ---------------------------------------------------------------

    @Test
    fun `adding an allergy trims it and clears the draft`() {
        val viewModel = viewModel()

        viewModel.onAllergyDraftChanged("  Penicillin ")
        viewModel.addAllergy()

        assertEquals(listOf("Penicillin"), viewModel.uiState.value.allergies)
        assertEquals("", viewModel.uiState.value.allergyDraft)
    }

    @Test
    fun `a blank draft is not added`() {
        val viewModel = viewModel()

        viewModel.onAllergyDraftChanged("   ")
        assertFalse(viewModel.uiState.value.canAddAllergy)
        viewModel.addAllergy()

        assertTrue(viewModel.uiState.value.allergies.isEmpty())
    }

    @Test
    fun `an allergy can be removed by position`() {
        val viewModel = viewModel()
        listOf("Penicillin", "Nuts", "Latex").forEach {
            viewModel.onAllergyDraftChanged(it)
            viewModel.addAllergy()
        }

        viewModel.removeAllergy(1)

        assertEquals(listOf("Penicillin", "Latex"), viewModel.uiState.value.allergies)
    }

    @Test
    fun `conditions are added and removed the same way`() {
        val viewModel = viewModel()

        viewModel.onConditionDraftChanged("Diabetes")
        viewModel.addCondition()
        viewModel.removeCondition(0)

        assertTrue(viewModel.uiState.value.conditions.isEmpty())
    }

    @Test
    fun `a suggestion chip adds its entry`() {
        val viewModel = viewModel()

        viewModel.addAllergyEntry("Penicillin")
        viewModel.addConditionEntry("Asthma")

        assertEquals(listOf("Penicillin"), viewModel.uiState.value.allergies)
        assertEquals(listOf("Asthma"), viewModel.uiState.value.conditions)
    }

    @Test
    fun `an entry already selected is not added twice`() {
        val viewModel = viewModel()
        viewModel.addAllergyEntry("Penicillin")

        viewModel.addAllergyEntry("penicillin")

        assertEquals(listOf("Penicillin"), viewModel.uiState.value.allergies)
    }

    @Test
    fun `other free text is trimmed and kept as typed`() {
        val viewModel = viewModel()

        viewModel.addAllergyEntry("  Shellfish  ")
        viewModel.addConditionEntry("  Post-viral fatigue  ")

        assertEquals(listOf("Shellfish"), viewModel.uiState.value.allergies)
        assertEquals(listOf("Post-viral fatigue"), viewModel.uiState.value.conditions)
    }

    @Test
    fun `the patient name titles the form`() {
        assertEquals("Grandma", viewModel().uiState.value.patientName)
    }

    // --- Contacts ------------------------------------------------------------

    @Test
    fun `at most five emergency contacts can be added`() {
        val viewModel = viewModel()

        repeat(7) { viewModel.addContact() }

        assertEquals(5, viewModel.uiState.value.contacts.size)
        assertFalse(viewModel.uiState.value.canAddContact)
    }

    @Test
    fun `a contact can be removed`() {
        val viewModel = viewModel()
        viewModel.addContact()
        viewModel.addContact()
        viewModel.onContactNameChanged(0, "Ana")
        viewModel.onContactNameChanged(1, "Bruno")

        viewModel.removeContact(0)

        assertEquals(listOf(ContactDraft(name = "Bruno")), viewModel.uiState.value.contacts)
    }

    // --- Validation ----------------------------------------------------------

    @Test
    fun `an invalid SNS number is flagged and nothing is saved`() {
        val viewModel = viewModel()
        viewModel.onSnsNumberChanged("123")

        viewModel.save()

        assertTrue(viewModel.uiState.value.hasError(PatientInfoField.SNS_NUMBER))
        assertTrue(info.saves.isEmpty())
        assertFalse(viewModel.uiState.value.saved)
    }

    @Test
    fun `a contact without a name or phone is flagged inline`() {
        val viewModel = viewModel()
        viewModel.addContact()

        viewModel.save()

        val state = viewModel.uiState.value
        assertTrue(state.hasError(0, EmergencyContactField.NAME))
        assertTrue(state.hasError(0, EmergencyContactField.PHONE))
        assertFalse(state.hasError(0, EmergencyContactField.RELATIONSHIP))
        assertTrue(info.saves.isEmpty())
    }

    @Test
    fun `editing a flagged input clears its error`() {
        val viewModel = viewModel()
        viewModel.addContact()
        viewModel.save()

        viewModel.onContactPhoneChanged(0, "912345678")

        assertFalse(viewModel.uiState.value.hasError(0, EmergencyContactField.PHONE))
        assertTrue(viewModel.uiState.value.hasError(0, EmergencyContactField.NAME))
    }

    // --- Saving --------------------------------------------------------------

    @Test
    fun `save writes the form as a normalized record`() {
        val viewModel = viewModel()
        viewModel.onAllergyDraftChanged(" Penicillin ")
        viewModel.addAllergy()
        viewModel.onSnsNumberChanged(" 123456789 ")
        viewModel.onFamilyDoctorChanged("  Dra. Silva  ")
        viewModel.onHealthCentrePhoneChanged("  ")
        viewModel.addContact()
        viewModel.onContactNameChanged(0, " Ana ")
        viewModel.onContactRelationshipChanged(0, "  ")
        viewModel.onContactPhoneChanged(0, " 912345678 ")
        viewModel.onNotesChanged("  ")

        viewModel.save()

        val call = info.saves.single()
        assertEquals(testPatientId, call.patientId)
        assertEquals(listOf("Penicillin"), call.info.allergies)
        assertEquals("123456789", call.info.snsNumber)
        assertEquals("Dra. Silva", call.info.familyDoctor)
        assertEquals(null, call.info.healthCentrePhone)
        assertEquals(
            listOf(EmergencyContact(name = "Ana", relationship = null, phone = "912345678")),
            call.info.emergencyContacts,
        )
        assertEquals(null, call.info.notes)
        assertTrue(viewModel.uiState.value.saved)
    }

    @Test
    fun `an edit loads, changes and saves the existing record`() {
        info.setInfo(info = patientInfo(snsNumber = "123456789"))
        val viewModel = viewModel()

        viewModel.onNotesChanged("Call before 10:00.")
        viewModel.save()

        assertEquals("123456789", info.saves.single().info.snsNumber)
        assertEquals("Call before 10:00.", info.saves.single().info.notes)
    }

    @Test
    fun `stored lists round-trip unchanged when nothing is edited`() {
        info.setInfo(
            info = patientInfo(
                allergies = listOf("Penicillin", "Nuts"),
                conditions = listOf("Type 2 diabetes", "Asthma"),
            ),
        )

        viewModel().save()

        val saved = info.saves.single().info
        assertEquals(listOf("Penicillin", "Nuts"), saved.allergies)
        assertEquals(listOf("Type 2 diabetes", "Asthma"), saved.conditions)
    }

    @Test
    fun `a viewer cannot save`() {
        circles.roleFlow.value = MemberRole.VIEWER
        val viewModel = viewModel()

        assertFalse(viewModel.uiState.value.canWrite)
        viewModel.onSnsNumberChanged("123456789")
        viewModel.save()

        assertTrue(info.saves.isEmpty())
    }

    @Test
    fun `a refused save says so and keeps the form`() {
        info.saveResult = PatientInfoResult.Failure(PatientInfoError.NotAllowed)
        val viewModel = viewModel()
        viewModel.onSnsNumberChanged("123456789")

        viewModel.save()

        assertTrue(viewModel.uiState.value.notAllowed)
        assertFalse(viewModel.uiState.value.saved)
        assertEquals("123456789", viewModel.uiState.value.snsNumber)
    }

    @Test
    fun `a failed save says so without losing the input`() {
        info.saveResult = PatientInfoResult.Failure(PatientInfoError.Unknown)
        val viewModel = viewModel()
        viewModel.onNotesChanged("Morning only.")

        viewModel.save()

        assertTrue(viewModel.uiState.value.saveFailed)
        assertEquals("Morning only.", viewModel.uiState.value.notes)
    }

    // --- Saving from a snapshot ----------------------------------------------

    @Test
    fun `a save hands the repository the snapshot the form was loaded from`() {
        val loaded = patientInfo(allergies = listOf("Latex"), notes = "Old note.")
        info.setInfo(info = loaded)
        val viewModel = viewModel()

        viewModel.onNotesChanged("New note.")
        viewModel.save()

        val call = info.saves.single()
        assertTrue(call.isChange)
        assertEquals(loaded, call.loaded)
        assertEquals(listOf("Latex"), call.info.allergies)
        assertEquals("New note.", call.info.notes)
    }

    @Test
    fun `a record that syncs after the form opened does not refresh the snapshot`() {
        info.setInfo(info = patientInfo(allergies = listOf("Latex")))
        val viewModel = viewModel()

        // Another caregiver adds an allergy and it syncs while the form is open.
        info.setInfo(info = patientInfo(allergies = listOf("Latex", "Penicillin")))
        viewModel.onNotesChanged("Call before 10:00.")
        viewModel.save()

        val call = info.saves.single()
        assertEquals(listOf("Latex"), call.loaded?.allergies)
        assertEquals(listOf("Latex"), call.info.allergies)
        assertEquals("Call before 10:00.", call.info.notes)
    }

    @Test
    fun `a form that started empty saves against no snapshot`() {
        val viewModel = viewModel()

        viewModel.onNotesChanged("First note.")
        viewModel.save()

        val call = info.saves.single()
        assertTrue(call.isChange)
        assertEquals(null, call.loaded)
    }

    @Test
    fun `a retry after a failed save still diffs against the original snapshot`() {
        val loaded = patientInfo(allergies = listOf("Latex"))
        info.setInfo(info = loaded)
        info.saveResult = PatientInfoResult.Failure(PatientInfoError.Unknown)
        val viewModel = viewModel()
        viewModel.addAllergyEntry("Nuts")
        viewModel.save()

        info.setInfo(info = patientInfo(allergies = listOf("Latex", "Penicillin")))
        info.saveResult = PatientInfoResult.Success(Unit)
        viewModel.save()

        assertEquals(2, info.saves.size)
        assertEquals(loaded, info.saves.last().loaded)
        assertEquals(listOf("Latex", "Nuts"), info.saves.last().info.allergies)
    }
}
