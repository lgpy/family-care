package io.github.lgpy.familycare.core.data.sync

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapping from queued local changes to backend operations, and from a row to
 * the JSON PostgREST receives: no PowerSync, no PostgREST, no network.
 */
class CrudWritesTest {

    private class RecordingUploader : CrudUploader {
        val calls = mutableListOf<String>()
        val bodies = mutableListOf<Map<String, JsonElement>>()

        override suspend fun upsert(table: String, id: String, values: Map<String, JsonElement>) {
            calls += "upsert:$table:$id"
            bodies += values
        }

        override suspend fun update(table: String, id: String, values: Map<String, JsonElement>) {
            calls += "update:$table:$id"
            bodies += values
        }

        override suspend fun delete(table: String, id: String) {
            calls += "delete:$table:$id"
            bodies += emptyMap()
        }
    }

    @Test
    fun `writes are sent in order with their table`() = runTest {
        val uploader = RecordingUploader()

        uploadWrites(
            listOf(
                write(PATIENTS_TABLE, "patient-1", CrudWriteKind.UPSERT),
                write(PATIENTS_TABLE, "patient-1", CrudWriteKind.UPDATE),
                write(PROFILES_TABLE, "user-1", CrudWriteKind.UPDATE),
                write(PATIENTS_TABLE, "patient-1", CrudWriteKind.DELETE),
            ),
            uploader,
        )

        assertEquals(
            listOf(
                "upsert:patients:patient-1",
                "update:patients:patient-1",
                "update:profiles:user-1",
                "delete:patients:patient-1",
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
    fun `only the tables the app owns have writable columns`() {
        assertEquals(setOf("name"), uploadColumns(CARE_CIRCLES_TABLE))
        assertEquals(
            setOf("display_name", "preferred_language"),
            uploadColumns(PROFILES_TABLE),
        )
        assertTrue(uploadColumns(PATIENTS_TABLE)!!.contains("agreement_note"))
        assertNull(uploadColumns(MEMBERSHIPS_TABLE))
        assertNull(uploadColumns("doses"))
    }

    @Test
    fun `a memberships write is never uploaded`() {
        // The server grants no DML on memberships at all: membership changes go
        // through the core.* RPCs, so even a stray local write is not uploadable.
        assertNull(uploadColumns(MEMBERSHIPS_TABLE))
    }

    // --- What a row body contains ------------------------------------------

    @Test
    fun `server-managed columns are dropped`() {
        val row = uploadRow(
            table = PATIENTS_TABLE,
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
            table = CARE_CIRCLES_TABLE,
            id = "circle-1",
            values = mapOf(
                "name" to JsonPrimitive("Our family"),
                "created_at" to JsonPrimitive("2026-11-18T10:00:00Z"),
            ),
        )

        assertEquals(
            mapOf("id" to JsonPrimitive("circle-1"), "name" to JsonPrimitive("Our family")),
            row,
        )
    }

    @Test
    fun `an update has no id in the body and keeps explicit nulls`() {
        val row = uploadRow(
            table = PATIENTS_TABLE,
            id = null,
            values = mapOf(
                "agreement_note" to JsonNull,
                "display_name" to JsonPrimitive("Grandma"),
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
        assertNull(uploadRow(PROFILES_TABLE, id = null, values = emptyMap()))
        assertNull(
            uploadRow(
                PROFILES_TABLE,
                id = null,
                values = mapOf("created_at" to JsonPrimitive("2026-11-18T10:00:00Z")),
            ),
        )
    }

    @Test
    fun `a read-only table never produces a body`() {
        assertNull(
            uploadRow(
                MEMBERSHIPS_TABLE,
                id = "membership-1",
                values = mapOf("role" to JsonPrimitive("owner")),
            ),
        )
    }

    private fun write(
        table: String,
        id: String,
        kind: CrudWriteKind,
    ) = CrudWrite(
        table = table,
        id = id,
        kind = kind,
        values = mapOf("display_name" to JsonPrimitive("Grandma")),
    )
}
