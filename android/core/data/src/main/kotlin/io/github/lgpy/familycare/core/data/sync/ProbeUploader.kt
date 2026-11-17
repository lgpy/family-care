package io.github.lgpy.familycare.core.data.sync

import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The three writes the probe table needs, in terms the app owns.
 *
 * A fake implementation records the calls in unit tests, so the CRUD-to-
 * operation mapping is verified without a network or a database.
 */
internal interface ProbeUploader {
    suspend fun upsert(id: String, values: Map<String, String?>)

    suspend fun update(id: String, values: Map<String, String?>)

    suspend fun delete(id: String)
}

/**
 * Writes to `core.sync_probe` through PostgREST with supabase-kt, using the
 * signed-in user's token.
 *
 * Deliberately not PowerSync's prebuilt Supabase connector: that one writes to
 * the `public` stream schema. Here `from(PROBE_SCHEMA, PROBE_TABLE)` sets
 * `Accept-Profile`/`Content-Profile: core`, and Row-Level Security still decides
 * whether the row belongs to the caller.
 *
 * No `Prefer: return=representation`: the rows come back through the sync
 * stream, not through the upload response.
 */
internal class SupabaseProbeUploader(
    private val postgrest: Postgrest,
) : ProbeUploader {

    override suspend fun upsert(id: String, values: Map<String, String?>) {
        postgrest.from(PROBE_SCHEMA, PROBE_TABLE).upsert(body = JsonArray(listOf(row(id, values)))) {
            onConflict = "id"
        }
    }

    override suspend fun update(id: String, values: Map<String, String?>) {
        if (values.isEmpty()) return
        postgrest.from(PROBE_SCHEMA, PROBE_TABLE).update(body = row(null, values)) {
            filter { eq("id", id) }
        }
    }

    override suspend fun delete(id: String) {
        postgrest.from(PROBE_SCHEMA, PROBE_TABLE).delete {
            filter { eq("id", id) }
        }
    }
}

private fun row(id: String?, values: Map<String, String?>): JsonObject = JsonObject(
    buildMap {
        id?.let { put("id", JsonPrimitive(it)) }
        values.forEach { (column, value) ->
            put(column, if (value == null) JsonNull else JsonPrimitive(value))
        }
    },
)
