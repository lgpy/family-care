package io.github.lgpy.familycare.feature.patientinfo.data.integration

import com.powersync.db.crud.CrudEntry
import com.powersync.db.crud.UpdateType
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.feature.patientinfo.data.PatientInfoError
import io.github.lgpy.familycare.feature.patientinfo.data.PatientInfoResult
import io.github.lgpy.familycare.feature.patientinfo.data.PowerSyncPatientInfoRepository
import io.github.lgpy.familycare.feature.patientinfo.domain.EmergencyContact
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfo
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfoField
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfoValidation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The patient-info repository against a **real PowerSync database** built from
 * the merged schema (core + info).
 *
 * What this catches that a unit test cannot: the module writes into a PowerSync
 * view through its `INSTEAD OF` trigger, so a column the view does not have or
 * an upsert the view refuses fails here, and a save is one queued transaction
 * whose entries these tests read back.
 *
 * Every test seeds the circle, the signed-in membership and a patient, then
 * drains the queue so only the action under test is asserted.
 */
class PatientInfoRepositoryTest {

    private val local = PatientInfoLocalDatabase(
        PatientInfoLocalDatabase.testDirectory("patient-info-repo"),
    )

    @AfterEach
    fun close() {
        local.close()
    }

    // --- Reading -------------------------------------------------------------

    @Test
    fun `a patient with no row yet observes null`() = runBlocking {
        seedCircleAndPatient()

        val info = withTimeout(TIMEOUT_MS) {
            repository().observe(PatientId(PATIENT_1)).first { true }
        }

        assertNull(info, "No row means none recorded, not an empty record")
    }

    // --- Creating ------------------------------------------------------------

    @Test
    fun `the first save inserts the row with id equal to the patient id`() = runBlocking {
        seedCircleAndPatient()

        val result = repository().save(PatientId(PATIENT_1), fullInfo())

        assertEquals(PatientInfoResult.Success(Unit), result)
        val transaction = local.database.nextTransaction()
        val entry = transaction.crud.single()
        assertEquals(UpdateType.PUT to "patient_info" to PATIENT_1, entry.op to entry.table to entry.id)

        val values = entry.values()
        assertEquals(JsonPrimitive(PATIENT_1), values["patient_id"])
        // The row's id is the patient's, so two offline creates converge (D29).
        assertNull(values["id"], "PowerSync carries the id outside opData")
        assertEquals(JsonPrimitive("""["Penicillin","Nuts"]"""), values["allergies"])
        assertEquals(JsonPrimitive("""["Type 2 diabetes"]"""), values["conditions"])
        assertEquals(JsonPrimitive("123456789"), values["sns_number"])
        assertEquals(JsonPrimitive("Dra. Silva"), values["family_doctor"])
        assertEquals(JsonPrimitive("+351 21 000 0000"), values["health_centre_phone"])
        assertEquals(
            JsonPrimitive(
                """[{"name":"Ana","phone":"912 345 678","relationship":"Daughter"},""" +
                    """{"name":"Bruno","phone":"(21) 000-0000"}]""",
            ),
            values["emergency_contacts"],
        )
        assertEquals(JsonPrimitive("Prefers morning appointments."), values["notes"])
        // The server stamps these; the app never sends them.
        assertNull(values["created_at"])
        assertNull(values["updated_at"])
        assertNull(values["updated_by"])
    }

    @Test
    fun `a saved row reads back as the domain record`() = runBlocking {
        seedCircleAndPatient()
        val repository = repository()

        repository.save(PatientId(PATIENT_1), fullInfo())
        local.database.drainUploadQueue()

        val info = withTimeout(TIMEOUT_MS) {
            repository.observe(PatientId(PATIENT_1)).first { it != null }
        }

        assertNotNull(info)
        assertEquals(listOf("Penicillin", "Nuts"), info!!.allergies)
        assertEquals(listOf("Type 2 diabetes"), info.conditions)
        assertEquals("123456789", info.snsNumber)
        assertEquals(
            listOf(
                EmergencyContact(name = "Ana", relationship = "Daughter", phone = "912 345 678"),
                EmergencyContact(name = "Bruno", relationship = null, phone = "(21) 000-0000"),
            ),
            info.emergencyContacts,
        )
    }

    // --- Updating ------------------------------------------------------------

    @Test
    fun `a second save updates the row without moving it`() = runBlocking {
        seedCircleAndPatient()
        val repository = repository()
        repository.save(PatientId(PATIENT_1), fullInfo())
        local.database.drainUploadQueue()

        val result = repository.save(PatientId(PATIENT_1), fullInfo().copy(notes = "Allergic to nuts."))

        assertEquals(PatientInfoResult.Success(Unit), result)
        val entry = local.database.nextTransaction().crud.single()
        assertEquals(UpdateType.PATCH to "patient_info" to PATIENT_1, entry.op to entry.table to entry.id)
        val values = entry.values()
        assertEquals(JsonPrimitive("Allergic to nuts."), values["notes"])
        // The row belongs to its patient: the server trigger refuses a move.
        assertNull(values["patient_id"])

        local.database.drainUploadQueue()
        val info = withTimeout(TIMEOUT_MS) {
            repository.observe(PatientId(PATIENT_1)).first { it?.notes == "Allergic to nuts." }
        }
        assertEquals("Allergic to nuts.", info?.notes)
    }

    // --- Refusals ------------------------------------------------------------

    @Test
    fun `a viewer is refused and nothing is queued`() = runBlocking {
        seedCircleAndPatient(role = PatientInfoLocalDatabase.VIEWER)

        val result = repository().save(PatientId(PATIENT_1), fullInfo())

        assertEquals(PatientInfoResult.Failure(PatientInfoError.NotAllowed), result)
        assertNull(local.database.getNextCrudTransaction(), "A refused write must not be queued")
    }

    @Test
    fun `a validation error is returned before anything is written`() = runBlocking {
        seedCircleAndPatient()
        val tooManyContacts = fullInfo().copy(
            emergencyContacts = List(6) { EmergencyContact("Contact $it", phone = "912345678") },
        )

        val result = repository().save(PatientId(PATIENT_1), tooManyContacts)

        val failure = assertInstanceOf(PatientInfoResult.Failure::class.java, result)
        assertEquals(
            PatientInfoError.Invalid(PatientInfoValidation(errors = setOf(PatientInfoField.EMERGENCY_CONTACTS))),
            failure.error,
        )
        assertNull(local.database.getNextCrudTransaction())
    }

    @Test
    fun `an invalid SNS number is refused`() = runBlocking {
        seedCircleAndPatient()

        val result = repository().save(PatientId(PATIENT_1), fullInfo().copy(snsNumber = "12345"))

        val failure = assertInstanceOf(PatientInfoResult.Failure::class.java, result)
        assertEquals(
            PatientInfoError.Invalid(PatientInfoValidation(errors = setOf(PatientInfoField.SNS_NUMBER))),
            failure.error,
        )
        assertNull(local.database.getNextCrudTransaction())
    }

    @Test
    fun `a patient of another circle is refused and not observed`() = runBlocking {
        seedCircleAndPatient()
        val otherCircle = "99999999-9999-4999-8999-999999999999"
        local.seedCircle(circleId = otherCircle, circleName = "Other family")
        local.seedPatient(PATIENT_2, circleId = otherCircle)
        local.database.drainUploadQueue()
        val repository = repository()

        assertEquals(
            PatientInfoResult.Failure(PatientInfoError.NotAllowed),
            repository.save(PatientId(PATIENT_2), fullInfo()),
        )
        assertNull(local.database.getNextCrudTransaction())

        assertNull(
            withTimeout(TIMEOUT_MS) { repository.observe(PatientId(PATIENT_2)).first { true } },
            "Another circle's patient must not be readable",
        )
    }

    @Test
    fun `a blank optional text is stored as null`() = runBlocking {
        seedCircleAndPatient()

        repository().save(
            PatientId(PATIENT_1),
            PatientInfo(
                snsNumber = "   ",
                familyDoctor = "  ",
                healthCentrePhone = " ",
                notes = " ",
            ),
        )

        val values = local.database.nextTransaction().crud.single().values()
        listOf("sns_number", "family_doctor", "health_centre_phone", "notes").forEach { column ->
            // PowerSync omits a null column from a PUT's opData; either way the
            // server receives no value for it.
            assertNull(values[column], "$column is null when blank")
        }
    }

    // --- Helpers -------------------------------------------------------------

    private suspend fun seedCircleAndPatient(role: String = PatientInfoLocalDatabase.OWNER) {
        local.seedOwner(role = role)
        local.seedPatient(PATIENT_1)
        local.database.drainUploadQueue()
    }

    private fun repository() = PowerSyncPatientInfoRepository(
        database = local.database,
        authState = local.authState,
    )

    private fun fullInfo() = PatientInfo(
        allergies = listOf("Penicillin", "Nuts"),
        conditions = listOf("Type 2 diabetes"),
        snsNumber = "123456789",
        familyDoctor = "Dra. Silva",
        healthCentre = "Centro de Saúde de Belém",
        healthCentrePhone = "+351 21 000 0000",
        emergencyContacts = listOf(
            EmergencyContact(name = "Ana", relationship = "Daughter", phone = "912 345 678"),
            EmergencyContact(name = "Bruno", phone = "(21) 000-0000"),
        ),
        notes = "Prefers morning appointments.",
    )

    private fun CrudEntry.values(): JsonObject = opData?.jsonValues ?: JsonObject(emptyMap())

    private companion object {
        const val PATIENT_1 = "33333333-3333-4333-8333-333333333333"
        const val PATIENT_2 = "33333333-3333-4333-8333-333333333334"

        const val TIMEOUT_MS = 15_000L
    }
}
