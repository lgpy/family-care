package io.github.lgpy.familycare.core.data.integration

import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.lgpy.familycare.core.data.circle.PowerSyncPatientRepository
import io.github.lgpy.familycare.core.data.sync.CORE_SCHEMA
import io.github.lgpy.familycare.core.data.sync.CoreSyncedTables
import io.github.lgpy.familycare.core.data.sync.DuplicateInsert
import io.github.lgpy.familycare.core.data.sync.PATIENTS_TABLE
import io.github.lgpy.familycare.core.data.sync.SupabaseCrudUploader
import io.github.lgpy.familycare.core.data.sync.SupabaseSyncConnector
import io.github.lgpy.familycare.core.data.sync.TableCatalog
import io.github.lgpy.familycare.core.data.sync.UploadFailure
import io.github.lgpy.familycare.core.data.sync.UploadRejections
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.CareCircleId
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.sync.RejectedChange
import io.github.lgpy.familycare.core.domain.sync.RejectedUpload
import io.github.lgpy.familycare.core.domain.sync.RejectionReason
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The upload half of sync, against the **local Supabase stack**: the app's own
 * [SupabaseCrudUploader] sends real PostgREST requests, signed in as real
 * users, and the rows are read back from the server.
 *
 * Two bugs this must catch:
 *
 * - a PostgREST upsert (`INSERT ... ON CONFLICT DO UPDATE`) on `core.patients`
 *   is refused with 42501, because Postgres also applies the SELECT policy
 *   (`core.can(id, 'read')`) to the not-yet-visible new row. A plain insert
 *   avoids that; the duplicate case is handled by an update.
 * - a viewer must be refused, and the refusal must be classified as permanent
 *   so the queue does not retry forever — the case [UploadRejections] records
 *   for the user.
 *
 * Skipped (JUnit assumption) unless `FAMILYCARE_SUPABASE_URL` and
 * `FAMILYCARE_SUPABASE_KEY` are set and the API answers, so the offline build
 * stays green. Never points at a remote project: the values come from
 * `supabase status -o env`, and CI only ever starts the local stack.
 *
 * The accounts and patients a run creates stay in the local development
 * database (there is no admin key here, by design); every email and patient id
 * is unique, so a previous run's rows cannot affect this one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalSupabaseUploadTest {

    private lateinit var stack: LocalStack
    private lateinit var owner: LocalStack.TestUser
    private lateinit var viewer: LocalStack.TestUser
    private lateinit var circleId: String

    /**
     * One owner and one viewer (through the real invitation flow) for the whole
     * class, so a run creates two accounts rather than one per test: the local
     * GoTrue has a signup rate limit and repeated test runs must not trip it.
     */
    @BeforeAll
    fun openStack() = runBlocking {
        stack = LocalStack.open()
        owner = stack.newUser()
        circleId = stack.createCircle(owner)
        viewer = stack.addViewer(owner, circleId)
    }

    @AfterAll
    fun closeStack() {
        if (this::stack.isInitialized) stack.close()
    }

    @Test
    fun `a new patient is inserted on the server`() = runBlocking {
        val patientId = newPatientId()

        uploader(owner).insert(CORE_SCHEMA, PATIENTS_TABLE, patientId, patientValues("Grandma"), DuplicateInsert.UPDATE_EXISTING)

        assertEquals("Grandma", stack.patientName(owner, patientId))
    }

    @Test
    fun `an edited patient is updated on the server`() = runBlocking {
        val patientId = newPatientId()
        uploader(owner).insert(CORE_SCHEMA, PATIENTS_TABLE, patientId, patientValues("Grandma"), DuplicateInsert.UPDATE_EXISTING)

        uploader(owner).update(CORE_SCHEMA, PATIENTS_TABLE, patientId, mapOf("display_name" to JsonPrimitive("Grandma Maria")))

        assertEquals("Grandma Maria", stack.patientName(owner, patientId))
    }

    @Test
    fun `re-sending an already inserted patient updates it instead of failing`() = runBlocking {
        val patientId = newPatientId()
        uploader(owner).insert(CORE_SCHEMA, PATIENTS_TABLE, patientId, patientValues("Grandma"), DuplicateInsert.UPDATE_EXISTING)

        // The precondition of the fallback: a second plain INSERT is a unique
        // violation, which is what makes the uploader switch to an UPDATE.
        val duplicate = refusal {
            owner.client.postgrest.from(CORE_SCHEMA, PATIENTS_TABLE).insert(
                body = JsonArray(listOf(patientRow(patientId, "Grandma"))),
            )
        }
        assertEquals(UNIQUE_VIOLATION, duplicate.code)

        // The retry a lost response (or a local INSERT OR REPLACE) produces.
        uploader(owner).insert(CORE_SCHEMA, PATIENTS_TABLE, patientId, patientValues("Grandma Maria"), DuplicateInsert.UPDATE_EXISTING)

        assertEquals("Grandma Maria", stack.patientName(owner, patientId))
    }

    @Test
    fun `a viewer's patient is refused as a permanent policy failure`() = runBlocking {
        val patientId = newPatientId()

        val error = refusal { uploader(viewer).insert(CORE_SCHEMA, PATIENTS_TABLE, patientId, patientValues("Nope"), DuplicateInsert.UPDATE_EXISTING) }

        assertEquals(RLS_DENIED, error.code)
        val failure = UploadFailure.of(error)
        assertTrue(failure.isPermanent, "A policy denial must not be retried forever")
        assertEquals(RejectionReason.NOT_ALLOWED, failure.rejectionReason())
        assertNull(stack.patientName(owner, patientId), "The refused row must not exist on the server")
    }

    @Test
    fun `a patient saved on the phone is uploaded through the connector`() = runBlocking {
        val local = localDatabase("upload-owner", userId = owner.userId, role = LocalDatabase.OWNER)
        try {
            val repository = repository(local, owner)
            val patient = repository.newPatient(CareCircleId(circleId), "Grandma")
            assertEquals(CoreResult.Success(patient.id), repository.save(patient))
            assertNotNull(local.database.getNextCrudTransaction(), "The save should be queued")

            val rejections = UploadRejections()
            connector(owner, rejections).uploadData(local.database)

            assertNull(rejections.state.value, "A permitted write records no refusal")
            assertNull(local.database.getNextCrudTransaction(), "The accepted transaction should be completed")
            assertEquals("Grandma", stack.patientName(owner, patient.id.value))
        } finally {
            local.close()
        }
    }

    @Test
    fun `a patient the server refuses is recorded as PATIENT and NOT_ALLOWED`() = runBlocking {
        // The phone still believes the viewer is a caregiver (a role change that
        // has not synced yet), so the local save succeeds and the queue sends
        // the row — and the server has the last word.
        val local = localDatabase("upload-viewer", userId = viewer.userId, role = LocalDatabase.CAREGIVER)
        try {
            val repository = repository(local, viewer)
            val patient = repository.newPatient(CareCircleId(circleId), "Nope")
            assertEquals(CoreResult.Success(patient.id), repository.save(patient))

            val rejections = UploadRejections()
            connector(viewer, rejections).uploadData(local.database)

            assertEquals(
                RejectedUpload(change = RejectedChange.PATIENT, reason = RejectionReason.NOT_ALLOWED),
                rejections.state.value,
            )
            assertNull(
                local.database.getNextCrudTransaction(),
                "A permanently refused transaction must be completed, not retried",
            )
            assertNull(stack.patientName(owner, patient.id.value), "The refused row must not exist on the server")
        } finally {
            local.close()
        }
    }

    // --- Helpers -------------------------------------------------------------

    /** The core's tables only: this test never touches a feature's schema. */
    private val tables = TableCatalog(listOf(CoreSyncedTables))

    private fun uploader(user: LocalStack.TestUser) =
        SupabaseCrudUploader(user.client.postgrest, tables)

    /**
     * The real connector, with sync downloads nowhere: the test only drives its
     * upload loop. Its endpoint is never contacted by `uploadData`.
     */
    private fun connector(user: LocalStack.TestUser, rejections: UploadRejections) =
        SupabaseSyncConnector(
            supabase = user.client,
            endpoint = "http://powersync.invalid",
            uploader = uploader(user),
            rejections = rejections,
            tables = tables,
        )

    private fun repository(local: LocalDatabase, user: LocalStack.TestUser) =
        PowerSyncPatientRepository(
            database = local.database,
            authState = MutableStateFlow<AuthState>(AuthState.SignedIn(user.userId, user.email)),
            rpc = UnusedPatientRpc,
            newId = ::newPatientId,
        )

    /** A real PowerSync database whose rows belong to [userId] in server [circleId]. */
    private suspend fun localDatabase(name: String, userId: String, role: String): LocalDatabase {
        val local = LocalDatabase(
            LocalDatabase.testDirectory(name),
            userId = userId,
            circleId = circleId,
        )
        local.seedCircle(circleId = circleId, circleName = "JVM family")
        local.seedMembership(userId = userId, role = role)
        // The seeded circle/membership are already synced: only what a test
        // writes itself should reach its assertions.
        local.database.drainUploadQueue()
        return local
    }

    /** The columns a phone PUT carries for a patient, in PostgREST's JSON types. */
    private fun patientValues(name: String): Map<String, JsonElement> = mapOf(
        "circle_id" to JsonPrimitive(circleId),
        "display_name" to JsonPrimitive(name),
        "reminder_policy" to JsonPrimitive("primary_with_escalation"),
        "escalation_delay_minutes" to JsonPrimitive(DEFAULT_ESCALATION_DELAY),
        "missed_window_minutes" to JsonPrimitive(DEFAULT_MISSED_WINDOW),
    )

    private fun patientRow(patientId: String, name: String) =
        JsonObject(
            patientValues(name) + ("id" to JsonPrimitive(patientId)),
        )

    /** Runs [block] and returns the PostgREST refusal it must produce. */
    private suspend fun refusal(block: suspend () -> Unit): PostgrestRestException {
        val error = try {
            block()
            null
        } catch (error: PostgrestRestException) {
            error
        }
        assertNotNull(error, "Expected PostgREST to refuse the write")
        return error!!
    }

    private fun newPatientId(): String = UUID.randomUUID().toString()

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
        const val RLS_DENIED = "42501"
        const val DEFAULT_ESCALATION_DELAY = 30
        const val DEFAULT_MISSED_WINDOW = 120
    }
}
