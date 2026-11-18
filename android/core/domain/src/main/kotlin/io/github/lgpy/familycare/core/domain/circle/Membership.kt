package io.github.lgpy.familycare.core.domain.circle

/** Identity of one `core.memberships` row (user × circle). */
@JvmInline
value class MembershipId(val value: String) {
    init {
        require(value.isNotBlank()) { "A membership id must not be blank" }
    }
}

/**
 * What a member may do in a circle (docs/domain/care-circle.md "Membership").
 *
 * The permission rules themselves live in `AccessRules.kt` and mirror
 * `core.can_circle()` exactly; [serverValue] is the enum value the database and
 * the sync stream use.
 */
enum class MemberRole(val serverValue: String) {
    /** Everything, including managing members and deleting data. */
    OWNER("owner"),

    /** Full day-to-day use: medications, doses, appointments, patient info. */
    CAREGIVER("caregiver"),

    /** Read-only. Modelled and accepted from the server, not offered in the UI yet. */
    VIEWER("viewer"),

    ;

    companion object {
        /** The role with this database value, or null when it is not one we know. */
        fun fromServer(value: String?): MemberRole? =
            entries.firstOrNull { it.serverValue == value }

        /**
         * The roles an owner may hand out with an invitation. `owner` is
         * deliberately absent: `core.create_invitation` rejects it (the server
         * constraint is `invitations_role_not_owner`).
         */
        val invitables: List<MemberRole> = listOf(CAREGIVER, VIEWER)
    }
}

/**
 * Which user belongs to which circle, with a role.
 *
 * Clients never write memberships directly: they change only through the
 * `core.*` RPCs (create circle, accept invitation, remove member), so the local
 * table is read-only for the app (see `CrudUploader`).
 */
data class Membership(
    val id: MembershipId,
    val circleId: CareCircleId,
    val userId: String,
    val role: MemberRole,
)

/**
 * One row of the family list: a membership joined with the co-member's profile.
 *
 * @param userId `auth.users.id` of the member.
 * @param displayName The name from `core.profiles`, or null while that profile
 *   row has not synced yet. The screen must have a fallback, never show the id
 *   blindly as a name.
 * @param isMe Whether this membership belongs to the signed-in user, so a
 *   screen can label "you" and avoid offering "remove" on itself.
 */
data class CircleMember(
    val membershipId: MembershipId,
    val userId: String,
    val displayName: String?,
    val role: MemberRole,
    val isMe: Boolean,
)
