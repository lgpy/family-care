package io.github.lgpy.familycare.ui.circle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.CareCircle
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.CircleSetupState
import io.github.lgpy.familycare.core.domain.circle.CoreError
import io.github.lgpy.familycare.core.domain.circle.CoreField
import io.github.lgpy.familycare.core.domain.circle.CoreResult
import io.github.lgpy.familycare.core.domain.circle.InvitationRules
import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.sync.SyncState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
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
 * with the app shell. It is cleared once the circle is there, and when the
 * signed-in user changes (the view model outlives a sign-out).
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
 * The view model lives as long as the activity, so everything it keeps about
 * "this account" is tied to the signed-in user id: a new user restarts [uiState]
 * from [CircleGateUiState.Loading] (never the previous account's circle) and
 * clears [actionState].
 *
 * The two actions are online writes ([CareCircleRepository.createCircle] and
 * [CareCircleRepository.acceptInvitation]); failures are values, so [CoreError]
 * becomes a [CircleGateError] the screen localizes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CircleGateViewModel(
    private val careCircleRepository: CareCircleRepository,
    private val authRepository: AuthRepository,
    syncState: Flow<SyncState>,
) : ViewModel() {

    /** The signed-in user, null when nobody is. */
    private val signedInUserId: Flow<String?> = authRepository.state
        .map { (it as? AuthState.SignedIn)?.userId }
        .distinctUntilChanged()

    val uiState: StateFlow<CircleGateUiState> = signedInUserId.flatMapLatest {
        combine(careCircleRepository.setupState, syncState) { setup, sync ->
            when (setup) {
                CircleSetupState.NotSyncedYet ->
                    CircleGateUiState.Loading(offline = !sync.connected)

                CircleSetupState.NoCircle -> CircleGateUiState.NoCircle

                is CircleSetupState.HasCircle ->
                    CircleGateUiState.HasCircle(setup.circle, setup.role)
            }
            // A new account starts from "loading": the shared state would
            // otherwise replay the previous account's circle until the first
            // value of the new one.
        }.onStart { emit(CircleGateUiState.Loading()) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CircleGateUiState.Loading(),
    )

    private val _actionState = MutableStateFlow(CircleGateActionState())
    val actionState: StateFlow<CircleGateActionState> = _actionState.asStateFlow()

    /** Counts the accounts seen; an action that started under another one is dropped. */
    private var accountGeneration = 0

    /** Whether the signed-in account's circle rows are already here. */
    private var hasCircle = false

    init {
        // Kept for the view model's whole life (not only while the gate is on
        // screen), because it is what ends "Working…" after a success.
        viewModelScope.launch {
            signedInUserId.collectLatest {
                accountGeneration += 1
                hasCircle = false
                _actionState.value = CircleGateActionState()

                careCircleRepository.setupState.collect { setup ->
                    hasCircle = setup is CircleSetupState.HasCircle
                    if (hasCircle) _actionState.value = CircleGateActionState()
                }
            }
        }
    }

    /** Creates a circle and the user's owner membership (needs a connection). */
    fun createCircle(name: String) {
        if (_actionState.value.busy) return

        if (!CareCircle.isValidName(name)) {
            _actionState.value = CircleGateActionState(error = CircleGateError.InvalidName)
            return
        }

        runAction { careCircleRepository.createCircle(name) }
    }

    /** Accepts an invite code, normalised exactly like the server does. */
    fun joinWithCode(code: String) {
        if (_actionState.value.busy) return

        if (!InvitationRules.isValidCode(code)) {
            _actionState.value = CircleGateActionState(error = CircleGateError.InvalidCode)
            return
        }

        runAction { careCircleRepository.acceptInvitation(InvitationRules.normalizeCode(code)) }
    }

    /**
     * Runs an online write with [CircleGateActionState.busy] set. A success keeps
     * "working" until the circle rows sync (unless they already did); a result
     * that arrives after the account changed belongs to nobody and is dropped.
     */
    private fun runAction(call: suspend () -> CoreResult<*>) {
        val generation = accountGeneration
        _actionState.value = CircleGateActionState(busy = true)
        viewModelScope.launch {
            val result = call()
            if (generation != accountGeneration) return@launch

            _actionState.value = when (result) {
                is CoreResult.Success -> CircleGateActionState(busy = !hasCircle)
                is CoreResult.Failure -> CircleGateActionState(error = result.error.toGateError())
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
