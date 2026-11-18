package io.github.lgpy.familycare.core.data.integration

import com.powersync.db.crud.UpdateType
import io.github.lgpy.familycare.core.data.circle.PowerSyncPatientRepository
import io.github.lgpy.familycare.core.data.sync.PATIENTS_TABLE
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.CareCircleId
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [PowerSyncPatientRepository] against a real PowerSync database.
 *
 * These are the tests that fail on the M2 bug this module was added for: a
 * patient save was written as `INSERT ... ON CONFLICT (id) DO UPDATE`, which
 * PowerSync tables (SQLite views, not tables) reject. The CREATE path is
 * therefore exercised for real here, not through a fake SQL executor.
 */
class LocalPatientRepositoryTest {

    private lateinit var directory: Path

    private lateinit var local: LocalDatabase
    private lateinit var repository: PowerSyncPatientRepository
    private val authState = MutableStateFlow<AuthState>(AuthState.SignedOut)

    @BeforeEach
    fun setUp() = runBlocking {
        directory = LocalDatabase.testDirectory("local")
        local = LocalDatabase(directory)
        local.seedOwner()
        // Seeded rows are already "synced": their queued PUTs are not part of
        // what each test asserts.
        local.database.drainUploadQueue()
        authState.value = AuthState.SignedIn(local.userId, "owner@example.test")
        repository = PowerSyncPatientRepository(
            database = local.database,
            authState = authState,
            newId = { PATIENT_ID },
        )
    }

    @AfterEach
    fun tearDown() {
    }

    @Test
    fun `creating a patient stores it locally and queues one PUT with its columns`() = runBlocking {
        val patient = repository.newPatient(
            circleId = CareCircleId(local.circleId),
            displayName = "  Grandma  ",
            agreementNote = "   ",
        )
        assertEquals(PatientId(PATIENT_ID), patient.id)

        assertEquals(CoreResult.Success(patient.id), repository.save(patient))

        // Read back through the repository's own query: an INSERT into the
        // PowerSync view, not an upsert of an ordinary table.
        val stored = withTimeout(READ_TIMEOUT) {
            repository.patients.first { patients -> patients.any { it.id == patient.id } }
        }.single()
        assertEquals("Grandma", stored.displayName)
        assertEquals(local.circleId, stored.circleId.value)
        assertEquals(ReminderPolicy.PRIMARY_WITH_ESCALATION, stored.reminderPolicy)
        assertEquals(30, stored.escalationDelayMinutes)
        assertEquals(120, stored.missedWindowMinutes)
        assertNull(stored.agreementNote)
        assertNull(stored.archivedAt)

        val transaction = local.database.nextTransaction()
        val entry = transaction.crud.single()
        assertEquals(UpdateType.PUT, entry.op)
        assertEquals(PATIENTS_TABLE, entry.table)
        assertEquals(PATIENT_ID, entry.id)

        // A PUT carries every non-null column of the row — the id is the
        // entry's own field, not part of the data — and nothing that was never
        // written (nulls are absent, not `null`).
        val data = entry.opData!!.jsonValues
        assertEquals(
            setOf(
                "circle_id",
                "display_name",
                "reminder_policy",
                "escalation_delay_minutes",
                "missed_window_minutes",
            ),
            data.keys,
        )
        assertEquals(local.circleId, data["circle_id"]!!.jsonPrimitive.content)
        assertEquals("Grandma", data["display_name"]!!.jsonPrimitive.content)
        assertEquals("primary_with_escalation", data["reminder_policy"]!!.jsonPrimitive.content)
        assertEquals(30, data["escalation_delay_minutes"]!!.jsonPrimitive.int)
        assertEquals(120, data["missed_window_minutes"]!!.jsonPrimitive.int)

        transaction.complete(null)
        assertNull(local.database.getNextCrudTransaction(), "Only one transaction was expected")

    }

    @Test
    fun `editing the same patient queues a PATCH with only the changed column`() = runBlocking {
        val patient = repository.newPatient(
            circleId = CareCircleId(local.circleId),
            displayName = "Grandma",
            agreementNote = "Likes tea",
        )
        assertEquals(CoreResult.Success(patient.id), repository.save(patient))
        local.database.nextTransaction().complete(null)

        // The same id, edited: an UPDATE of the view (not a second INSERT).
        val edited = patient.copy(displayName = "Grandma Maria")
        assertEquals(CoreResult.Success(edited.id), repository.save(edited))

        val stored = withTimeout(READ_TIMEOUT) {
            repository.observe(patient.id).first { it?.displayName == "Grandma Maria" }
        }!!
        assertEquals(patient.id, stored.id)
        assertEquals("Likes tea", stored.agreementNote)

        val transaction = local.database.nextTransaction()
        val entry = transaction.crud.single()
        assertEquals(UpdateType.PATCH, entry.op)
        assertEquals(PATIENTS_TABLE, entry.table)
        assertEquals(PATIENT_ID, entry.id)
        // Only what actually changed: the repository writes every editable
        // column, and PowerSync records the difference.
        assertEquals(
            JsonObject(mapOf("display_name" to JsonPrimitive("Grandma Maria"))),
            entry.opData!!.jsonValues,
        )
        transaction.complete(null)
        assertNull(local.database.getNextCrudTransaction())

    }

    @Test
    fun `clearing the agreement note reaches the queue as an explicit null`() = runBlocking {
        val patient = repository.newPatient(
            circleId = CareCircleId(local.circleId),
            displayName = "Grandma",
            agreementNote = "Likes tea",
        )
        repository.save(patient)
        local.database.nextTransaction().complete(null)

        repository.save(patient.copy(agreementNote = "   "))

        val entry = local.database.nextTransaction().crud.single()
        assertEquals(UpdateType.PATCH, entry.op)
        assertEquals(
            JsonObject(mapOf("agreement_note" to JsonNull)),
            entry.opData!!.jsonValues,
        )

    }

    @Test
    fun `a viewer cannot save a patient and nothing is queued`() = runBlocking {
        // The role comes from the local memberships table, the same row the
        // server's policy reads.
        val viewerDirectory = LocalDatabase.testDirectory("viewer")
        val viewer = LocalDatabase(viewerDirectory)
        viewer.seedCircle()
        viewer.seedMembership(userId = viewer.userId, role = LocalDatabase.VIEWER)
        viewer.database.drainUploadQueue()
        val viewerState = MutableStateFlow<AuthState>(AuthState.SignedIn(viewer.userId, "viewer@example.test"))
        val viewerRepository = PowerSyncPatientRepository(viewer.database, viewerState) { PATIENT_ID }

        val patient = viewerRepository.newPatient(CareCircleId(viewer.circleId), "Grandma")

        assertEquals(CoreResult.Failure(CoreError.NotAllowed), viewerRepository.save(patient))
        assertNull(viewer.database.getNextCrudTransaction())
        assertTrue(
            withTimeout(READ_TIMEOUT) { viewerRepository.patients.first() }.isEmpty(),
            "A refused save must not reach the local table",
        )

        viewer.close()
    }

    private companion object {
        const val PATIENT_ID = "33333333-3333-4333-8333-333333333333"
        val READ_TIMEOUT = 15.seconds
    }
}
