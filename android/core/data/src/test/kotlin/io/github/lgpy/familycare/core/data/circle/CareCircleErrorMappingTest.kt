package io.github.lgpy.familycare.core.data.circle

import io.github.lgpy.familycare.core.data.sync.UploadFailure
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreField
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The error mapping of the online care-circle calls.
 *
 * The inputs are what PostgREST reports for the codes the M2 migration raises;
 * the outputs are the app's own sealed type, so no server message can reach a
 * screen.
 */
class CareCircleErrorMappingTest {

    private fun error(
        status: Int?,
        postgresCode: String?,
        message: String? = null,
        call: RpcCall = RpcCall.CREATE_CIRCLE,
    ): CoreError = careCircleErrorOf(
        failure = UploadFailure(status, postgresCode),
        message = message,
        call = call,
    )

    @Test
    fun `no response at all is offline`() {
        assertEquals(CoreError.Offline, error(status = null, postgresCode = null))
    }

    @Test
    fun `an invalid invitation code is a code error`() {
        assertEquals(
            CoreError.InvalidCode,
            error(400, "22023", "invitation is not valid", RpcCall.ACCEPT_INVITATION),
        )
    }

    @Test
    fun `a wrong invited email is recognised`() {
        assertEquals(
            CoreError.EmailMismatch,
            error(
                403,
                "42501",
                "invitation was issued for a different email address",
                RpcCall.ACCEPT_INVITATION,
            ),
        )
    }

    @Test
    fun `an unconfirmed email is recognised`() {
        assertEquals(
            CoreError.EmailNotConfirmed,
            error(
                403,
                "42501",
                "confirm your email address before accepting an invitation",
                RpcCall.ACCEPT_INVITATION,
            ),
        )
    }

    @Test
    fun `other privilege failures are not allowed`() {
        assertEquals(
            CoreError.NotAllowed,
            error(
                403,
                "42501",
                "only a circle owner can invite members",
                RpcCall.CREATE_INVITATION,
            ),
        )
        assertEquals(
            CoreError.NotAllowed,
            error(403, "42501", "not authenticated", RpcCall.CREATE_CIRCLE),
        )
    }

    @Test
    fun `the last owner is recognised as such`() {
        assertEquals(
            CoreError.LastOwner,
            error(
                409,
                "23514",
                "cannot remove the last owner of a circle",
                RpcCall.REMOVE_MEMBER,
            ),
        )
    }

    @Test
    fun `a used invitation cannot be revoked`() {
        assertEquals(
            CoreError.AlreadyAccepted,
            error(400, "22023", "invitation was already accepted", RpcCall.REVOKE_INVITATION),
        )
    }

    @Test
    fun `an unknown row is not found`() {
        assertEquals(
            CoreError.NotFound,
            error(404, "P0002", "membership not found", RpcCall.REMOVE_MEMBER),
        )
        assertEquals(
            CoreError.NotFound,
            error(404, null, null, RpcCall.LIST_INVITATIONS),
        )
    }

    @Test
    fun `a local input problem names the field`() {
        assertEquals(
            CoreError.Invalid(setOf(CoreField.CIRCLE_NAME)),
            error(400, "22023", "circle name must be 1-100 characters", RpcCall.CREATE_CIRCLE),
        )
        assertEquals(
            CoreError.Invalid(setOf(CoreField.INVITATION_EMAIL)),
            error(
                400,
                "22023",
                "invitation email is not a valid address",
                RpcCall.CREATE_INVITATION,
            ),
        )
    }

    @Test
    fun `a policy refusal without a SQLSTATE is rejected`() {
        assertEquals(
            CoreError.Rejected,
            error(400, null, "some new server message", RpcCall.CREATE_INVITATION),
        )
    }

    @Test
    fun `a server error is unknown`() {
        assertEquals(CoreError.Unknown, error(500, null, "boom"))
        assertEquals(CoreError.Unknown, error(503, "XX000", "boom"))
    }
}
