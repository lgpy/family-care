package io.github.lgpy.familycare.core.data.sync

import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The writes a synced table needs, in terms the app owns.
 *
 * A fake implementation records the calls in unit tests, so the
 * CRUD-to-operation mapping is verified without a network or a database.
 *
 * Each call names its PostgREST schema, because a feature module's tables live
 * in their own schema (`meds`) next to the core's (`core`).
 */
internal interface CrudUploader {
    suspend fun insert(
        schema: String,
        table: String,
        id: String,
        values: Map<String, JsonElement>,
        duplicate: DuplicateInsert,
    )

    suspend fun update(schema: String, table: String, id: String, values: Map<String, JsonElement>)

    suspend fun delete(schema: String, table: String, id: String)
}

/**
 * The column set an upload statement uses: an INSERT carries every writable
 * column, an UPDATE only the ones the policy lets change.
 */
internal enum class UploadColumnSet { INSERT, UPDATE }

/**
 * Writes to PostgREST with supabase-kt, using the signed-in user's token.
 *
 * Deliberately not PowerSync's prebuilt Supabase connector: that one writes to
 * the `public` schema. Here `from(schema, table)` sets
 * `Accept-Profile`/`Content-Profile`, and Row-Level Security still decides
 * whether the row belongs to the caller.
 *
 * No `Prefer: return=representation`: the rows come back through the sync
 * stream, not through the upload response.
 *
 * @param tables The synced tables and their upload policies; it decides which
 *   columns are sent and what a duplicate INSERT means.
 */
internal class SupabaseCrudUploader(
    private val postgrest: Postgrest,
    private val tables: TableCatalog,
) : CrudUploader {

    /**
     * A plain INSERT, then either an UPDATE or nothing when the row already
     * exists.
     *
     * Not a PostgREST upsert (`INSERT ... ON CONFLICT DO UPDATE`): for that
     * statement Postgres also applies the table's SELECT policy to the new row,
     * and `patients_select_can_read` calls `core.can(id, 'read')`, which looks
     * the patient up in `core.patients` where the row being inserted is not yet
     * visible. Every new patient would then be refused as an RLS violation. The
     * medications tables use the same shape (`meds.patient_of_medication`), so a
     * plain insert is required there too.
     *
     * A `23505` is a retry whose first INSERT reached the server but whose
     * response was lost, or a local `INSERT OR REPLACE` of a synced row. What it
     * means is per table: an editable row is re-sent as an UPDATE, while an
     * insert-only row (a dose-log entry, a regimen version) is already exactly
     * as sent, so the write is treated as done. Falling back to an UPDATE there
     * would hit the append-only/versioning triggers and be reported to the user
     * as a refusal of a write that actually succeeded.
     */
    override suspend fun insert(
        schema: String,
        table: String,
        id: String,
        values: Map<String, JsonElement>,
        duplicate: DuplicateInsert,
    ) {
        val body = uploadRow(tables, table, UploadColumnSet.INSERT, id, values) ?: return
        try {
            postgrest.from(schema, table).insert(body = JsonArray(listOf(body)))
        } catch (error: PostgrestRestException) {
            if (error.code != UNIQUE_VIOLATION) throw error
            when (duplicate) {
                DuplicateInsert.ALREADY_APPLIED -> Unit
                DuplicateInsert.UPDATE_EXISTING -> update(schema, table, id, values)
            }
        }
    }

    override suspend fun update(schema: String, table: String, id: String, values: Map<String, JsonElement>) {
        // No id in the body: it is the filter, and sending it would be a no-op.
        val body = uploadRow(tables, table, UploadColumnSet.UPDATE, id = null, values) ?: return
        postgrest.from(schema, table).update(body = body) {
            filter { eq("id", id) }
        }
    }

    override suspend fun delete(schema: String, table: String, id: String) {
        if (tables.syncedTable(table)?.upload?.deleteAllowed != true) return
        postgrest.from(schema, table).delete {
            filter { eq("id", id) }
        }
    }
}

/**
 * The JSON object PostgREST receives for one row, or null when there is nothing
 * to send (an unknown table, a write the policy does not allow, or an update
 * with no writable column left).
 *
 * @param columns Whether this is an INSERT (all writable columns) or an UPDATE
 *   (only the ones the policy lets change).
 * @param id Included for an insert only; null for an update, where the id is
 *   the `eq` filter instead.
 * @param values Raw values from the PowerSync upload queue. Columns outside the
 *   policy's set (the server-managed timestamps included) are dropped, and JSON
 *   nulls are kept so clearing a field (an agreement note, an end date) reaches
 *   the server as NULL rather than being silently omitted.
 */
internal fun uploadRow(
    tables: TableCatalog,
    table: String,
    columns: UploadColumnSet,
    id: String?,
    values: Map<String, JsonElement>,
): JsonObject? {
    val synced = tables.syncedTable(table) ?: return null
    val writable = when (columns) {
        UploadColumnSet.INSERT -> synced.upload.insertColumns
        UploadColumnSet.UPDATE -> synced.upload.updateColumns
    }
    if (writable.isEmpty()) return null

    val body = buildMap<String, JsonElement> {
        id?.let { put("id", JsonPrimitive(it)) }
        values.forEach { (column, value) ->
            if (column in writable) put(column, synced.upload.converted(column, value))
        }
    }

    return if (body.isEmpty()) null else JsonObject(body)
}

/**
 * The value PostgREST receives for one column.
 *
 * SQLite has no boolean, `numeric` and `jsonb`, so three kinds of column are
 * stored locally in a form PostgREST would not accept as-is and are converted
 * here: decimal strings become JSON numbers, JSON text becomes a JSON value,
 * and 0/1 integers become JSON booleans. Every other value is passed through
 * untouched. A value that does not parse is left as it is, so the server reports
 * it as a constraint failure the queue can classify as permanent instead of the
 * upload retrying a local formatting bug forever.
 */
private fun UploadPolicy.converted(column: String, value: JsonElement): JsonElement {
    val primitive = value as? JsonPrimitive ?: return value

    if (column in booleanColumns) {
        return when (primitive.content) {
            "1" -> JsonPrimitive(true)
            "0" -> JsonPrimitive(false)
            "true" -> JsonPrimitive(true)
            "false" -> JsonPrimitive(false)
            else -> value
        }
    }

    if (!primitive.isString) return value

    if (column in decimalColumns) {
        val number = primitive.content.toBigDecimalOrNull() ?: return value
        return JsonPrimitive(number)
    }

    if (column in jsonColumns) {
        return runCatching { Json.parseToJsonElement(primitive.content) }.getOrDefault(value)
    }

    return value
}

/** `unique_violation`: the row is already on the server. */
private const val UNIQUE_VIOLATION = "23505"
