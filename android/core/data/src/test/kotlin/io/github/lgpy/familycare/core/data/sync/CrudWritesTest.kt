package io.github.lgpy.familycare.core.data.sync

import com.powersync.db.schema.Column
import com.powersync.db.schema.Table
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapping from queued local changes to backend operations, and from a row to
 * the JSON PostgREST receives: no PowerSync, no PostgREST, no network.
 *
 * The per-table upload policy is exercised with the core's own tables and with
 * a synthetic contribution shaped like a feature module's (insert-only rows,
 * `end_date`-only updates, decimal/JSON/boolean columns).
 */
class CrudWritesTest {

    private val coreTables = TableCatalog(listOf(CoreSyncedTables))

    private class RecordingUploader : CrudUploader {
        val calls = mutableListOf<String>()
        val bodies = mutableListOf<Map<String, JsonElement>>()

        override suspend fun insert(
            schema: String,
            table: String,
            id: String,
            values: Map<String, JsonElement>,
            duplicate: DuplicateInsert,
        ) {
            calls += "insert:$schema:$table:$id:$duplicate"
            bodies += values
        }

        override suspend fun update(schema: String, table: String, id: String, values: Map<String, JsonElement>) {
            calls += "update:$schema:$table:$id"
            bodies += values
        }

        override suspend fun delete(schema: String, table: String, id: String) {
            calls += "delete:$schema:$table:$id"
            bodies += emptyMap()
        }
    }

    @Test
    fun `writes are sent in order with their schema and table`() = runTest {
        val uploader = RecordingUploader()

        uploadWrites(
            listOf(
                write(CORE_SCHEMA, PATIENTS_TABLE, "patient-1", CrudWriteKind.INSERT),
                write(CORE_SCHEMA, PATIENTS_TABLE, "patient-1", CrudWriteKind.UPDATE),
                write(CORE_SCHEMA, PROFILES_TABLE, "user-1", CrudWriteKind.UPDATE),
                write(CORE_SCHEMA, PATIENTS_TABLE, "patient-1", CrudWriteKind.DELETE),
            ),
            uploader,
        )

        assertEquals(
            listOf(
                "insert:core:patients:patient-1:${DuplicateInsert.ALREADY_APPLIED}",
                "update:core:patients:patient-1",
                "update:core:profiles:user-1",
                "delete:core:patients:patient-1",
            ),
            uploader.calls,
        )
    }

    @Test
    fun `nothing to upload means no calls`() = runTest {
        val uploader = RecordingUploader()

        uploadWrites(emptyList(), uploader)

        assertTrue(uploader.calls.isEmpty())
    }

    // --- Which tables may be uploaded --------------------------------------

    @Test
    fun `the core's writable columns are declared per operation`() {
        assertEquals(
            setOf("patients", "profiles", "care_circles", "memberships"),
            coreTables.schema.tables.map { it.name }.toSet(),
        )
        assertEquals(setOf("name"), coreTables.syncedTable(CARE_CIRCLES_TABLE)!!.upload.updateColumns)
        assertTrue(coreTables.syncedTable(CARE_CIRCLES_TABLE)!!.upload.insertColumns.isEmpty())
        assertEquals(
            setOf("display_name", "preferred_language"),
            coreTables.syncedTable(PROFILES_TABLE)!!.upload.updateColumns,
        )
        assertTrue(coreTables.syncedTable(PATIENTS_TABLE)!!.upload.insertColumns.contains("agreement_note"))
        assertTrue(coreTables.syncedTable(PATIENTS_TABLE)!!.upload.deleteAllowed)
        assertTrue(coreTables.syncedTable(MEMBERSHIPS_TABLE)!!.upload.isReadOnly)
        assertNull(coreTables.syncedTable("doses"))
    }

    @Test
    fun `memberships stay read-only so a stray write is never uploaded`() {
        // The server grants no DML on memberships at all: membership changes go
        // through the core.* RPCs, so even a stray local write is not uploadable.
        val memberships = coreTables.syncedTable(MEMBERSHIPS_TABLE)!!

        assertTrue(memberships.upload.insertColumns.isEmpty())
        assertTrue(memberships.upload.updateColumns.isEmpty())
        assertFalse(memberships.upload.deleteAllowed)
        assertNull(uploadRow(coreTables, MEMBERSHIPS_TABLE, UploadColumnSet.INSERT, "membership-1", mapOf("role" to JsonPrimitive("owner"))))
        assertNull(uploadRow(coreTables, MEMBERSHIPS_TABLE, UploadColumnSet.UPDATE, null, mapOf("role" to JsonPrimitive("owner"))))
    }

    @Test
    fun `a duplicate table name is refused when the catalog is built`() {
        val duplicated = Table(
            name = PATIENTS_TABLE,
            columns = listOf(Column.text("display_name")),
        )

        val error = runCatching {
            TableCatalog(
                listOf(
                    CoreSyncedTables,
                    object : SyncedTables {
                        override val tables = listOf(
                            SyncedTable(CORE_SCHEMA, duplicated, UploadPolicy()),
                        )
                    },
                ),
            )
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error!!.message!!.contains(PATIENTS_TABLE))
    }

    // --- What a row body contains ------------------------------------------

    @Test
    fun `server-managed columns are dropped`() {
        val row = uploadRow(
            tables = coreTables,
            table = PATIENTS_TABLE,
            columns = UploadColumnSet.INSERT,
            id = "patient-1",
            values = mapOf(
                "circle_id" to JsonPrimitive("circle-1"),
                "display_name" to JsonPrimitive("Grandma"),
                "created_at" to JsonPrimitive("2026-11-18T10:00:00Z"),
                "updated_at" to JsonPrimitive("2026-11-18T10:00:00Z"),
            ),
        )

        assertEquals(
            mapOf(
                "id" to JsonPrimitive("patient-1"),
                "circle_id" to JsonPrimitive("circle-1"),
                "display_name" to JsonPrimitive("Grandma"),
            ),
            row,
        )
    }

    @Test
    fun `a circle only sends its name`() {
        val row = uploadRow(
            tables = coreTables,
            table = CARE_CIRCLES_TABLE,
            columns = UploadColumnSet.UPDATE,
            id = null,
            values = mapOf(
                "name" to JsonPrimitive("Our family"),
                "created_at" to JsonPrimitive("2026-11-18T10:00:00Z"),
            ),
        )

        assertEquals(mapOf("name" to JsonPrimitive("Our family")), row)
    }

    @Test
    fun `an update has no id in the body and keeps explicit nulls`() {
        val row = uploadRow(
            tables = coreTables,
            table = PATIENTS_TABLE,
            columns = UploadColumnSet.UPDATE,
            id = null,
            values = mapOf(
                "agreement_note" to JsonNull,
                "display_name" to JsonPrimitive("Grandma"),
                "created_at" to JsonPrimitive("2026-11-18T10:00:00Z"),
            ),
        )

        assertEquals(
            mapOf("agreement_note" to JsonNull, "display_name" to JsonPrimitive("Grandma")),
            row,
        )
        assertFalse(row!!.containsKey("id"))
    }

    @Test
    fun `an update with nothing writable is not sent`() {
        assertNull(uploadRow(coreTables, PROFILES_TABLE, UploadColumnSet.UPDATE, id = null, values = emptyMap()))
        assertNull(
            uploadRow(
                coreTables,
                PROFILES_TABLE,
                UploadColumnSet.UPDATE,
                id = null,
                values = mapOf("created_at" to JsonPrimitive("2026-11-18T10:00:00Z")),
            ),
        )
    }

    // --- A feature module's contribution -----------------------------------

    @Test
    fun `a feature table's insert carries only its writable columns and is retried idempotently`() {
        val row = uploadRow(
            tables = featureTables,
            table = DOSES,
            columns = UploadColumnSet.INSERT,
            id = "dose-1",
            values = mapOf(
                "regimen_id" to JsonPrimitive("regimen-1"),
                "actual_quantity" to JsonPrimitive("0.500"),
                "created_at" to JsonPrimitive("2026-11-18T10:00:00Z"),
            ),
        )

        assertEquals(
            mapOf(
                "id" to JsonPrimitive("dose-1"),
                "regimen_id" to JsonPrimitive("regimen-1"),
                "actual_quantity" to JsonPrimitive(java.math.BigDecimal("0.500")),
            ),
            row,
        )
        assertEquals(DuplicateInsert.ALREADY_APPLIED, featureTables.syncedTable(DOSES)!!.upload.duplicateInsert)
    }

    @Test
    fun `a feature table with no update columns produces no update body`() {
        assertNull(
            uploadRow(
                featureTables,
                DOSES,
                UploadColumnSet.UPDATE,
                id = null,
                values = mapOf("outcome" to JsonPrimitive("taken")),
            ),
        )
    }

    @Test
    fun `only the end date of a versioned table is sent on an update`() {
        val row = uploadRow(
            tables = featureTables,
            table = VERSIONS,
            columns = UploadColumnSet.UPDATE,
            id = null,
            values = mapOf(
                "end_date" to JsonPrimitive("2026-12-01"),
                "times" to JsonPrimitive("[]"),
                "start_date" to JsonPrimitive("2026-11-01"),
            ),
        )

        assertEquals(mapOf("end_date" to JsonPrimitive("2026-12-01")), row)
    }

    @Test
    fun `json, decimal and boolean columns are converted to what Postgres accepts`() {
        val row = uploadRow(
            tables = featureTables,
            table = VERSIONS,
            columns = UploadColumnSet.INSERT,
            id = "row-1",
            values = mapOf(
                "times" to JsonPrimitive("""[{"time":"08:00","quantity":0.5}]"""),
                "quantity" to JsonPrimitive("1.125"),
                "flag" to JsonPrimitive(1),
                "label" to JsonPrimitive("1.125"),
            ),
        )!!

        assertEquals(
            JsonArray(
                listOf(
                    kotlinx.serialization.json.JsonObject(
                        mapOf(
                            "time" to JsonPrimitive("08:00"),
                            "quantity" to JsonPrimitive(java.math.BigDecimal("0.5")),
                        ),
                    ),
                ),
            ),
            row["times"],
        )
        assertEquals("1.125", (row["quantity"] as JsonPrimitive).content)
        assertFalse((row["quantity"] as JsonPrimitive).isString)
        assertEquals(JsonPrimitive(true), row["flag"])
        // A column the policy does not declare keeps its text: no guessing.
        assertEquals(JsonPrimitive("1.125"), row["label"])
    }

    @Test
    fun `a value that does not parse is left for the server to refuse`() {
        val row = uploadRow(
            tables = featureTables,
            table = VERSIONS,
            columns = UploadColumnSet.INSERT,
            id = "row-1",
            values = mapOf(
                "times" to JsonPrimitive("not json"),
                "quantity" to JsonPrimitive("half"),
            ),
        )!!

        assertEquals(JsonPrimitive("not json"), row["times"])
        assertEquals(JsonPrimitive("half"), row["quantity"])
    }

    private val featureTables = TableCatalog(
        listOf(
            CoreSyncedTables,
            object : SyncedTables {
                override val tables = listOf(
                    SyncedTable(
                        schema = "meds",
                        table = Table(
                            name = DOSES,
                            columns = listOf(
                                Column.text("regimen_id"),
                                Column.text("actual_quantity"),
                                Column.text("created_at"),
                            ),
                        ),
                        upload = UploadPolicy(
                            insertColumns = setOf("regimen_id", "actual_quantity"),
                            decimalColumns = setOf("actual_quantity"),
                        ),
                    ),
                    SyncedTable(
                        schema = "meds",
                        table = Table(
                            name = VERSIONS,
                            columns = listOf(
                                Column.text("start_date"),
                                Column.text("end_date"),
                                Column.text("times"),
                                Column.text("quantity"),
                                Column.integer("flag"),
                                Column.text("label"),
                            ),
                        ),
                        upload = UploadPolicy(
                            insertColumns = setOf("start_date", "end_date", "times", "quantity", "flag", "label"),
                            updateColumns = setOf("end_date"),
                            jsonColumns = setOf("times"),
                            decimalColumns = setOf("quantity"),
                            booleanColumns = setOf("flag"),
                        ),
                    ),
                )
            },
        ),
    )

    private fun write(
        schema: String,
        table: String,
        id: String,
        kind: CrudWriteKind,
    ) = CrudWrite(
        schema = schema,
        table = table,
        id = id,
        kind = kind,
        values = mapOf("display_name" to JsonPrimitive("Grandma")),
    )

    private companion object {
        const val DOSES = "doses"
        const val VERSIONS = "versions"
    }
}
