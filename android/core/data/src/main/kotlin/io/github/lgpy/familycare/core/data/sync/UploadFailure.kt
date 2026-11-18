package io.github.lgpy.familycare.core.data.sync

import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.lgpy.familycare.core.domain.sync.RejectionReason

/**
 * Why an upload attempt failed, in the terms the retry policy needs.
 *
 * Both values are optional: a network failure has neither, a PostgREST/Postgres
 * failure has an HTTP status, and a database error also carries the SQLSTATE
 * code PostgREST returns in its JSON error body.
 */
internal data class UploadFailure(
    val statusCode: Int?,
    val postgresCode: String?,
) {
    /** Whether retrying cannot help, so the queue must move on. */
    val isPermanent: Boolean get() = isPermanentUploadFailure(statusCode, postgresCode)

    /**
     * The coarse reason a *permanent* failure is reported to the user as.
     *
     * Only meaningful when [isPermanent] is true: a policy denial is "not
     * allowed", a data or constraint violation means the values themselves are
     * invalid, and everything else permanent is a plain "other". Callers must
     * not call this for a transient failure.
     */
    fun rejectionReason(): RejectionReason = when {
        postgresCode == RLS_DENIED -> RejectionReason.NOT_ALLOWED
        postgresCode != null &&
            postgresCode.length == SQLSTATE_LENGTH &&
            postgresCode.take(2) in PERMANENT_SQLSTATE_CLASSES -> RejectionReason.INVALID_DATA

        else -> RejectionReason.OTHER
    }

    companion object {
        /**
         * Reads the code out of the exceptions supabase-kt throws. Anything that
         * is not a REST response (network, TLS, timeouts raised by Ktor) has
         * neither a status nor a code and is treated as transient.
         */
        fun of(error: Throwable): UploadFailure = when (error) {
            is PostgrestRestException -> UploadFailure(error.statusCode, error.code)
            is RestException -> UploadFailure(error.statusCode, null)
            else -> UploadFailure(null, null)
        }
    }
}

/**
 * The retry policy for uploads (M1, decision 5 of the sync task).
 *
 * Permanent failures are the ones a retry would repeat forever: the row is
 * rejected by a policy or a constraint, or the request itself is malformed.
 * For those the transaction is completed (so one bad row cannot wedge the whole
 * queue) and the refusal is surfaced through `SyncState.rejectedUpload`, which
 * is what the user is told about.
 *
 * Everything else is transient and is rethrown so PowerSync retries after its
 * delay: no response at all (offline), 5xx, and the client errors that mean
 * "not now": 401 (the token is being refreshed), 408 (timeout) and 429 (rate
 * limited).
 */
internal fun isPermanentUploadFailure(
    statusCode: Int?,
    postgresCode: String?,
): Boolean {
    if (postgresCode != null) {
        if (postgresCode == RLS_DENIED) return true
        // Class 22 = data exception, class 23 = integrity constraint violation.
        if (postgresCode.length == SQLSTATE_LENGTH && postgresCode.take(2) in PERMANENT_SQLSTATE_CLASSES) {
            return true
        }
    }

    return statusCode != null &&
        statusCode in CLIENT_ERROR_RANGE &&
        statusCode !in TRANSIENT_CLIENT_ERRORS
}

/** `insufficient_privilege`: the RLS policy refused the row. */
private const val RLS_DENIED = "42501"

private const val SQLSTATE_LENGTH = 5
private val PERMANENT_SQLSTATE_CLASSES = setOf("22", "23")
private val CLIENT_ERROR_RANGE = 400..499
private val TRANSIENT_CLIENT_ERRORS = setOf(401, 408, 429)
