package io.github.lgpy.familycare.core.domain.circle

/**
 * The three questions every feature asks about a patient or a circle.
 *
 * They are the client half of `core.can_circle(circle_id, action)` in the M2
 * migration, so a screen can hide a write button for a viewer before the server
 * rejects the write. The server remains the authority: these helpers only decide
 * what to show.
 */
enum class CircleAction(val serverValue: String) {
    /** Any member, including a viewer. */
    READ("read"),

    /** Owner or caregiver. */
    WRITE("write"),

    /** Owner only. */
    MANAGE("manage"),
}

/**
 * Whether [this] role may perform [action] in its circle.
 *
 * A null receiver (signed in, but not a member of any circle) can do nothing,
 * which is the same answer the database gives for an unknown circle.
 */
fun MemberRole?.can(action: CircleAction): Boolean = when (action) {
    CircleAction.READ -> this != null
    CircleAction.WRITE -> this == MemberRole.OWNER || this == MemberRole.CAREGIVER
    CircleAction.MANAGE -> this == MemberRole.OWNER
}

/** Any member may read the circle's data. */
val MemberRole?.canRead: Boolean get() = can(CircleAction.READ)

/** Owners and caregivers may edit patient data. */
val MemberRole?.canWrite: Boolean get() = can(CircleAction.WRITE)

/** Only owners may invite, remove members and delete data. */
val MemberRole?.canManage: Boolean get() = can(CircleAction.MANAGE)
