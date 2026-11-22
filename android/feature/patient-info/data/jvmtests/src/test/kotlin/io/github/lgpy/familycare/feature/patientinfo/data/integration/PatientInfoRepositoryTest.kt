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

    // --- Saving changes made from a possibly stale form ------------------------

    @Test
    fun `a notes-only edit writes only the notes and keeps another caregivers allergy`() = runBlocking {
        val repository = seededRow(allergies = listOf("Latex"))
        val loaded = readInfo(repository)
        // Caregiver B adds Penicillin and it syncs to this phone.
        syncFromServer(allergies = """["Latex","Penicillin"]""")

        val result = repository.saveChanges(
            PatientId(PATIENT_1),
            loaded = loaded,
            edited = loaded.copy(notes = "Call before 10:00."),
        )

        assertEquals(PatientInfoResult.Success(Unit), result)
        val entry = local.database.nextTransaction().crud.single()
        assertEquals(UpdateType.PATCH, entry.op)
        assertEquals(setOf("notes"), entry.values().keys)
        assertEquals(listOf("Latex", "Penicillin"), readInfo(repository).allergies)
        assertEquals("Call before 10:00.", readInfo(repository).notes)
    }

    @Test
    fun `an allergy added meanwhile is kept and the users addition is appended`() = runBlocking {
        val repository = seededRow(allergies = listOf("Latex"))
        val loaded = readInfo(repository)
        syncFromServer(allergies = """["Latex","Penicillin"]""")

        repository.saveChanges(
            PatientId(PATIENT_1),
            loaded = loaded,
            edited = loaded.copy(allergies = listOf("Latex", "Nuts")),
        )

        assertEquals(listOf("Latex", "Penicillin", "Nuts"), readInfo(repository).allergies)
        val values = local.database.nextTransaction().crud.single().values()
        assertEquals(setOf("allergies"), values.keys)
    }

    @Test
    fun `an edited entry is a removal plus an addition on the current row`() = runBlocking {
        val repository = seededRow(conditions = listOf("Asthma", "Diabetes"))
        val loaded = readInfo(repository)
        syncFromServer(conditions = """["Asthma","Diabetes","Gout"]""")

        repository.saveChanges(
            PatientId(PATIENT_1),
            loaded = loaded,
            edited = loaded.copy(conditions = listOf("Asthma", "Type 2 diabetes")),
        )

        assertEquals(
            listOf("Asthma", "Gout", "Type 2 diabetes"),
            readInfo(repository).conditions,
        )
    }

    @Test
    fun `a removal is applied to the current row and keeps what was added meanwhile`() = runBlocking {
        val repository = seededRow(allergies = listOf("Latex", "Penicillin"))
        val loaded = readInfo(repository)
        syncFromServer(allergies = """["Latex","Penicillin","Nuts"]""")

        repository.saveChanges(
            PatientId(PATIENT_1),
            loaded = loaded,
            edited = loaded.copy(allergies = listOf("Latex")),
        )

        assertEquals(listOf("Latex", "Nuts"), readInfo(repository).allergies)
    }

    @Test
    fun `an entry someone else already removed is not written again`() = runBlocking {
        val repository = seededRow(allergies = listOf("Latex", "Penicillin"))
        val loaded = readInfo(repository)
        // Caregiver B removes Penicillin.
        syncFromServer(allergies = """["Latex"]""")

        // Caregiver A removes it too, and edits the notes.
        val result = repository.saveChanges(
            PatientId(PATIENT_1),
            loaded = loaded,
            edited = loaded.copy(allergies = listOf("Latex"), notes = "Note."),
        )

        assertEquals(PatientInfoResult.Success(Unit), result)
        assertEquals(setOf("notes"), local.database.nextTransaction().crud.single().values().keys)
        assertEquals(listOf("Latex"), readInfo(repository).allergies)
    }

    @Test
    fun `a removal never removes an entry the user did not see`() = runBlocking {
        val repository = seededRow(allergies = listOf("Latex", "Penicillin"))
        val loaded = readInfo(repository)
        syncFromServer(allergies = """["Latex","Nuts"]""")

        repository.saveChanges(
            PatientId(PATIENT_1),
            loaded = loaded,
            edited = loaded.copy(allergies = listOf("Latex")),
        )

        assertEquals(listOf("Latex", "Nuts"), readInfo(repository).allergies)
    }

    @Test
    fun `contacts are merged the same way`() = runBlocking {
        val ana = EmergencyContact("Ana", "Daughter", "912 345 678")
        val bruno = EmergencyContact("Bruno", null, "(21) 000-0000")
        val carla = EmergencyContact("Carla", "Niece", "919 000 000")
        val repository = seededRow(contacts = listOf(ana))
        val loaded = readInfo(repository)
        syncFromServer(
            contacts = """[{"name": "Ana", "phone": "912 345 678", "relationship": "Daughter"}, """ +
                """{"name": "Carla", "phone": "919 000 000", "relationship": "Niece"}]""",
        )

        repository.saveChanges(
            PatientId(PATIENT_1),
            loaded = loaded,
            edited = loaded.copy(emergencyContacts = listOf(ana, bruno)),
        )

        assertEquals(listOf(ana, carla, bruno), readInfo(repository).emergencyContacts)
    }

    @Test
    fun `lists stored in Postgres spaced format are not rewritten by an unrelated edit`() = runBlocking {
        val repository = seededRow(
            allergies = listOf("Latex", "Penicillin"),
            conditions = listOf("Asthma"),
            contacts = listOf(EmergencyContact("Ana", "Daughter", "912 345 678")),
        )
        val loaded = readInfo(repository)
        // The server returns the same lists, re-serialised by jsonb.
        syncFromServer(
            allergies = """["Latex", "Penicillin"]""",
            conditions = """["Asthma"]""",
            contacts = """[{"name": "Ana", "phone": "912 345 678", "relationship": "Daughter"}]""",
        )

        repository.saveChanges(
            PatientId(PATIENT_1),
            loaded = loaded,
            edited = loaded.copy(familyDoctor = "Dra. Silva"),
        )

        val values = local.database.nextTransaction().crud.single().values()
        assertEquals(setOf("family_doctor"), values.keys)
    }

    @Test
    fun `an unchanged list is left out even when the stored text is spaced`() = runBlocking {
        val repository = seededRow(allergies = listOf("Latex"))
        val loaded = readInfo(repository)
        syncFromServer(allergies = """["Latex", "Penicillin"]""")

        repository.saveChanges(
            PatientId(PATIENT_1),
            loaded = loaded,
            edited = loaded.copy(allergies = listOf("Latex"), healthCentre = "Centro"),
        )

        assertEquals(setOf("health_centre"), local.database.nextTransaction().crud.single().values().keys)
    }

    @Test
    fun `a scalar the user changed wins over a concurrent change and untouched ones stay`() = runBlocking {
        val repository = seededRow(notes = "Original.", familyDoctor = "Dra. Silva")
        val loaded = readInfo(repository)
        local.database.execute(
            "UPDATE patient_info SET notes = ?, family_doctor = ? WHERE id = ?",
            listOf("B's note.", "Dr. Costa", PATIENT_1),
        )
        local.database.drainUploadQueue()

        repository.saveChanges(
            PatientId(PATIENT_1),
            loaded = loaded,
            edited = loaded.copy(notes = "A's note."),
        )

        assertEquals(setOf("notes"), local.database.nextTransaction().crud.single().values().keys)
        val stored = readInfo(repository)
        assertEquals("A's note.", stored.notes)
        assertEquals("Dr. Costa", stored.familyDoctor)
    }

    @Test
    fun `clearing a scalar the user changed writes null`() = runBlocking {
        val repository = seededRow(notes = "Original.")
        val loaded = readInfo(repository)

        repository.saveChanges(PatientId(PATIENT_1), loaded, loaded.copy(notes = null))

        assertNull(readInfo(repository).notes)
    }

    @Test
    fun `nothing changed queues nothing`() = runBlocking {
        val repository = seededRow(allergies = listOf("Latex"))
        val loaded = readInfo(repository)

        val result = repository.saveChanges(PatientId(PATIENT_1), loaded, loaded)

        assertEquals(PatientInfoResult.Success(Unit), result)
        assertNull(local.database.getNextCrudTransaction())
    }

    @Test
    fun `the merged list keeps the entry limit`() = runBlocking {
        val repository = seededRow(allergies = List(29) { "Allergy $it" })
        val loaded = readInfo(repository)
        syncFromServer(allergies = formatJson(List(29) { "Allergy $it" } + "Others"))

        val result = repository.saveChanges(
            PatientId(PATIENT_1),
            loaded = loaded,
            edited = loaded.copy(allergies = loaded.allergies + "Mine"),
        )

        val failure = assertInstanceOf(PatientInfoResult.Failure::class.java, result)
        assertEquals(
            PatientInfoError.Invalid(PatientInfoValidation(errors = setOf(PatientInfoField.ALLERGIES))),
            failure.error,
        )
        assertNull(local.database.getNextCrudTransaction())
        assertEquals(30, readInfo(repository).allergies.size)
    }

    @Test
    fun `a form that started empty merges onto a row that synced meanwhile`() = runBlocking {
        seedCircleAndPatient()
        val repository = repository()
        repository.save(PatientId(PATIENT_1), PatientInfo(allergies = listOf("Penicillin"), notes = "B's note."))
        local.database.drainUploadQueue()

        repository.saveChanges(
            PatientId(PATIENT_1),
            loaded = null,
            edited = PatientInfo(allergies = listOf("Latex")),
        )

        val stored = readInfo(repository)
        assertEquals(listOf("Penicillin", "Latex"), stored.allergies)
        assertEquals("B's note.", stored.notes)
        assertEquals(setOf("allergies"), local.database.nextTransaction().crud.single().values().keys)
    }

    @Test
    fun `with no row yet saveChanges inserts the whole record`() = runBlocking {
        seedCircleAndPatient()

        val result = repository().saveChanges(PatientId(PATIENT_1), loaded = null, edited = fullInfo())

        assertEquals(PatientInfoResult.Success(Unit), result)
        val entry = local.database.nextTransaction().crud.single()
        assertEquals(UpdateType.PUT, entry.op)
        assertEquals(JsonPrimitive("""["Penicillin","Nuts"]"""), entry.values()["allergies"])
    }

    @Test
    fun `a viewer cannot save changes`() = runBlocking {
        seedCircleAndPatient(role = PatientInfoLocalDatabase.VIEWER)

        val result = repository().saveChanges(PatientId(PATIENT_1), null, fullInfo())

        assertEquals(PatientInfoResult.Failure(PatientInfoError.NotAllowed), result)
        assertNull(local.database.getNextCrudTransaction())
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

    /** A row saved by this phone and synced, so the queue is empty. */
    private suspend fun seededRow(
        allergies: List<String> = emptyList(),
        conditions: List<String> = emptyList(),
        contacts: List<EmergencyContact> = emptyList(),
        notes: String? = null,
        familyDoctor: String? = null,
    ): PowerSyncPatientInfoRepository {
        seedCircleAndPatient()
        val repository = repository()
        repository.save(
            PatientId(PATIENT_1),
            PatientInfo(
                allergies = allergies,
                conditions = conditions,
                emergencyContacts = contacts,
                notes = notes,
                familyDoctor = familyDoctor,
            ),
        )
        local.database.drainUploadQueue()
        return repository
    }

    /** What the form is filled from, and what is stored now. */
    private suspend fun readInfo(repository: PowerSyncPatientInfoRepository): PatientInfo =
        checkNotNull(
            withTimeout(TIMEOUT_MS) { repository.observe(PatientId(PATIENT_1)).first { it != null } },
        )

    /**
     * Another caregiver's change arriving through sync: the list columns hold
     * the JSON text Postgres sends, spaces included, and the queue stays empty.
     */
    private suspend fun syncFromServer(
        allergies: String? = null,
        conditions: String? = null,
        contacts: String? = null,
    ) {
        allergies?.let {
            local.database.execute("UPDATE patient_info SET allergies = ? WHERE id = ?", listOf(it, PATIENT_1))
        }
        conditions?.let {
            local.database.execute("UPDATE patient_info SET conditions = ? WHERE id = ?", listOf(it, PATIENT_1))
        }
        contacts?.let {
            local.database.execute(
                "UPDATE patient_info SET emergency_contacts = ? WHERE id = ?",
                listOf(it, PATIENT_1),
            )
        }
        local.database.drainUploadQueue()
    }

    private fun formatJson(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]", separator = ", ") { "\"$it\"" }

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
