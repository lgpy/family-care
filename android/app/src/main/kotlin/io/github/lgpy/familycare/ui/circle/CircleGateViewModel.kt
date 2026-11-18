package io.github.lgpy.familycare.ui.circle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.domain.circle.CareCircle
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.CircleSetupState
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreField
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.InvitationRules
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.sync.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The three states the gate can show (docs/domain/care-circle.md, M2). */
sealed interface CircleGateUiState {

    /** The first sync has not finished; [offline] adds the "connect" hint. */
    data class Loading(val offline: Boolean = false) : CircleGateUiState

    /** The local data is current and the user belongs to no circle. */
    data object NoCircle : CircleGateUiState

    /** The user belongs to [circle] with [role]: the app shell can be shown. */
    data class HasCircle(val circle: CareCircle, val role: MemberRole) : CircleGateUiState
}

/** What went wrong with "create circle" or "join with a code", in the app's terms. */
enum class CircleGateError {
    InvalidCode,
    EmailMismatch,
    EmailNotConfirmed,
    InvalidName,
    Offline,
    NotAllowed,
    Unknown,
}

/**
 * The form state of the gate: one action at a time, with the last error.
 *
 * [busy] stays true after a successful create or join, because the circle only
 * appears when its rows finish downloading; the flows then replace this screen
 * with the app shell.
 */
data class CircleGateActionState(
    val busy: Boolean = false,
    val error: CircleGateError? = null,
)

/**
 * Drives the care-circle gate that sits between sign-in and the app shell.
 *
 * [uiState] is a straight mapping of [CareCircleRepository.setupState]: the app
 * never treats "no rows yet" as "no family". When a membership is removed the
 * repository moves back to [CircleSetupState.NoCircle] and the gate shows the
 * create/join screen again, without any screen refreshing anything.
 *
 * The two actions are online writes ([CareCircleRepository.createCircle] and
 * [CareCircleRepository.acceptInvitation]); failures are values, so [CoreError]
 * becomes a [CircleGateError] the screen localizes.
 */
class CircleGateViewModel(
    private val careCircleRepository: CareCircleRepository,
    private val authRepository: AuthRepository,
    syncState: Flow<SyncState>,
) : ViewModel() {

    val uiState: StateFlow<CircleGateUiState> = combine(
        careCircleRepository.setupState,
        syncState,
    ) { setup, sync ->
        when (setup) {
            CircleSetupState.NotSyncedYet ->
                CircleGateUiState.Loading(offline = !sync.connected)

            CircleSetupState.NoCircle -> CircleGateUiState.NoCircle

            is CircleSetupState.HasCircle ->
                CircleGateUiState.HasCircle(setup.circle, setup.role)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CircleGateUiState.Loading(),
    )

    private val _actionState = MutableStateFlow(CircleGateActionState())
    val actionState: StateFlow<CircleGateActionState> = _actionState.asStateFlow()

    /** Creates a circle and the user's owner membership (needs a connection). */
    fun createCircle(name: String) {
        if (_actionState.value.busy) return

        if (!CareCircle.isValidName(name)) {
            _actionState.value = CircleGateActionState(error = CircleGateError.InvalidName)
            return
        }

        _actionState.value = CircleGateActionState(busy = true)
        viewModelScope.launch {
            when (val result = careCircleRepository.createCircle(name)) {
                // Keep "working": the gate closes when the circle row syncs.
                is CoreResult.Success -> _actionState.value = CircleGateActionState(busy = true)
                is CoreResult.Failure ->
                    _actionState.value = CircleGateActionState(error = result.error.toGateError())
            }
        }
    }

    /** Accepts an invite code, normalised exactly like the server does. */
    fun joinWithCode(code: String) {
        if (_actionState.value.busy) return

        if (!InvitationRules.isValidCode(code)) {
            _actionState.value = CircleGateActionState(error = CircleGateError.InvalidCode)
            return
        }

        _actionState.value = CircleGateActionState(busy = true)
        viewModelScope.launch {
            val result = careCircleRepository.acceptInvitation(InvitationRules.normalizeCode(code))
            when (result) {
                is CoreResult.Success -> _actionState.value = CircleGateActionState(busy = true)
                is CoreResult.Failure ->
                    _actionState.value = CircleGateActionState(error = result.error.toGateError())
            }
        }
    }

    /** Dismisses the last message without leaving the gate. */
    fun dismissError() {
        _actionState.update { it.copy(error = null) }
    }

    /**
     * Leaves the signed-in account, so an invitation for another address can be
     * accepted with the Google account it was sent to.
     */
    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }
}

/**
 * The [CoreError] to [CircleGateError] mapping, in one place so the gate and its
 * unit test agree on what each failure means.
 */
fun CoreError.toGateError(): CircleGateError = when (this) {
    CoreError.InvalidCode -> CircleGateError.InvalidCode
    CoreError.EmailMismatch -> CircleGateError.EmailMismatch
    CoreError.EmailNotConfirmed -> CircleGateError.EmailNotConfirmed
    CoreError.Offline -> CircleGateError.Offline
    CoreError.NotAllowed -> CircleGateError.NotAllowed
    is CoreError.Invalid ->
        if (CoreField.CIRCLE_NAME in fields) CircleGateError.InvalidName else CircleGateError.Unknown

    CoreError.NoCircle, CoreError.LastOwner, CoreError.AlreadyAccepted, CoreError.NotFound,
    CoreError.Rejected, CoreError.Unknown -> CircleGateError.Unknown
}
