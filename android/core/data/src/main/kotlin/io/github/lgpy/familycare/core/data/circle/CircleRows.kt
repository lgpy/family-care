package io.github.lgpy.familycare.core.data.circle

import com.powersync.db.SqlCursor
import com.powersync.db.getLong
import com.powersync.db.getString
import com.powersync.db.getStringOptional
import io.github.lgpy.familycare.core.domain.circle.CareCircleId
import io.github.lgpy.familycare.core.domain.circle.CircleMember
import io.github.lgpy.familycare.core.domain.circle.Invitation
import io.github.lgpy.familycare.core.domain.circle.InvitationId
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.MembershipId
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One `patients` row as it comes out of SQLite, before it becomes a domain
 * [Patient].
 *
 * Kept separate from the cursor reading so the interesting part — enum names,
 * numeric columns, timestamps — is unit-tested without a PowerSync database.
 */
internal data class PatientRow(
    val id: String,
    val circleId: String,
    val displayName: String,
    val linkedUserId: String?,
    val reminderPolicy: String,
    val primaryCaregiverId: String?,
    val escalationDelayMinutes: Long,
    val missedWindowMinutes: Long,
    val agreementNote: String?,
    val archivedAt: String?,
) {

    /**
     * The domain patient, or null when the row holds a role or policy this app
     * does not know (a server-side enum value newer than the app). Skipping the
     * row beats crashing or showing a broken patient.
     */
    fun toPatient(): Patient? {
        val policy = ReminderPolicy.fromServer(reminderPolicy) ?: return null

        return Patient(
            id = PatientId(id),
            circleId = CareCircleId(circleId),
            displayName = displayName,
            linkedUserId = linkedUserId,
            reminderPolicy = policy,
            primaryCaregiverId = primaryCaregiverId,
            escalationDelayMinutes = escalationDelayMinutes.toInt(),
            missedWindowMinutes = missedWindowMinutes.toInt(),
            agreementNote = agreementNote,
            archivedAt = parseServerInstant(archivedAt),
        )
    }
}

/** Reads one row of `SELECT_PATIENTS` / `SELECT_PATIENT`. */
internal fun SqlCursor.toPatientRow(): PatientRow = PatientRow(
    id = getString("id"),
    circleId = getString("circle_id"),
    displayName = getString("display_name"),
    linkedUserId = getStringOptional("linked_user_id"),
    reminderPolicy = getString("reminder_policy"),
    primaryCaregiverId = getStringOptional("primary_caregiver_id"),
    escalationDelayMinutes = getLong("escalation_delay_minutes"),
    missedWindowMinutes = getLong("missed_window_minutes"),
    agreementNote = getStringOptional("agreement_note"),
    archivedAt = getStringOptional("archived_at"),
)

/**
 * One `memberships` row joined with the co-member's profile name, before it
 * becomes a [CircleMember]. A row whose role the app does not know is skipped by
 * [toCircleMember] rather than shown as a broken member.
 */
internal data class MemberRow(
    val membershipId: String,
    val userId: String,
    val displayName: String?,
    val role: String,
) {
    fun toCircleMember(meUserId: String): CircleMember? {
        val memberRole = MemberRole.fromServer(role) ?: return null

        return CircleMember(
            membershipId = MembershipId(membershipId),
            userId = userId,
            displayName = displayName,
            role = memberRole,
            isMe = userId == meUserId,
        )
    }
}

/**
 * A membership row as it comes out of SQLite, before it becomes a
 * [CircleMember] (for the member list) or a [MemberRole] (for the signed-in
 * user's own role).
 *
 * `watch`/`getOptional` need a non-null row type, so the cursor is read into
 * this DTO and the enum mapping — the only part that can fail — happens in
 * [toRole] / [toCircleMember].
 */
internal data class RoleRow(val role: String) {
    /** The role, or null for a server enum value newer than the app. */
    fun toRole(): MemberRole? = MemberRole.fromServer(role)
}

/** Reads one row of `SELECT_MY_ROLE` / `SELECT_MEMBERS`. */
internal fun SqlCursor.toRoleRow(): RoleRow = RoleRow(role = getString("role"))

/** Reads one row of `SELECT_MEMBERS`. */
internal fun SqlCursor.toMemberRow(): MemberRow = MemberRow(
    membershipId = getString("id"),
    userId = getString("user_id"),
    displayName = getStringOptional("display_name"),
    role = getString("role"),
)

/**
 * One `core.invitations` row from the PostgREST response, or null when the
 * columns needed to show it are missing or its role is not one an invitation can
 * carry.
 */
internal fun invitationFromJson(json: JsonObject): Invitation? {
    val id = json.text("id") ?: return null
    val circleId = json.text("circle_id") ?: return null
    val email = json.text("email") ?: return null
    val role = MemberRole.fromServer(json.text("role"))
        // `owner` cannot be invited (`invitations_role_not_owner`), so a row that
        // claims it is not something the UI could act on.
        ?.takeIf { it in MemberRole.invitables }
        ?: return null

    return Invitation(
        id = InvitationId(id),
        circleId = CareCircleId(circleId),
        email = email,
        role = role,
        expiresAt = json.instant("expires_at"),
        createdAt = json.instant("created_at"),
        acceptedAt = json.instant("accepted_at"),
        revokedAt = json.instant("revoked_at"),
    )
}

/** A string column, or null for JSON null / a missing key. */
internal fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.instant(name: String): Instant? = parseServerInstant(text(name))

/**
 * Parses a `timestamptz` the way it reaches the phone.
 *
 * PowerSync and PostgREST both send ISO-8601 text, but a PostgreSQL timestamp
 * may use a space separator and a short `+00` offset, which the strict parser
 * rejects. Anything else (including an empty string) is null rather than an
 * exception: a display timestamp must never break a screen.
 */
internal fun parseServerInstant(value: String?): Instant? {
    if (value.isNullOrBlank()) return null

    val normalized = value
        .trim()
        .replace(' ', 'T')
        .let { if (it.endsWith("+00")) "$it:00" else it }

    return Instant.parseOrNull(normalized)
}
