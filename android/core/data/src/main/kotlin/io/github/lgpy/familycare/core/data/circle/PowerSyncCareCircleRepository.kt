package io.github.lgpy.familycare.core.data.circle

import com.powersync.PowerSyncDatabase
import com.powersync.db.getString
import com.powersync.db.getStringOptional
import io.github.lgpy.familycare.core.data.sync.UploadFailure
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.CareCircle
import io.github.lgpy.familycare.core.domain.circle.CareCircleId
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.CircleMember
import io.github.lgpy.familycare.core.domain.circle.CircleSetupState
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreField
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.Invitation
import io.github.lgpy.familycare.core.domain.circle.InvitationId
import io.github.lgpy.familycare.core.domain.circle.InvitationRules
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.MembershipId
import io.github.lgpy.familycare.core.domain.sync.SyncState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * [CareCircleRepository] over the local, encrypted PowerSync database and the
 * `core.*` RPCs.
 *
 * Reads are local SQL, so they work in airplane mode and update by themselves
 * when a sync brings a new circle, membership or name. The online calls only
 * change rows on the server; [setupState] moves to [CircleSetupState.HasCircle]
 * when the resulting rows arrive, which is what keeps "create circle" from
 * needing a manual refresh.
 *
 * Nothing here logs: no email, no invitation code and no patient data
 * (security-and-privacy §4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PowerSyncCareCircleRepository(
    private val database: PowerSyncDatabase,
    private val authState: StateFlow<AuthState>,
    syncState: StateFlow<SyncState>,
    private val rpc: CareCircleRpc,
) : CareCircleRepository {

    /** The signed-in user id, or null; distinctUntilChanged keeps the watchers stable. */
    private val signedInUserId: Flow<String?> = authState
        .map { (it as? AuthState.SignedIn)?.userId }
        .distinctUntilChanged()

    override val circle: Flow<CareCircle?> = signedInUserId.flatMapLatest { userId ->
        if (userId == null) {
            flowOf(null)
        } else {
            database.watch(SELECT_CIRCLE, listOf(userId)) { cursor ->
                CareCircle(
                    id = CareCircleId(cursor.getString("id")),
                    name = cursor.getString("name"),
                )
            }.map { it.firstOrNull() }
        }
    }

    override val myRole: Flow<MemberRole?> = signedInUserId.flatMapLatest { userId ->
        if (userId == null) {
            flowOf(null)
        } else {
            database.watch(SELECT_MY_ROLE, listOf(userId)) { cursor -> cursor.toRoleRow() }
                .map { rows -> rows.firstOrNull()?.toRole() }
        }
    }

    override val members: Flow<List<CircleMember>> = signedInUserId.flatMapLatest { userId ->
        if (userId == null) {
            flowOf(emptyList())
        } else {
            database.watch(SELECT_MEMBERS, listOf(userId)) { cursor -> cursor.toMemberRow() }
                .map { rows -> rows.mapNotNull { it.toCircleMember(userId) } }
        }
    }

    override val setupState: Flow<CircleSetupState> =
        combine(circle, myRole, syncState, authState) { circle, role, sync, auth ->
            when {
                // Nothing can be said before the first full download: the local
                // tables are empty for a brand-new user *and* for a returning one.
                auth !is AuthState.SignedIn -> CircleSetupState.NotSyncedYet
                sync.hasSynced != true -> CircleSetupState.NotSyncedYet
                circle != null && role != null -> CircleSetupState.HasCircle(circle, role)
                else -> CircleSetupState.NoCircle
            }
        }

    override suspend fun createCircle(name: String): CoreResult<CareCircleId> {
        val normalized = CareCircle.normalizeName(name)
        if (!CareCircle.isValidName(normalized)) {
            return CoreResult.Failure(CoreError.Invalid(setOf(CoreField.CIRCLE_NAME)))
        }

        return runRpc(RpcCall.CREATE_CIRCLE) {
            CareCircleId(rpc.createCircle(normalized))
        }
    }

    override suspend fun acceptInvitation(code: String): CoreResult<CareCircleId> {
        if (!InvitationRules.isValidCode(code)) {
            return CoreResult.Failure(CoreError.InvalidCode)
        }

        return runRpc(RpcCall.ACCEPT_INVITATION) {
            CareCircleId(rpc.acceptInvitation(InvitationRules.normalizeCode(code)))
        }
    }

    override suspend fun createInvitation(
        email: String,
        role: MemberRole,
    ): CoreResult<String> {
        val normalized = InvitationRules.normalizeEmail(email)
        if (!InvitationRules.isValidEmail(normalized)) {
            return CoreResult.Failure(CoreError.Invalid(setOf(CoreField.INVITATION_EMAIL)))
        }
        if (role !in MemberRole.invitables) {
            return CoreResult.Failure(CoreError.NotAllowed)
        }

        val circleId = currentCircleId()
            ?: return CoreResult.Failure(CoreError.NoCircle)

        return runRpc(RpcCall.CREATE_INVITATION) {
            rpc.createInvitation(circleId.value, normalized, role)
        }
    }

    override suspend fun listPendingInvitations(): CoreResult<List<Invitation>> {
        val circleId = currentCircleId()
            ?: return CoreResult.Failure(CoreError.NoCircle)

        return runRpc(RpcCall.LIST_INVITATIONS) {
            rpc.listInvitations(circleId.value).filter { it.isPending() }
        }
    }

    override suspend fun revokeInvitation(invitationId: InvitationId): CoreResult<Unit> =
        runRpc(RpcCall.REVOKE_INVITATION) {
            rpc.revokeInvitation(invitationId.value)
        }

    override suspend fun removeMember(membershipId: MembershipId): CoreResult<Unit> =
        runRpc(RpcCall.REMOVE_MEMBER) {
            rpc.removeMember(membershipId.value)
        }

    /**
     * The circle the online calls act on: the same "oldest membership" rule as
     * the flows. Null when the user is in none.
     */
    private suspend fun currentCircleId(): CareCircleId? {
        val userId = signedInUserId.first() ?: return null

        return database.getOptional(SELECT_CURRENT_CIRCLE_ID, listOf(userId)) { cursor ->
            cursor.getString("circle_id")
        }?.let(::CareCircleId)
    }

    /**
     * Runs one RPC and turns any failure into a [CoreError]. Cancellation is
     * rethrown so leaving a screen does not cancel the caller's scope by
     * accident; every other exception becomes a value.
     */
    private suspend fun <T> runRpc(
        call: RpcCall,
        block: suspend () -> T,
    ): CoreResult<T> = try {
        CoreResult.Success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        CoreResult.Failure(careCircleErrorOf(UploadFailure.of(error), error.message, call))
    }
}
