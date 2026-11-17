package io.github.lgpy.familycare.core.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upload retry policy (M1, decision 5): which failures move the queue on and
 * which ones PowerSync should retry.
 *
 * Pure function, no network and no PowerSync: the codes are the ones the
 * database and PostgREST actually return.
 */
class UploadFailureTest {

    @Test
    fun rowLevelSecurityDenialIsPermanent() {
        // 42501 insufficient_privilege: the RLS policy refused the row.
        assertTrue(isPermanentUploadFailure(statusCode = 403, postgresCode = "42501"))
    }

    @Test
    fun dataAndConstraintViolationsArePermanent() {
        // 22P02 invalid_text_representation, 22001 string too long.
        assertTrue(isPermanentUploadFailure(statusCode = 400, postgresCode = "22P02"))
        assertTrue(isPermanentUploadFailure(statusCode = 400, postgresCode = "22001"))
        // 23502 not_null_violation, 23505 unique_violation.
        assertTrue(isPermanentUploadFailure(statusCode = 409, postgresCode = "23502"))
        assertTrue(isPermanentUploadFailure(statusCode = 409, postgresCode = "23505"))
    }

    @Test
    fun clientErrorsArePermanentExceptTheRetryableOnes() {
        assertTrue(isPermanentUploadFailure(statusCode = 400, postgresCode = null))
        assertTrue(isPermanentUploadFailure(statusCode = 403, postgresCode = null))
        assertTrue(isPermanentUploadFailure(statusCode = 404, postgresCode = null))
        assertTrue(isPermanentUploadFailure(statusCode = 422, postgresCode = null))
    }

    @Test
    fun authenticationTimeoutAndRateLimitAreRetried() {
        // 401: the access token is being refreshed.
        assertFalse(isPermanentUploadFailure(statusCode = 401, postgresCode = null))
        // 408: the request timed out.
        assertFalse(isPermanentUploadFailure(statusCode = 408, postgresCode = null))
        // 429: rate limited.
        assertFalse(isPermanentUploadFailure(statusCode = 429, postgresCode = null))
    }

    @Test
    fun serverAndNetworkFailuresAreRetried() {
        assertFalse(isPermanentUploadFailure(statusCode = 500, postgresCode = null))
        assertFalse(isPermanentUploadFailure(statusCode = 503, postgresCode = null))
        // No response at all: offline, TLS, DNS.
        assertFalse(isPermanentUploadFailure(statusCode = null, postgresCode = null))
    }

    @Test
    fun postgrestInternalCodesAreNotMistakenForSqlStates() {
        // PGRST116 ("no rows") is not a class 22/23 SQLSTATE, so it adds no
        // permanence of its own; only the HTTP status decides.
        assertFalse(isPermanentUploadFailure(statusCode = 500, postgresCode = "PGRST116"))
        assertFalse(isPermanentUploadFailure(statusCode = null, postgresCode = "PGRST116"))
    }

    @Test
    fun failureOfANetworkErrorHasNoCodes() {
        val failure = UploadFailure.of(java.io.IOException("no route to host"))

        assertFalse(failure.isPermanent)
    }
}
