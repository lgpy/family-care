package io.github.lgpy.familycare.core.domain.circle

import kotlinx.coroutines.flow.Flow

/**
 * The care circle: what the signed-in user belongs to, plus the online calls
 * that change membership.
 *
 * **Reads** come from the encrypted local database and never wait for the
 * network (D3), so every `Flow` behaves the same in airplane mode:
 *
 * | flow | emits |
 * |---|---|
 * | [setupState] | not yet synced / no circle / the circle and my role |
 * | [circle] | the current circle, or null |
 * | [myRole] | my role in it, or null |
 * | [members] | every member of it, with display names |
 *
 * **Writes** are the `core.*` RPCs. They need a connection; when one of them
 * succeeds, the new circle/membership rows arrive through the sync stream and
 * the flows above update by themselves — no screen has to refresh anything.
 *
 * ## Which circle is "the" circle
 *
 * The MVP gives a user at most one circle. If the server holds more than one
 * (for example after accepting an invitation while already owning a circle),
 * the **oldest membership** wins, with the membership id as a deterministic
 * tie-break. Every flow here and [PatientRepository] uses the same rule.
 *
 * ## The setup screen
 *
 * ```kotlin
 * when (val state = repository.setupState.collectAsState().value) {
 *     CircleSetupState.NotSyncedYet -> Loading()
 *     CircleSetupState.NoCircle -> CreateOrJoin()
 *     is CircleSetupState.HasCircle -> Today(state.circle, state.role)
 * }
 * ```
 *
 * The implementation lives in `core/data`; this interface keeps PowerSync,
 * Supabase and SQL out of every other module.
 */
interface CareCircleRepository {

    /** Not synced yet / no circle / the circle and the user's role in it. */
    val setupState: Flow<CircleSetupState>

    /**
     * The current circle, or null when the user has none (or is signed out, or
     * the first sync has not finished). Emits again when the row changes.
     */
    val circle: Flow<CareCircle?>

    /**
     * The signed-in user's role in the current circle, or null when they are in
     * no circle. Combine it with [MemberRole.can] / [canWrite] / [canManage] to
     * decide what a screen may offer.
     */
    val myRole: Flow<MemberRole?>

    /**
     * The members of the current circle, owner first and then by display name.
     * [CircleMember.isMe] marks the signed-in user. Empty until the membership
     * rows sync.
     */
    val members: Flow<List<CircleMember>>

    /**
     * Creates a circle and the caller's owner membership, atomically (the
     * `core.create_circle` RPC).
     *
     * The new circle arrives through the next sync; [setupState] moves from
     * [CircleSetupState.NoCircle] to [CircleSetupState.HasCircle] on its own.
     * Works offline only in the sense that the attempt fails with
     * [CoreError.Offline] — creation needs the server, because the membership
     * may not be written by the client.
     *
     * @param name 1..100 characters after trimming ([CareCircle.isValidName]).
     * @return the new circle's id, or [CoreError.Invalid] with
     *   [CoreField.CIRCLE_NAME], [CoreError.NotAllowed] (no session) or a
     *   transport error.
     */
    suspend fun createCircle(name: String): CoreResult<CareCircleId>

    /**
     * Accepts an invitation code and joins that circle (the
     * `core.accept_invitation` RPC).
     *
     * The code may be typed with spaces, dashes and lower case; it is normalised
     * with [InvitationRules.normalizeCode] exactly like the server does. The
     * signed-in account's confirmed email must be the invited address.
     *
     * @return the joined circle's id, or [CoreError.InvalidCode] (unknown,
     *   expired, used or revoked — the server does not say which),
     *   [CoreError.EmailMismatch], [CoreError.EmailNotConfirmed],
     *   [CoreError.NotAllowed], [CoreError.Offline].
     */
    suspend fun acceptInvitation(code: String): CoreResult<CareCircleId>

    /**
     * Invites an email address to the current circle (the `core.create_invitation`
     * RPC). Owner only.
     *
     * The returned code exists **only** in this result: the server stores a hash.
     * Show it once and let the user share it (WhatsApp, any app). The invitation
     * expires after seven days and works once.
     *
     * @param email Any address; it is normalised with
     *   [InvitationRules.normalizeEmail] and must pass
     *   [InvitationRules.isValidEmail].
     * @param role [MemberRole.CAREGIVER] or [MemberRole.VIEWER]; owner cannot be
     *   invited.
     * @return the display code (`XXXXXXXX-XXXXXXXX-XXXXXXXX-XXXXXXXX`), or
     *   [CoreError.Invalid] with [CoreField.INVITATION_EMAIL], [CoreError.NotAllowed]
     *   (not an owner), [CoreError.NoCircle] or a transport error.
     */
    suspend fun createInvitation(
        email: String,
        role: MemberRole = MemberRole.CAREGIVER,
    ): CoreResult<String>

    /**
     * The current circle's invitations that can still be accepted, newest first
     * (online PostgREST read, owner only — invitations are not synced).
     *
     * The plaintext codes are not available here; only [Invitation] metadata.
     *
     * @return the pending invitations, or [CoreError.NotAllowed] for a
     *   non-owner, [CoreError.NoCircle], [CoreError.Offline].
     */
    suspend fun listPendingInvitations(): CoreResult<List<Invitation>>

    /**
     * Cancels an unaccepted invitation (the `core.revoke_invitation` RPC).
     * Owner only; revoking twice is harmless.
     *
     * @return [CoreError.AlreadyAccepted] when the invitation was already used,
     *   [CoreError.NotFound], [CoreError.NotAllowed], [CoreError.Offline].
     */
    suspend fun revokeInvitation(invitationId: InvitationId): CoreResult<Unit>

    /**
     * Removes a member from the current circle (the `core.remove_member` RPC).
     * Owner only.
     *
     * Server access ends immediately; the removed member's phone loses the data
     * on its next sync (T7). The row disappears from [members] when the deletion
     * syncs.
     *
     * @return [CoreError.LastOwner] when this is the circle's only owner,
     *   [CoreError.NotFound], [CoreError.NotAllowed], [CoreError.Offline].
     */
    suspend fun removeMember(membershipId: MembershipId): CoreResult<Unit>
}
