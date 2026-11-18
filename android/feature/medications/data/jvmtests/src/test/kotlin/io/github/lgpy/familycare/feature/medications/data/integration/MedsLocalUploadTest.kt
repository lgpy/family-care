package io.github.lgpy.familycare.feature.medications.data.integration

import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.lgpy.familycare.core.data.sync.CORE_SCHEMA
import io.github.lgpy.familycare.core.data.sync.CoreSyncedTables
import io.github.lgpy.familycare.core.data.sync.DuplicateInsert
import io.github.lgpy.familycare.core.data.sync.PATIENTS_TABLE
import io.github.lgpy.familycare.core.data.sync.SupabaseCrudUploader
import io.github.lgpy.familycare.core.data.sync.TableCatalog
import io.github.lgpy.familycare.core.data.sync.UploadFailure
import io.github.lgpy.familycare.core.data.sync.UploadRejections
import io.github.lgpy.familycare.core.data.sync.crudWrites
import io.github.lgpy.familycare.core.data.sync.uploadTransaction
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.sync.RejectionReason
import io.github.lgpy.familycare.feature.medications.data.DOSE_LOG_TABLE
import io.github.lgpy.familycare.feature.medications.data.MEDICATIONS_TABLE
import io.github.lgpy.familycare.feature.medications.data.MEDS_SCHEMA
import io.github.lgpy.familycare.feature.medications.data.MedicationsSyncedTables
import io.github.lgpy.familycare.feature.medications.data.PowerSyncMedicationRepository
import io.github.lgpy.familycare.feature.medications.data.REGIMENS_TABLE
import io.github.lgpy.familycare.feature.medications.data.STOCK_MOVEMENTS_TABLE
import io.github.lgpy.familycare.feature.medications.domain.DoseTime
import io.github.lgpy.familycare.feature.medications.domain.Medication
import io.github.lgpy.familycare.feature.medications.domain.MedicationForm
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.Regimen
import io.github.lgpy.familycare.feature.medications.domain.RegimenId
import io.github.lgpy.familycare.feature.medications.domain.ScheduleType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The `meds` tables' upload half, against the **local Supabase stack**: the
 * app's own [SupabaseCrudUploader] sends real PostgREST requests for the
 * module's tables (`meds.medications`, `meds.regimens`, `meds.dose_log`), signed
 * in as real users, and the rows are read back from the server.
 *
 * What this catches that a unit test cannot — the rules in
 * `backend/supabase/migrations/20261119120000_meds.sql`:
 *
 * - a regimen is versioned: only `end_date` may change, and the trigger refuses
 *   anything else with `23514`, which [UploadFailure] must classify as permanent;
 * - `dose_log` is append-only: it has no UPDATE grant, a trigger refuses every
 *   UPDATE, and a duplicate INSERT is "already applied" rather than an UPDATE —
 *   a real duplicate INSERT is the only way to prove that;
 * - a viewer may read the circle but not write its patients' data, and the
 *   `42501` that refuses the row must be permanent so the queue does not retry
 *   forever.
 *
 * Skipped (JUnit assumption) unless `FAMILYCARE_SUPABASE_URL` and
 * `FAMILYCARE_SUPABASE_KEY` are set and the API answers, so the offline build
 * stays green. Never points at a remote project: the values come from
 * `supabase status -o env`, and CI only ever starts the local stack.
 *
 * Every patient, medication, regimen and entry id is unique, so a previous run's
 * rows cannot affect this one (the local database keeps them; there is no admin
 * key here, by design).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MedsLocalUploadTest {

    private lateinit var stack: MedsLocalStack
    private lateinit var owner: MedsLocalStack.TestUser
    private lateinit var viewer: MedsLocalStack.TestUser
    private lateinit var circleId: String

    /**
     * One owner and one viewer (through the real invitation flow) for the whole
     * class, so a run creates two accounts rather than one per test: the local
     * GoTrue has a signup rate limit and repeated test runs must not trip it.
     */
    @BeforeAll
    fun openStack() = runBlocking {
        stack = MedsLocalStack.open()
        owner = stack.newUser()
        circleId = stack.createCircle(owner)
        viewer = stack.addViewer(owner, circleId)
    }

    @AfterAll
    fun closeStack() {
        if (this::stack.isInitialized) stack.close()
    }

    // --- (a) a patient, a medication and its regimen -------------------------

    @Test
    fun `a patient then a medication and its regimen are inserted on the server`() = runBlocking {
        val patientId = newPatient()

        val medicationId = newId()
        uploader(owner).insert(
            MEDS_SCHEMA,
            MEDICATIONS_TABLE,
            medicationId,
            medicationValues(patientId),
            duplicatePolicy(MEDICATIONS_TABLE),
        )

        val regimenId = newId()
        uploader(owner).insert(
            MEDS_SCHEMA,
            REGIMENS_TABLE,
            regimenId,
            regimenValues(medicationId),
            duplicatePolicy(REGIMENS_TABLE),
        )

        // Read back as the user, not from the upload response (there is none).
        assertEquals("Grandma", stack.patientName(owner, patientId))

        val medication = stack.row(owner, MEDS_SCHEMA, MEDICATIONS_TABLE, medicationId)
        assertNotNull(medication, "The medication must exist on the server")
        assertEquals("Metformin", medication!!.text("name"))
        assertEquals("tablet", medication.text("form"))
        assertEquals("7", medication.text("low_stock_threshold_days"))
        assertEquals(owner.userId, medication.text("created_by"), "The server stamps the creator")

        val regimen = stack.row(owner, MEDS_SCHEMA, REGIMENS_TABLE, regimenId)
        assertNotNull(regimen, "The regimen must exist on the server")
        assertEquals("2026-11-01", regimen!!.text("start_date"))
        assertEquals(
            listOf("08:00" to "0.5"),
            regimen.times(),
            "times is a jsonb array, not the JSON text the phone stores",
        )
    }

    // --- (b) the one editable regimen column --------------------------------

    @Test
    fun `a regimen end date update lands`() = runBlocking {
        val fixture = fixture()

        uploader(owner).update(
            MEDS_SCHEMA,
            REGIMENS_TABLE,
            fixture.regimenId,
            mapOf("end_date" to JsonPrimitive(END_DATE)),
        )

        assertEquals(
            END_DATE,
            stack.row(owner, MEDS_SCHEMA, REGIMENS_TABLE, fixture.regimenId)?.text("end_date"),
        )
    }

    // --- (c) versioning: only end_date may change ---------------------------

    @Test
    fun `an update that changes a regimen's times is refused as a permanent failure`() = runBlocking {
        val fixture = fixture()
        val before = stack.row(owner, MEDS_SCHEMA, REGIMENS_TABLE, fixture.regimenId)?.times()

        // The upload policy sends an UPDATE of `end_date` alone, so the client
        // never carries a change the trigger would refuse.
        uploader(owner).update(
            MEDS_SCHEMA,
            REGIMENS_TABLE,
            fixture.regimenId,
            mapOf("times" to JsonPrimitive(CHANGED_TIMES)),
        )
        assertEquals(
            before,
            stack.row(owner, MEDS_SCHEMA, REGIMENS_TABLE, fixture.regimenId)?.times(),
            "A local times change must never reach the server",
        )

        // The rule itself, as the server states it (a stray client that does
        // send the column, e.g. an older app version).
        val error = refusal {
            owner.client.postgrest.from(MEDS_SCHEMA, REGIMENS_TABLE).update(
                body = JsonObject(mapOf("times" to Json.parseToJsonElement(CHANGED_TIMES))),
            ) {
                filter { eq("id", fixture.regimenId) }
            }
        }

        assertEquals(CHECK_VIOLATION, error.code)
        val failure = UploadFailure.of(error)
        assertTrue(failure.isPermanent, "A versioned-regimen refusal must not be retried forever")
        assertEquals(RejectionReason.INVALID_DATA, failure.rejectionReason())
    }

    // --- (d) the append-only dose log ---------------------------------------

    @Test
    fun `a dose log entry is inserted on the server`() = runBlocking {
        val fixture = fixture()
        val entryId = newId()

        uploader(owner).insert(
            MEDS_SCHEMA,
            DOSE_LOG_TABLE,
            entryId,
            doseLogValues(fixture, confirmedBy = owner.userId, note = "first"),
            duplicatePolicy(DOSE_LOG_TABLE),
        )

        val entry = stack.row(owner, MEDS_SCHEMA, DOSE_LOG_TABLE, entryId)
        assertNotNull(entry, "The dose-log entry must exist on the server")
        assertEquals("taken", entry!!.text("outcome"))
        assertTrue(
            entry.text("scheduled_local")!!.startsWith(SCHEDULED_LOCAL.replace(' ', 'T')),
            "scheduled_local is the slot's wall-clock time: ${entry.text("scheduled_local")}",
        )
        assertEquals(0, BigDecimal(entry.text("actual_quantity")).compareTo(BigDecimal("0.5")))
        assertEquals(owner.userId, entry.text("confirmed_by"))
        assertEquals(JsonNull, entry.get("skip_reason"), "A taken dose carries no skip reason")
    }

    // --- (e) a retried insert is already applied ----------------------------

    @Test
    fun `re-sending the same dose log insert succeeds without an update`() = runBlocking {
        val fixture = fixture()
        val entryId = newId()

        uploader(owner).insert(
            MEDS_SCHEMA,
            DOSE_LOG_TABLE,
            entryId,
            doseLogValues(fixture, confirmedBy = owner.userId, note = "first"),
            duplicatePolicy(DOSE_LOG_TABLE),
        )

        // The same id again (a retry whose first response was lost), carrying
        // different values: the client must treat the unique violation as done.
        // It must not fall back to an UPDATE — see the rule below — and must
        // leave the row exactly as the server first received it.
        uploader(owner).insert(
            MEDS_SCHEMA,
            DOSE_LOG_TABLE,
            entryId,
            doseLogValues(fixture, confirmedBy = owner.userId, note = "second"),
            duplicatePolicy(DOSE_LOG_TABLE),
        )

        val entry = stack.row(owner, MEDS_SCHEMA, DOSE_LOG_TABLE, entryId)
        assertEquals("first", entry?.text("note"), "An applied duplicate must leave the row untouched")

        // Why the fallback is wrong here, as the server states it: there is no
        // UPDATE grant on `dose_log` at all, so a retried update would be
        // refused and reported as a loss of a write that actually succeeded.
        val refusal = refusal {
            owner.client.postgrest.from(MEDS_SCHEMA, DOSE_LOG_TABLE)
                .update(body = JsonObject(mapOf("note" to JsonPrimitive("second")))) {
                    filter { eq("id", entryId) }
                }
        }
        assertEquals(RLS_DENIED, refusal.code)
        assertEquals(
            "first",
            stack.row(owner, MEDS_SCHEMA, DOSE_LOG_TABLE, entryId)?.text("note"),
            "The refused update must not change the entry",
        )
    }

    // --- (f) the append-only stock ledger (D28) -----------------------------

    @Test
    fun `a stock movement is inserted on the server, with the server's creator`() = runBlocking {
        val fixture = fixture()
        val movementId = newId()

        uploader(owner).insert(
            MEDS_SCHEMA,
            STOCK_MOVEMENTS_TABLE,
            movementId,
            stockMovementValues(fixture.medicationId, "count", "30", reason = "opening"),
            duplicatePolicy(STOCK_MOVEMENTS_TABLE),
        )

        val movement = stack.row(owner, MEDS_SCHEMA, STOCK_MOVEMENTS_TABLE, movementId)
        assertNotNull(movement, "The stock movement must exist on the server")
        assertEquals("count", movement!!.text("kind"))
        assertEquals(0, BigDecimal(movement.text("quantity")).compareTo(BigDecimal("30")))
        assertEquals("opening", movement.text("reason"))
        assertTrue(
            movement.text("occurred_at")!!.startsWith(OCCURRED_AT.take(19).replace(' ', 'T')),
            "occurred_at is an instant: ${movement.text("occurred_at")}",
        )
        assertEquals(owner.userId, movement.text("created_by"), "The server stamps the creator")
    }

    @Test
    fun `re-sending the same stock movement succeeds without an update`() = runBlocking {
        val fixture = fixture()
        val movementId = newId()

        uploader(owner).insert(
            MEDS_SCHEMA,
            STOCK_MOVEMENTS_TABLE,
            movementId,
            stockMovementValues(fixture.medicationId, "refill", "20", reason = "first"),
            duplicatePolicy(STOCK_MOVEMENTS_TABLE),
        )
        uploader(owner).insert(
            MEDS_SCHEMA,
            STOCK_MOVEMENTS_TABLE,
            movementId,
            stockMovementValues(fixture.medicationId, "refill", "20", reason = "second"),
            duplicatePolicy(STOCK_MOVEMENTS_TABLE),
        )

        assertEquals(
            "first",
            stack.row(owner, MEDS_SCHEMA, STOCK_MOVEMENTS_TABLE, movementId)?.text("reason"),
            "An applied duplicate must leave the row untouched",
        )

        // The ledger is append-only: a stray UPDATE is refused by the server.
        val refused = refusal {
            owner.client.postgrest.from(MEDS_SCHEMA, STOCK_MOVEMENTS_TABLE)
                .update(body = JsonObject(mapOf("quantity" to JsonPrimitive(1)))) {
                    filter { eq("id", movementId) }
                }
        }
        assertEquals(RLS_DENIED, refused.code)
    }

    @Test
    fun `a viewer's stock movement insert is refused as a permanent policy failure`() = runBlocking {
        val fixture = fixture()
        val movementId = newId()

        val error = refusal {
            uploader(viewer).insert(
                MEDS_SCHEMA,
                STOCK_MOVEMENTS_TABLE,
                movementId,
                stockMovementValues(fixture.medicationId, "refill", "10", reason = "viewer"),
                duplicatePolicy(STOCK_MOVEMENTS_TABLE),
            )
        }

        assertEquals(RLS_DENIED, error.code)
        assertTrue(UploadFailure.of(error).isPermanent)
        assertNull(stack.row(owner, MEDS_SCHEMA, STOCK_MOVEMENTS_TABLE, movementId))
    }

    // --- (g) a viewer may not write -----------------------------------------

    @Test
    fun `a viewer's dose log insert is refused as a permanent policy failure`() = runBlocking {
        val fixture = fixture()
        val entryId = newId()

        // The entry is the viewer's own (confirmed_by is the caller), so only the
        // patient policy can refuse it — which is the rule under test.
        val error = refusal {
            uploader(viewer).insert(
                MEDS_SCHEMA,
                DOSE_LOG_TABLE,
                entryId,
                doseLogValues(fixture, confirmedBy = viewer.userId, note = "viewer"),
                duplicatePolicy(DOSE_LOG_TABLE),
            )
        }

        assertEquals(RLS_DENIED, error.code)
        val failure = UploadFailure.of(error)
        assertTrue(failure.isPermanent, "A policy denial must not be retried forever")
        assertEquals(RejectionReason.NOT_ALLOWED, failure.rejectionReason())
        assertNull(
            stack.row(owner, MEDS_SCHEMA, DOSE_LOG_TABLE, entryId),
            "The refused row must not exist on the server",
        )
    }

    // --- (g) the whole path: repository -> queue -> uploadTransaction ------

    @Test
    fun `a medication saved on the phone is uploaded through uploadTransaction`() = runBlocking {
        val patientId = newPatient()
        val medicationId = newId()
        val regimenId = newId()

        val local = MedsLocalDatabase(
            MedsLocalDatabase.testDirectory("meds-upload"),
            userId = owner.userId,
            circleId = circleId,
        )
        try {
            local.seedOwner()
            local.seedPatient(patientId, circleId = circleId)
            // The seeded circle/membership/patient are already synced: only the
            // repository's own write should reach the server.
            local.database.drainUploadQueue()

            val repository = PowerSyncMedicationRepository(
                database = local.database,
                authState = local.authState,
                now = { NOW },
                newId = { regimenId },
            )
            assertEquals(
                io.github.lgpy.familycare.feature.medications.data.MedsResult.Success(
                    MedicationId(medicationId),
                ),
                repository.create(medication(medicationId, patientId), regimen(regimenId, medicationId)),
            )

            val transaction = local.database.nextTransaction()
            val rejections = UploadRejections()
            uploadTransaction(
                writes = transaction.crudWrites(tables),
                uploader = uploader(owner),
                rejections = rejections,
                complete = { transaction.complete(null) },
            )

            assertNull(rejections.state.value, "A permitted write records no refusal")
            assertNull(local.database.getNextCrudTransaction(), "The accepted transaction should be completed")
            assertEquals(
                "Metformin",
                stack.row(owner, MEDS_SCHEMA, MEDICATIONS_TABLE, medicationId)?.text("name"),
            )
            assertEquals(
                "2026-11-01",
                stack.row(owner, MEDS_SCHEMA, REGIMENS_TABLE, regimenId)?.text("start_date"),
            )
        } finally {
            local.close()
        }
    }

    // --- Helpers ------------------------------------------------------------

    /** The core's tables plus this module's: what `DataLayer.create` builds. */
    private val tables = TableCatalog(listOf(CoreSyncedTables, MedicationsSyncedTables))

    private fun uploader(user: MedsLocalStack.TestUser) =
        SupabaseCrudUploader(user.client.postgrest, tables)

    /**
     * The duplicate rule the table's policy declares, which is what the
     * connector passes (`CrudEntry.toCrudWrite`): the upload tests then assert
     * the policy the app ships rather than a value the test chose itself.
     */
    private fun duplicatePolicy(table: String): DuplicateInsert =
        checkNotNull(tables.syncedTable(table)) { "$table is not a synced table" }
            .upload
            .duplicateInsert

    /** A new patient on the server, as the owner, through the core's policy. */
    private suspend fun newPatient(): String = newId().also { patientId ->
        uploader(owner).insert(
            CORE_SCHEMA,
            PATIENTS_TABLE,
            patientId,
            mapOf(
                "circle_id" to JsonPrimitive(circleId),
                "display_name" to JsonPrimitive("Grandma"),
                "reminder_policy" to JsonPrimitive("primary_with_escalation"),
                "escalation_delay_minutes" to JsonPrimitive(DEFAULT_ESCALATION_DELAY),
                "missed_window_minutes" to JsonPrimitive(DEFAULT_MISSED_WINDOW),
            ),
            DuplicateInsert.UPDATE_EXISTING,
        )
    }

    /** A patient with one medication and one open-ended daily regimen on the server. */
    private suspend fun fixture(): Fixture {
        val patientId = newPatient()

        val medicationId = newId()
        uploader(owner).insert(
            MEDS_SCHEMA,
            MEDICATIONS_TABLE,
            medicationId,
            medicationValues(patientId),
            duplicatePolicy(MEDICATIONS_TABLE),
        )

        val regimenId = newId()
        uploader(owner).insert(
            MEDS_SCHEMA,
            REGIMENS_TABLE,
            regimenId,
            regimenValues(medicationId),
            duplicatePolicy(REGIMENS_TABLE),
        )

        return Fixture(patientId, medicationId, regimenId)
    }

    /** The columns a phone PUT carries for a medication, as PostgREST JSON types. */
    private fun medicationValues(patientId: String): Map<String, JsonElement> = mapOf(
        "patient_id" to JsonPrimitive(patientId),
        "name" to JsonPrimitive("Metformin"),
        "form" to JsonPrimitive("tablet"),
        "stock_unit" to JsonPrimitive("tablet"),
        "requires_prescription" to JsonPrimitive(true),
        "low_stock_threshold_days" to JsonPrimitive(7),
    )

    /** The columns a phone PUT carries for a regimen version. */
    private fun regimenValues(medicationId: String): Map<String, JsonElement> = mapOf(
        "medication_id" to JsonPrimitive(medicationId),
        "schedule_type" to JsonPrimitive("daily"),
        "start_date" to JsonPrimitive(START_DATE),
        "time_zone" to JsonPrimitive("Europe/Lisbon"),
        "times" to JsonPrimitive(TIMES),
    )

    /** The columns a phone PUT carries for a dose-log entry. */
    private fun doseLogValues(        fixture: Fixture,
        confirmedBy: String,
        note: String,
    ): Map<String, JsonElement> = mapOf(
        "regimen_id" to JsonPrimitive(fixture.regimenId),
        "medication_id" to JsonPrimitive(fixture.medicationId),
        "scheduled_local" to JsonPrimitive(SCHEDULED_LOCAL),
        "outcome" to JsonPrimitive("taken"),
        "actual_at" to JsonPrimitive(ACTUAL_AT),
        "actual_quantity" to JsonPrimitive("0.5"),
        "confirmed_by" to JsonPrimitive(confirmedBy),
        "given_by_other" to JsonPrimitive(false),
        "note" to JsonPrimitive(note),
    )

    /** The columns a phone PUT carries for a stock movement (D28). */
    private fun stockMovementValues(
        medicationId: String,
        kind: String,
        quantity: String,
        reason: String?,
    ): Map<String, JsonElement> = buildMap {
        put("medication_id", JsonPrimitive(medicationId))
        put("kind", JsonPrimitive(kind))
        put("quantity", JsonPrimitive(quantity))
        reason?.let { put("reason", JsonPrimitive(it)) }
        // A count carries no reason; the column stays null on the server.
        put("occurred_at", JsonPrimitive(OCCURRED_AT))
    }

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

    private fun newId(): String = UUID.randomUUID().toString()

    private fun JsonObject.text(column: String): String? =
        (get(column) as? JsonPrimitive)?.takeIf { it !== JsonNull }?.content

    /** The `(time, quantity)` pairs of a regimen's `times` jsonb array. */
    private fun JsonObject.times(): List<Pair<String, String>> =
        (get("times") as JsonArray).map { item ->
            val entry = item as JsonObject
            entry.getValue("time").jsonPrimitive.content to
                entry.getValue("quantity").jsonPrimitive.content
        }

    private fun medication(id: String, patientId: String) = Medication(
        id = MedicationId(id),
        patientId = PatientId(patientId),
        name = "Metformin",
        strength = "500 mg",
        form = MedicationForm.TABLET,
        stockUnit = "tablet",
        requiresPrescription = true,
    )

    private fun regimen(id: String, medicationId: String) = Regimen(
        id = RegimenId(id),
        medicationId = MedicationId(medicationId),
        scheduleType = ScheduleType.DAILY,
        startDate = LocalDate.of(2026, 11, 1),
        timeZone = ZoneId.of("Europe/Lisbon"),
        times = listOf(DoseTime(LocalTime.of(8, 0), BigDecimal("0.5"))),
    )

    /** A patient, a medication and its regimen, as the uploader created them. */
    private class Fixture(
        val patientId: String,
        val medicationId: String,
        val regimenId: String,
    )

    private companion object {
        /** `check_violation`: the versioning trigger refused the change. */
        const val CHECK_VIOLATION = "23514"
        const val RLS_DENIED = "42501"

        const val START_DATE = "2026-11-01"
        const val END_DATE = "2026-11-20"
        const val TIMES = """[{"time":"08:00","quantity":0.5}]"""
        const val CHANGED_TIMES = """[{"time":"09:00","quantity":1}]"""
        const val SCHEDULED_LOCAL = "2026-11-19 08:00:00"
        const val ACTUAL_AT = "2026-11-19T08:05:00.000Z"
        const val OCCURRED_AT = "2026-11-19T10:00:00.000Z"

        const val DEFAULT_ESCALATION_DELAY = 30
        const val DEFAULT_MISSED_WINDOW = 120

        /** 10:00 in Lisbon (WET in November), the repositories' clock. */
        val NOW: Instant = Instant.parse("2026-11-19T10:00:00Z")
    }
}
