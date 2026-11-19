package io.github.lgpy.familycare.core.data.activity

import io.github.jan.supabase.postgrest.Postgrest
import io.github.lgpy.familycare.core.data.circle.RpcCall
import io.github.lgpy.familycare.core.data.circle.careCircleErrorOf
import io.github.lgpy.familycare.core.data.sync.CORE_SCHEMA
import io.github.lgpy.familycare.core.data.sync.UploadFailure
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One row of `core.circle_activity` (D30): an audit entry or one of the
 * circle's security events, already labelled by the server.
 *
 * [label] is the short, non-clinical identifier the server derives (a
 * medication name, an appointment title, a patient or member name); the audit
 * payloads themselves are never returned. Names are null when the row no
 * longer resolves to a profile, and the screen shows "Someone" for them.
 */
data class ActivityEvent(
    val id: String,
    val occurredAt: Instant,
    val eventKind: String,
    val action: String,
    val tableName: String,
    val recordId: String? = null,
    val actorId: String? = null,
    val actorName: String? = null,
    val patientId: String? = null,
    val patientName: String? = null,
    val label: String? = null,
)

/**
 * The owner-only activity feed (D30, docs/domain/patient-lifecycle.md
 * "Activity"). It is online: the audit log is not synced.
 *
 * The implementation lives in this module (like [io.github.lgpy.familycare.core.data.circle.PatientRpc])
 * so the view model can be tested with a fake and the RPC's failures are mapped
 * without a network.
 */
interface ActivityRepository {

    /**
     * The circle's newest activity first.
     *
     * @param before Return only rows that happened before this instant (paging
     *   with the last row of the previous page), or null for the first page.
     * @param limit At most this many rows (the server caps it at 200).
     */
    suspend fun activity(
        circleId: String,
        before: Instant? = null,
        limit: Int = PAGE_SIZE,
    ): CoreResult<List<ActivityEvent>>

    companion object {
        /** The page size of the first read, mirrored by the server's cap check. */
        const val PAGE_SIZE: Int = 50
    }
}

/**
 * [ActivityRepository] over PostgREST with supabase-kt, schema `core`.
 *
 * Responses are parsed from their JSON body rather than decoded into
 * `@Serializable` classes: the app does not use the serialization compiler
 * plugin (same choice as `SupabaseCareCircleRpc`).
 */
internal class SupabaseActivityRepository(
    private val postgrest: Postgrest,
) : ActivityRepository {

    override suspend fun activity(
        circleId: String,
        before: Instant?,
        limit: Int,
    ): CoreResult<List<ActivityEvent>> = try {
        val params = buildMap<String, JsonElement> {
            put("p_circle_id", JsonPrimitive(circleId))
            put("p_limit", JsonPrimitive(limit))
            if (before != null) put("p_before", JsonPrimitive(before.toString()))
        }
        val result = postgrest.rpc(CIRCLE_ACTIVITY, JsonObject(params)) {
            schema = CORE_SCHEMA
        }
        val rows = Json.parseToJsonElement(result.data) as? JsonArray ?: JsonArray(emptyList())
        CoreResult.Success(rows.mapNotNull { row -> (row as? JsonObject)?.toActivityEvent() })
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        CoreResult.Failure(
            careCircleErrorOf(UploadFailure.of(error), error.message, RpcCall.CIRCLE_ACTIVITY),
        )
    }

    private fun JsonObject.toActivityEvent(): ActivityEvent? {
        val id = string("id") ?: return null
        val occurredAt = string("occurred_at")?.let(::parseInstant) ?: return null
        return ActivityEvent(
            id = id,
            occurredAt = occurredAt,
            eventKind = string("event_kind").orEmpty(),
            action = string("action").orEmpty(),
            tableName = string("table_name").orEmpty(),
            recordId = string("record_id"),
            actorId = string("actor_id"),
            actorName = string("actor_name"),
            patientId = string("patient_id"),
            patientName = string("patient_name"),
            label = string("label"),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    /**
     * PostgREST returns timestamptz as ISO-8601 with an explicit offset
     * (`+00:00` or `+01:00`); [Instant.parse] handles both, and
     * `OffsetDateTime` is the fallback for the raw Postgres text form.
     */
    private fun parseInstant(text: String): Instant? {
        val parsed = runCatching { Instant.parse(text) }.getOrNull()
        if (parsed != null) return parsed
        return runCatching {
            val offset = java.time.OffsetDateTime.parse(text)
            Instant.fromEpochSeconds(offset.toEpochSecond(), offset.nano)
        }.getOrNull()
    }

    private companion object {
        const val CIRCLE_ACTIVITY = "circle_activity"
    }
}
