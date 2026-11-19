package io.github.lgpy.familycare.core.domain.circle

/**
 * What a core repository call returns.
 *
 * Failures are values, not exceptions, so a screen can react to
 * [CoreError.EmailMismatch] or [CoreError.LastOwner] without parsing a message:
 * server text, SQL and row data never reach the UI (security-and-privacy §4).
 */
sealed interface CoreResult<out T> {

    /** The call succeeded; [value] is its result. */
    data class Success<T>(val value: T) : CoreResult<T>

    /** The call failed; [error] says why in the app's own terms. */
    data class Failure(val error: CoreError) : CoreResult<Nothing>
}

/** The result of a call that has nothing to return (revoke, remove, update). */
typealias CoreUnitResult = CoreResult<Unit>

/** Why a core repository call failed. */
sealed interface CoreError {

    /** The user belongs to no circle, so there is nothing to act on. */
    data object NoCircle : CoreError

    /**
     * The invitation code is unknown, expired, already used or revoked. The
     * server deliberately reports all four the same way so a caller cannot
     * discover which codes exist.
     */
    data object InvalidCode : CoreError

    /**
     * The invitation was issued for a different email address. The user has to
     * sign in with the Google account the invitation was sent to.
     */
    data object EmailMismatch : CoreError

    /** The signed-in account's email address is not confirmed yet. */
    data object EmailNotConfirmed : CoreError

    /**
     * The server refused the operation for this role (for example, a caregiver
     * trying to invite) or the session is not usable.
     */
    data object NotAllowed : CoreError

    /** Removing or demoting this member would leave the circle without an owner. */
    data object LastOwner : CoreError

    /** The invitation had already been accepted and can no longer be revoked. */
    data object AlreadyAccepted : CoreError

    /** The row the call addressed does not exist (anymore). */
    data object NotFound : CoreError

    /**
     * A local rule rejected the input before anything was sent. [fields] tells
     * a form which inputs to mark; see [CoreField].
     */
    data class Invalid(val fields: Set<CoreField> = emptySet()) : CoreError

    /** No usable response: offline, TLS, DNS, timeout. The write stays queued locally. */
    data object Offline : CoreError

    /** The server understood the call and refused it (a constraint or policy). */
    data object Rejected : CoreError

    /** Anything else. The UI only offers "try again", never the server's text. */
    data object Unknown : CoreError
}

/**
 * The inputs a [CoreError.Invalid] can point at, so a form can highlight the
 * offending field without a message.
 */
enum class CoreField {
    CIRCLE_NAME,
    PROFILE_DISPLAY_NAME,
    PROFILE_PREFERRED_LANGUAGE,
    INVITATION_EMAIL,
    PATIENT_DISPLAY_NAME,
    PATIENT_REMINDER_POLICY,
    PATIENT_PRIMARY_CAREGIVER,
    PATIENT_LINKED_USER,
    PATIENT_ESCALATION_DELAY,
    PATIENT_MISSED_WINDOW,
    PATIENT_BIRTH_YEAR,
    PATIENT_AGREEMENT_NOTE,
}
