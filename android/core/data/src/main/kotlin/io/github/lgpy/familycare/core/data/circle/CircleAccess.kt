package io.github.lgpy.familycare.core.data.circle

import com.powersync.PowerSyncDatabase
import com.powersync.db.getString
import io.github.lgpy.familycare.core.domain.circle.CareCircleId
import io.github.lgpy.familycare.core.domain.circle.MemberRole

/**
 * Who the signed-in user is in their current circle, as the local database
 * knows it.
 *
 * This is the client half of `core.can(patient, action)`: a feature module
 * reads it before writing so a viewer is refused with
 * `CoreError.NotAllowed` (or the feature's own equivalent) before anything is
 * queued, instead of the upload being refused permanently later. The server
 * remains the authority.
 */
public data class CircleAccess(
    /** The current circle, chosen by the same rule as every core query. */
    public val circleId: CareCircleId,
    /** The signed-in user's role in that circle. */
    public val role: MemberRole,
)

/**
 * The signed-in user's current circle and role, or null when they belong to no
 * circle yet (or the synced role is one this app does not know).
 *
 * Uses the same "oldest membership, tie-broken by id" rule as
 * [io.github.lgpy.familycare.core.data.circle.LocalSql.SELECT_MY_ROLE], so a
 * feature module cannot disagree with the core about which circle is current.
 */
public suspend fun PowerSyncDatabase.circleAccess(userId: String): CircleAccess? {
    val row = getOptional(SELECT_CURRENT_CIRCLE_AND_ROLE, listOf(userId)) { cursor ->
        cursor.getString("circle_id") to cursor.getString("role")
    } ?: return null

    val role = MemberRole.fromServer(row.second) ?: return null
    return CircleAccess(circleId = CareCircleId(row.first), role = role)
}

/** The current circle and the role in it, in one read. */
private const val SELECT_CURRENT_CIRCLE_AND_ROLE = """
    SELECT circle_id, role
    FROM memberships
    WHERE user_id = ?
    ORDER BY created_at ASC, id ASC
    LIMIT 1
"""
