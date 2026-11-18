package io.github.lgpy.familycare.core.data.sync

import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The three writes a synced table needs, in terms the app owns.
 *
 * A fake implementation records the calls in unit tests, so the
 * CRUD-to-operation mapping is verified without a network or a database.
 */
internal interface CrudUploader {
    suspend fun upsert(table: String, id: String, values: Map<String, JsonElement>)

    suspend fun update(table: String, id: String, values: Map<String, JsonElement>)

    suspend fun delete(table: String, id: String)
}

/**
 * The columns a client may write for [table], or null when the table is
 * read-only locally.
 *
 * Only columns the app owns are listed; `created_at`/`updated_at` are stamped by
 * the database trigger (`core.set_updated_at`) and are never sent, and
 * `memberships` has no writable columns at all because membership changes go
 * through the `core.*` RPCs. An unknown table (including a future module's)
 * returns null, so nothing about it is uploaded by this uploader.
 */
internal fun uploadColumns(table: String): Set<String>? = when (table) {
    PATIENTS_TABLE -> PATIENT_UPLOAD_COLUMNS
    CARE_CIRCLES_TABLE -> CARE_CIRCLE_UPLOAD_COLUMNS
    PROFILES_TABLE -> PROFILE_UPLOAD_COLUMNS
    else -> null
}

/** A circle is created by `core.create_circle`; only its name is editable. */
private val CARE_CIRCLE_UPLOAD_COLUMNS: Set<String> = setOf("name")

/** A profile row is created by the server; name and language are the user's. */
private val PROFILE_UPLOAD_COLUMNS: Set<String> = setOf("display_name", "preferred_language")

/** Everything on a patient except the server-managed timestamps and its circle. */
private val PATIENT_UPLOAD_COLUMNS: Set<String> = setOf(
    "circle_id",
    "display_name",
    "linked_user_id",
    "reminder_policy",
    "primary_caregiver_id",
    "escalation_delay_minutes",
    "missed_window_minutes",
    "agreement_note",
    "archived_at",
)

/**
 * Writes to the `core` schema through PostgREST with supabase-kt, using the
 * signed-in user's token.
 *
 * Deliberately not PowerSync's prebuilt Supabase connector: that one writes to
 * the `public` schema. Here `from(CORE_SCHEMA, table)` sets
 * `Accept-Profile`/`Content-Profile: core`, and Row-Level Security still decides
 * whether the row belongs to the caller.
 *
 * No `Prefer: return=representation`: the rows come back through the sync
 * stream, not through the upload response.
 */
internal class SupabaseCrudUploader(
    private val postgrest: Postgrest,
) : CrudUploader {

    /**
     * A plain INSERT, falling back to an UPDATE when the row already exists.
     *
     * Not a PostgREST upsert (`INSERT ... ON CONFLICT DO UPDATE`): for that
     * statement Postgres also applies the table's SELECT policy to the new row,
     * and `patients_select_can_read` calls `core.can(id, 'read')`, which looks
     * the patient up in `core.patients` where the row being inserted is not yet
     * visible. Every new patient would then be refused as an RLS violation.
     *
     * The duplicate case is a retry whose first INSERT reached the server but
     * whose response was lost, or a local `INSERT OR REPLACE` of a synced row.
     */
    override suspend fun upsert(table: String, id: String, values: Map<String, JsonElement>) {
        val body = uploadRow(table, id, values) ?: return
        try {
            postgrest.from(CORE_SCHEMA, table).insert(body = JsonArray(listOf(body)))
        } catch (error: PostgrestRestException) {
            if (error.code != UNIQUE_VIOLATION) throw error
            update(table, id, values)
        }
    }

    override suspend fun update(table: String, id: String, values: Map<String, JsonElement>) {
        // No id in the body: it is the filter, and sending it would be a no-op.
        val body = uploadRow(table, id = null, values) ?: return
        postgrest.from(CORE_SCHEMA, table).update(body = body) {
            filter { eq("id", id) }
        }
    }

    override suspend fun delete(table: String, id: String) {
        if (uploadColumns(table) == null) return
        postgrest.from(CORE_SCHEMA, table).delete {
            filter { eq("id", id) }
        }
    }
}

/**
 * The JSON object PostgREST receives for one row, or null when there is nothing
 * to send (a read-only table, or an update with no writable column left).
 *
 * @param id Included for an upsert only; null for an update, where the id is
 *   the `eq` filter instead.
 * @param values Raw values from the PowerSync upload queue. Columns outside
 *   [uploadColumns] (the server-managed timestamps included) are dropped, and
 *   JSON nulls are kept so clearing a field (an agreement note, an archive
 *   stamp) reaches the server as NULL rather than being silently omitted.
 */
internal fun uploadRow(
    table: String,
    id: String?,
    values: Map<String, JsonElement>,
): JsonObject? {
    val columns = uploadColumns(table) ?: return null

    val body = buildMap<String, JsonElement> {
        id?.let { put("id", JsonPrimitive(it)) }
        values.forEach { (column, value) ->
            if (column in columns) put(column, value)
        }
    }

    return if (body.isEmpty()) null else JsonObject(body)
}

/** `unique_violation`: the row is already on the server. */
private const val UNIQUE_VIOLATION = "23505"
