package io.github.lgpy.familycare.core.data.integration

import com.powersync.db.crud.UpdateType
import io.github.lgpy.familycare.core.data.circle.PatientRpc
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
import kotlin.time.Instant
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
import org.junit.jupiter.api.Assertions.assertFalse
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
    private val rpc = FakePatientRpc()
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
            rpc = rpc,
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
        val viewerRepository = PowerSyncPatientRepository(
            viewer.database,
            viewerState,
            UnusedPatientRpc,
            newId = { PATIENT_ID },
        )

        val patient = viewerRepository.newPatient(CareCircleId(viewer.circleId), "Grandma")

        assertEquals(CoreResult.Failure(CoreError.NotAllowed), viewerRepository.save(patient))
        assertNull(viewer.database.getNextCrudTransaction())
        assertTrue(
            withTimeout(READ_TIMEOUT) { viewerRepository.patients.first() }.isEmpty(),
            "A refused save must not reach the local table",
        )

        viewer.close()
    }

    @Test
    fun `an owner archiving sets archived_at locally and queues a PATCH of only it`() = runBlocking {
        seedLivePatient()
        val repository = repository(now = { ARCHIVED_AT })

        assertEquals(
            CoreResult.Success(PatientId(PATIENT_ID)),
            repository.archive(PatientId(PATIENT_ID)),
        )

        val stored = withTimeout(READ_TIMEOUT) {
            repository.observe(PatientId(PATIENT_ID)).first { it?.archivedAt != null }
        }!!
        assertEquals(ARCHIVED_AT, stored.archivedAt)

        val entry = local.database.nextTransaction().crud.single()
        assertEquals(UpdateType.PATCH, entry.op)
        assertEquals(PATIENTS_TABLE, entry.table)
        assertEquals(
            JsonObject(mapOf("archived_at" to JsonPrimitive(ARCHIVED_AT.toString()))),
            entry.opData!!.jsonValues,
        )
    }

    @Test
    fun `unarchiving clears archived_at and queues an explicit null`() = runBlocking {
        local.seedPatient(
            patientId = PATIENT_ID,
            displayName = "Grandma",
            archivedAt = ARCHIVED_AT.toString(),
        )
        local.database.drainUploadQueue()
        val repository = repository()

        assertEquals(
            CoreResult.Success(PatientId(PATIENT_ID)),
            repository.unarchive(PatientId(PATIENT_ID)),
        )

        val stored = withTimeout(READ_TIMEOUT) {
            repository.observe(PatientId(PATIENT_ID)).first { it?.archivedAt == null }
        }!!
        assertFalse(stored.isArchived)

        val entry = local.database.nextTransaction().crud.single()
        assertEquals(UpdateType.PATCH, entry.op)
        assertEquals(
            JsonObject(mapOf("archived_at" to JsonNull)),
            entry.opData!!.jsonValues,
        )
    }

    @Test
    fun `a caregiver or viewer cannot archive and nothing is queued`() = runBlocking {
        for (role in listOf(LocalDatabase.CAREGIVER, LocalDatabase.VIEWER)) {
            val other = LocalDatabase(LocalDatabase.testDirectory("archive-$role"))
            try {
                other.seedCircle()
                other.seedMembership(userId = other.userId, role = role)
                other.seedPatient(patientId = PATIENT_ID, displayName = "Grandma")
                other.database.drainUploadQueue()
                val state = MutableStateFlow<AuthState>(
                    AuthState.SignedIn(other.userId, "$role@example.test"),
                )
                val otherRepository = PowerSyncPatientRepository(
                    database = other.database,
                    authState = state,
                    rpc = rpc,
                    newId = { PATIENT_ID },
                )

                assertEquals(
                    CoreResult.Failure(CoreError.NotAllowed),
                    otherRepository.archive(PatientId(PATIENT_ID)),
                )
                assertNull(other.database.getNextCrudTransaction())
                assertNull(
                    withTimeout(READ_TIMEOUT) {
                        otherRepository.observe(PatientId(PATIENT_ID)).first()
                    }?.archivedAt,
                )
            } finally {
                other.close()
            }
        }
    }

    @Test
    fun `only an owner erases, through the RPC, and the local row waits for the sync`() = runBlocking {
        seedLivePatient()
        val fakeRpc = FakePatientRpc()
        val repository = repository(rpc = fakeRpc)

        assertEquals(
            CoreResult.Success(PatientId(PATIENT_ID)),
            repository.erase(PatientId(PATIENT_ID)),
        )

        assertEquals(listOf(PATIENT_ID), fakeRpc.erased)
        // The repository never queues a direct local delete (the server refuses it).
        assertNull(local.database.getNextCrudTransaction())
        assertEquals(
            PatientId(PATIENT_ID),
            withTimeout(READ_TIMEOUT) { repository.observe(PatientId(PATIENT_ID)).first() }?.id,
        )
    }

    @Test
    fun `a caregiver cannot erase and the RPC is never called`() = runBlocking {
        val other = LocalDatabase(LocalDatabase.testDirectory("erase-caregiver"))
        try {
            other.seedCircle()
            other.seedMembership(userId = other.userId, role = LocalDatabase.CAREGIVER)
            other.seedPatient(patientId = PATIENT_ID, displayName = "Grandma")
            other.database.drainUploadQueue()
            val state = MutableStateFlow<AuthState>(
                AuthState.SignedIn(other.userId, "caregiver@example.test"),
            )
            val fakeRpc = FakePatientRpc()
            val otherRepository = PowerSyncPatientRepository(
                database = other.database,
                authState = state,
                rpc = fakeRpc,
                newId = { PATIENT_ID },
            )

            assertEquals(
                CoreResult.Failure(CoreError.NotAllowed),
                otherRepository.erase(PatientId(PATIENT_ID)),
            )
            assertTrue(fakeRpc.erased.isEmpty())
        } finally {
            other.close()
        }
    }

    @Test
    fun `a failed erase is mapped and leaves the patient in place`() = runBlocking {
        seedLivePatient()
        val repository = repository(rpc = FakePatientRpc(failure = RuntimeException("no network")))

        assertEquals(
            CoreResult.Failure(CoreError.Offline),
            repository.erase(PatientId(PATIENT_ID)),
        )
        assertEquals(
            PatientId(PATIENT_ID),
            withTimeout(READ_TIMEOUT) { repository.observe(PatientId(PATIENT_ID)).first() }?.id,
        )
    }

    @Test
    fun `the active list excludes archived patients and the archive list holds them`() = runBlocking {
        local.seedPatient(patientId = PATIENT_ID, displayName = "Grandma")
        local.seedPatient(
            patientId = ARCHIVED_ID,
            displayName = "Grandpa",
            archivedAt = ARCHIVED_AT.toString(),
        )
        local.database.drainUploadQueue()
        val repository = repository()

        val active = withTimeout(READ_TIMEOUT) {
            repository.patients.first { it.isNotEmpty() }
        }
        val archived = withTimeout(READ_TIMEOUT) {
            repository.archivedPatients.first { it.isNotEmpty() }
        }

        assertEquals(listOf(PATIENT_ID), active.map { it.id.value })
        assertEquals(listOf(ARCHIVED_ID), archived.map { it.id.value })
        assertTrue(archived.single().isArchived)
    }

    private fun repository(
        now: () -> Instant = { ARCHIVED_AT },
        rpc: PatientRpc = this.rpc,
    ): PowerSyncPatientRepository = PowerSyncPatientRepository(
        database = local.database,
        authState = authState,
        rpc = rpc,
        newId = { PATIENT_ID },
        now = now,
    )

    private suspend fun seedLivePatient() {
        local.seedPatient(patientId = PATIENT_ID, displayName = "Grandma")
        local.database.drainUploadQueue()
    }

    private companion object {
        const val PATIENT_ID = "33333333-3333-4333-8333-333333333333"
        const val ARCHIVED_ID = "44444444-4444-4444-8444-444444444444"
        val ARCHIVED_AT: Instant = Instant.parse("2026-11-19T10:00:00Z")
        val READ_TIMEOUT = 15.seconds
    }
}
