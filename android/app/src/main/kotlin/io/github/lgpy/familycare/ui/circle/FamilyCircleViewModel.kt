package io.github.lgpy.familycare.ui.circle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.CircleMember
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreField
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.Invitation
import io.github.lgpy.familycare.core.domain.circle.InvitationRules
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.canManage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why an action on the family screen failed, in the app's terms. */
enum class FamilyCircleError {
    InvalidEmail,
    InvalidCode,
    Offline,
    NotAllowed,
    LastOwner,
    AlreadyAccepted,
    NotFound,
    Unknown,
}

/** The plaintext code, which exists only in the result of [FamilyCircleViewModel.invite]. */
data class ShownInvite(
    val email: String,
    val code: String,
)

/**
 * State of the family circle screen.
 *
 * [members] and [circleName] come from the synced local database; [invitations]
 * is the one online read (invitations are not synced) and [loaded] is false
 * until the first emission of the local flows.
 */
data class FamilyCircleUiState(
    val circleName: String = "",
    val members: List<CircleMember> = emptyList(),
    val isOwner: Boolean = false,
    val loaded: Boolean = false,
    val invitations: List<Invitation> = emptyList(),
    val invitationsLoading: Boolean = false,
    val invitationsLoaded: Boolean = false,
    val invitationsError: FamilyCircleError? = null,
    val inviteBusy: Boolean = false,
    val inviteError: FamilyCircleError? = null,
    val shownInvite: ShownInvite? = null,
    val pendingRemoval: CircleMember? = null,
    val pendingRevoke: Invitation? = null,
    val actionError: FamilyCircleError? = null,
)

/**
 * Drives More → Family circle (docs/ux/screens.md §7).
 *
 * The member list is local and updates by itself. Everything an owner changes
 * is online: create an invitation, list the pending ones, revoke one, remove a
 * member. Each failure is a [FamilyCircleError], so an offline phone gets "no
 * connection" instead of a stack trace, and the invitation code is kept only in
 * [FamilyCircleUiState.shownInvite] until the dialog is dismissed — the server
 * stores a hash and can never show it again.
 */
class FamilyCircleViewModel(
    private val careCircleRepository: CareCircleRepository,
) : ViewModel() {

    private val local = MutableStateFlow(FamilyCircleUiState())

    val uiState: StateFlow<FamilyCircleUiState> = combine(
        careCircleRepository.circle,
        careCircleRepository.members,
        careCircleRepository.myRole,
        local,
    ) { circle, members, role, actions ->
        actions.copy(
            circleName = circle?.name.orEmpty(),
            members = members,
            isOwner = role.canManage,
            loaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FamilyCircleUiState(),
    )

    /** Fetches the pending invitations (online, owner only). */
    fun refreshInvitations() {
        if (!uiState.value.isOwner) return
        loadInvitations()
    }

    /** The fetch itself, also used after an invitation is created. */
    private fun loadInvitations() {
        if (local.value.invitationsLoading) return

        local.update { it.copy(invitationsLoading = true, invitationsError = null) }
        viewModelScope.launch {
            val result = careCircleRepository.listPendingInvitations()
            local.update {
                when (result) {
                    is CoreResult.Success -> it.copy(
                        invitations = result.value,
                        invitationsLoading = false,
                        invitationsLoaded = true,
                        invitationsError = null,
                    )

                    is CoreResult.Failure -> it.copy(
                        invitationsLoading = false,
                        invitationsLoaded = true,
                        invitationsError = result.error.toFamilyCircleError(),
                    )
                }
            }
        }
    }

    /**
     * Creates a caregiver invitation and exposes its code once.
     *
     * The code is formatted for display ([InvitationRules.formatCode]) and is
     * not stored anywhere else; [dismissInvite] is final.
     */
    fun invite(email: String) {
        if (!uiState.value.isOwner || uiState.value.inviteBusy) return

        val normalizedEmail = InvitationRules.normalizeEmail(email)
        if (!InvitationRules.isValidEmail(normalizedEmail)) {
            local.update { it.copy(inviteError = FamilyCircleError.InvalidEmail) }
            return
        }

        local.update { it.copy(inviteBusy = true, inviteError = null) }
        viewModelScope.launch {
            when (val result = careCircleRepository.createInvitation(
                email = normalizedEmail,
                role = MemberRole.CAREGIVER,
            )) {
                is CoreResult.Success -> {
                    local.update {
                        it.copy(
                            inviteBusy = false,
                            inviteError = null,
                            shownInvite = ShownInvite(
                                email = normalizedEmail,
                                code = InvitationRules.formatCode(result.value),
                            ),
                        )
                    }
                    loadInvitations()
                }

                is CoreResult.Failure -> local.update {
                    it.copy(
                        inviteBusy = false,
                        inviteError = result.error.toFamilyCircleError(),
                    )
                }
            }
        }
    }

    /** Closes the code dialog; the code cannot be shown again. */
    fun dismissInvite() {
        local.update { it.copy(shownInvite = null) }
    }

    /** Asks for confirmation before removing [member] (never the signed-in user). */
    fun requestRemoveMember(member: CircleMember) {
        if (!uiState.value.isOwner || member.isMe) return
        local.update { it.copy(pendingRemoval = member) }
    }

    fun cancelRemoveMember() {
        local.update { it.copy(pendingRemoval = null) }
    }

    /** Removes the member the confirmation dialog is about. */
    fun confirmRemoveMember() {
        val member = local.value.pendingRemoval ?: return
        local.update { it.copy(pendingRemoval = null, actionError = null) }
        viewModelScope.launch {
            when (val result = careCircleRepository.removeMember(member.membershipId)) {
                is CoreResult.Success -> Unit
                is CoreResult.Failure ->
                    local.update { it.copy(actionError = result.error.toFamilyCircleError()) }
            }
        }
    }

    /** Asks for confirmation before revoking [invitation]. */
    fun requestRevoke(invitation: Invitation) {
        if (!uiState.value.isOwner) return
        local.update { it.copy(pendingRevoke = invitation) }
    }

    fun cancelRevoke() {
        local.update { it.copy(pendingRevoke = null) }
    }

    /** Revokes the invitation the confirmation dialog is about. */
    fun confirmRevoke() {
        val invitation = local.value.pendingRevoke ?: return
        local.update { it.copy(pendingRevoke = null, actionError = null) }
        viewModelScope.launch {
            when (val result = careCircleRepository.revokeInvitation(invitation.id)) {
                is CoreResult.Success -> local.update { state ->
                    state.copy(
                        invitations = state.invitations.filterNot { it.id == invitation.id },
                    )
                }

                is CoreResult.Failure ->
                    local.update { it.copy(actionError = result.error.toFamilyCircleError()) }
            }
        }
    }

    fun dismissError() {
        local.update {
            it.copy(
                inviteError = null,
                invitationsError = null,
                actionError = null,
            )
        }
    }
}

/**
 * The [CoreError] to [FamilyCircleError] mapping used by every action on this
 * screen.
 */
fun CoreError.toFamilyCircleError(): FamilyCircleError = when (this) {
    is CoreError.Invalid ->
        if (CoreField.INVITATION_EMAIL in fields) {
            FamilyCircleError.InvalidEmail
        } else {
            FamilyCircleError.Unknown
        }

    CoreError.InvalidCode -> FamilyCircleError.InvalidCode
    CoreError.Offline -> FamilyCircleError.Offline
    CoreError.NotAllowed, CoreError.NoCircle -> FamilyCircleError.NotAllowed
    CoreError.LastOwner -> FamilyCircleError.LastOwner
    CoreError.AlreadyAccepted -> FamilyCircleError.AlreadyAccepted
    CoreError.NotFound -> FamilyCircleError.NotFound
    CoreError.EmailMismatch, CoreError.EmailNotConfirmed, CoreError.Rejected,
    CoreError.Unknown -> FamilyCircleError.Unknown
}
