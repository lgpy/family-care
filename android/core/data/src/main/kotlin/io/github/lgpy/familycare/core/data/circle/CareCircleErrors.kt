package io.github.lgpy.familycare.core.data.circle

import io.github.lgpy.familycare.core.data.sync.UploadFailure
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreField

/**
 * The online calls of a care circle, one method per `core.*` RPC, throwing on
 * failure. The repository wraps each call into a [CoreError], which is what
 * makes the mapping testable without a network.
 *
 * Codes are the ones the M2 migration raises:
 * - `22023` invalid parameter (`invitation is not valid`, email, circle name);
 * - `42501` insufficient privilege (not an owner, wrong email, unconfirmed
 *   email, not signed in);
 * - `23514` check violation (`cannot remove the last owner`);
 * - `P0002` no data found (unknown invitation or membership).
 */
internal enum class RpcCall {
    CREATE_CIRCLE,
    ACCEPT_INVITATION,
    CREATE_INVITATION,
    LIST_INVITATIONS,
    REVOKE_INVITATION,
    REMOVE_MEMBER,

    /** `core.erase_patient` (D30): owner only, and gone is gone. */
    ERASE_PATIENT,
}

/**
 * Turns a failed RPC into the app's own error vocabulary.
 *
 * No message, SQL or row data is passed on: the returned [CoreError] is a fixed
 * value the UI can translate (security-and-privacy §4). The message text is only
 * inspected here to tell apart the cases the server deliberately reports with
 * the same SQLSTATE: a wrong email, an unconfirmed email and "not an owner" are
 * all `42501`. Those messages are ours, defined in
 * `backend/supabase/migrations/20261118120000_core_care_circle.sql`; anything
 * unrecognised falls back to the safe, generic value.
 */
internal fun careCircleErrorOf(
    failure: UploadFailure,
    message: String?,
    call: RpcCall,
): CoreError {
    val status = failure.statusCode
    val code = failure.postgresCode
    val text = message.orEmpty().lowercase()

    // Neither a status nor a SQLSTATE: no response at all (offline, TLS, DNS,
    // timeout). The RPCs are server-only, so this is the only "retry later" case.
    if (status == null && code == null) return CoreError.Offline

    return when {
        code == INVALID_PARAMETER -> invalidParameter(call, text)
        code == INSUFFICIENT_PRIVILEGE -> insufficientPrivilege(text)
        code == CHECK_VIOLATION ->
            if (text.contains("last owner")) CoreError.LastOwner else CoreError.Rejected
        code == NO_DATA_FOUND -> CoreError.NotFound
        status == HTTP_NOT_FOUND -> CoreError.NotFound
        status != null && status in CLIENT_ERROR_RANGE -> CoreError.Rejected
        else -> CoreError.Unknown
    }
}

private fun invalidParameter(call: RpcCall, message: String): CoreError = when (call) {
    // Unknown, expired, used and revoked codes all look the same on purpose.
    RpcCall.ACCEPT_INVITATION -> CoreError.InvalidCode
    RpcCall.CREATE_CIRCLE -> CoreError.Invalid(setOf(CoreField.CIRCLE_NAME))
    // The only 22023 create_invitation raises for valid input we send is email.
    RpcCall.CREATE_INVITATION -> CoreError.Invalid(setOf(CoreField.INVITATION_EMAIL))
    RpcCall.REVOKE_INVITATION ->
        if (message.contains("already accepted")) CoreError.AlreadyAccepted else CoreError.Rejected
    else -> CoreError.Rejected
}

private fun insufficientPrivilege(message: String): CoreError = when {
    message.contains("confirm your email") -> CoreError.EmailNotConfirmed
    message.contains("email address") -> CoreError.EmailMismatch
    else -> CoreError.NotAllowed
}

/** `invalid_parameter_value`, e.g. "invitation is not valid". */
private const val INVALID_PARAMETER = "22023"

/** `insufficient_privilege`: not an owner, wrong email, or no session. */
private const val INSUFFICIENT_PRIVILEGE = "42501"

/** `check_violation`, e.g. "cannot remove the last owner of a circle". */
private const val CHECK_VIOLATION = "23514"

/** `no_data_found` from the RPCs that look a row up by id. */
private const val NO_DATA_FOUND = "P0002"

private const val HTTP_NOT_FOUND = 404
private val CLIENT_ERROR_RANGE = 400..499
