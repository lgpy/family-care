package io.github.lgpy.familycare.core.data.circle

import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.lgpy.familycare.core.data.sync.CORE_SCHEMA
import io.github.lgpy.familycare.core.data.sync.INVITATIONS_TABLE
import io.github.lgpy.familycare.core.domain.circle.Invitation
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The online half of [io.github.lgpy.familycare.core.domain.circle.CareCircleRepository]:
 * the `core.*` RPCs and the owner-only invitation list.
 *
 * An interface so the repository (which owns the local database) can be tested
 * with a fake, and the Supabase implementation can be replaced without touching
 * the mapping.
 */
internal interface CareCircleRpc {

    /** `core.create_circle(name)` → new circle id. */
    suspend fun createCircle(name: String): String

    /** `core.accept_invitation(code)` → joined circle id. */
    suspend fun acceptInvitation(normalizedCode: String): String

    /** `core.create_invitation(circle_id, email, role)` → the plaintext display code. */
    suspend fun createInvitation(circleId: String, email: String, role: MemberRole): String

    /** The circle's invitations, newest first (RLS limits this to owners). */
    suspend fun listInvitations(circleId: String): List<Invitation>

    /** `core.revoke_invitation(id)`. */
    suspend fun revokeInvitation(invitationId: String)

    /** `core.remove_member(membership_id)`. */
    suspend fun removeMember(membershipId: String)
}

/**
 * [CareCircleRpc] over PostgREST with supabase-kt, schema `core`.
 *
 * The invitations read names its columns explicitly: `authenticated` has no
 * SELECT privilege on `token_hash`, so `select=*` is rejected by PostgREST (the
 * M2 migration grants column by column).
 *
 * Responses are parsed from their JSON body rather than decoded into
 * `@Serializable` classes: the app does not use the serialization compiler
 * plugin, and the RPCs return scalars (a uuid, a code) that would need their own
 * DTOs otherwise.
 */
internal class SupabaseCareCircleRpc(
    private val postgrest: Postgrest,
) : CareCircleRpc {

    override suspend fun createCircle(name: String): String =
        scalar(
            postgrest.rpc(CREATE_CIRCLE, params("p_name" to JsonPrimitive(name))) {
                schema = CORE_SCHEMA
            },
        )

    override suspend fun acceptInvitation(normalizedCode: String): String =
        scalar(
            postgrest.rpc(ACCEPT_INVITATION, params("p_code" to JsonPrimitive(normalizedCode))) {
                schema = CORE_SCHEMA
            },
        )

    override suspend fun createInvitation(
        circleId: String,
        email: String,
        role: MemberRole,
    ): String = scalar(
        postgrest.rpc(
            CREATE_INVITATION,
            params(
                "p_circle_id" to JsonPrimitive(circleId),
                "p_email" to JsonPrimitive(email),
                "p_role" to JsonPrimitive(role.serverValue),
            ),
        ) {
            schema = CORE_SCHEMA
        },
    )

    override suspend fun listInvitations(circleId: String): List<Invitation> {
        val result = postgrest.from(CORE_SCHEMA, INVITATIONS_TABLE).select(INVITATION_COLUMNS) {
            filter { eq("circle_id", circleId) }
            order("created_at", Order.DESCENDING)
        }

        val rows = Json.parseToJsonElement(result.data) as? JsonArray ?: JsonArray(emptyList())
        return rows.mapNotNull { row -> (row as? JsonObject)?.let(::invitationFromJson) }
    }

    override suspend fun revokeInvitation(invitationId: String) {
        postgrest.rpc(REVOKE_INVITATION, params("p_invitation_id" to JsonPrimitive(invitationId))) {
            schema = CORE_SCHEMA
        }
    }

    override suspend fun removeMember(membershipId: String) {
        postgrest.rpc(REMOVE_MEMBER, params("p_membership_id" to JsonPrimitive(membershipId))) {
            schema = CORE_SCHEMA
        }
    }

    private fun scalar(result: PostgrestResult): String =
        (Json.parseToJsonElement(result.data) as? JsonPrimitive)?.content
            ?: error("A core RPC returned something other than a JSON scalar")

    private fun params(vararg entries: Pair<String, JsonElement>): JsonObject =
        JsonObject(entries.toMap())

    private companion object {
        const val CREATE_CIRCLE = "create_circle"
        const val ACCEPT_INVITATION = "accept_invitation"
        const val CREATE_INVITATION = "create_invitation"
        const val REVOKE_INVITATION = "revoke_invitation"
        const val REMOVE_MEMBER = "remove_member"

        /**
         * Everything `authenticated` may select on `core.invitations` except
         * `token_hash`, which the migration does not grant.
         */
        val INVITATION_COLUMNS: Columns = Columns.list(
            "id",
            "circle_id",
            "email",
            "role",
            "expires_at",
            "created_at",
            "accepted_at",
            "revoked_at",
        )
    }
}
