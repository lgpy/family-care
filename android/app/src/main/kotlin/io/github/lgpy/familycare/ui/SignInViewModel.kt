package io.github.lgpy.familycare.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.domain.auth.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State of the sign-in screen itself. The session lives in [AuthState], which
 * this view model re-exposes so the screen and its unit tests read one thing.
 */
data class SignInUiState(
    /** A Google sheet is open or a token exchange is running. */
    val signingIn: Boolean = false,
    /** Show the localized "could not sign in" message. */
    val failed: Boolean = false,
)

/**
 * Drives the sign-in screen (D12).
 *
 * The Google credential step needs an Activity, so the screen obtains the ID
 * token and hands it over; this view model only owns the Supabase exchange and
 * the resulting screen state, which makes it testable with a fake repository.
 */
class SignInViewModel(private val authRepository: AuthRepository) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.state

    private val _uiState = MutableStateFlow(SignInUiState())
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    /** The button was tapped; the account sheet is about to open. */
    fun onSignInStarted() {
        if (_uiState.value.signingIn) return
        _uiState.value = SignInUiState(signingIn = true)
    }

    /** The user dismissed the sheet: return to the idle button, no message. */
    fun onCredentialCancelled() {
        _uiState.value = SignInUiState()
    }

    /** No Google credential was available (no account, no Play Services). */
    fun onCredentialFailed() {
        _uiState.value = SignInUiState(failed = true)
    }

    /** Exchanges the Google ID token for a Supabase session. */
    fun onIdTokenReceived(idToken: String, rawNonce: String) {
        _uiState.value = SignInUiState(signingIn = true)
        viewModelScope.launch {
            val result = authRepository.signInWithGoogleIdToken(idToken, rawNonce)
            _uiState.update { it.copy(signingIn = false, failed = result.isFailure) }
        }
    }

    fun onErrorDismissed() {
        _uiState.update { it.copy(failed = false) }
    }
}
