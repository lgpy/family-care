package io.github.lgpy.familycare.ui

import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.domain.auth.AuthState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Sign-in screen logic against a fake repository: no Supabase, no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {

    private lateinit var repository: FakeAuthRepository
    private lateinit var viewModel: SignInViewModel

    @Before
    fun setUp() {
        // Eager main dispatcher: onIdTokenReceived's coroutine runs at once, so
        // each test can assert straight after the call.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeAuthRepository()
        viewModel = SignInViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun exposesTheRepositorySessionState() = runTest {
        assertEquals(AuthState.SignedOut, viewModel.authState.value)
    }

    @Test
    fun startsIdle() {
        assertFalse(viewModel.uiState.value.signingIn)
        assertFalse(viewModel.uiState.value.failed)
    }

    @Test
    fun tappingTheButtonShowsProgress() {
        viewModel.onSignInStarted()

        assertTrue(viewModel.uiState.value.signingIn)
        assertFalse(viewModel.uiState.value.failed)
    }

    @Test
    fun successfulTokenExchangeEndsProgressWithoutError() = runTest {
        repository.signInResult = Result.success(Unit)

        viewModel.onSignInStarted()
        viewModel.onIdTokenReceived(idToken = "google-id-token", rawNonce = "raw-nonce")

        assertFalse(viewModel.uiState.value.signingIn)
        assertFalse(viewModel.uiState.value.failed)
        assertEquals("google-id-token" to "raw-nonce", repository.lastSignIn)
        assertTrue(viewModel.authState.value is AuthState.SignedIn)
    }

    @Test
    fun failedTokenExchangeShowsTheErrorMessage() = runTest {
        repository.signInResult = Result.failure(IllegalStateException("invalid nonce"))

        viewModel.onSignInStarted()
        viewModel.onIdTokenReceived(idToken = "google-id-token", rawNonce = "bad-nonce")

        assertFalse(viewModel.uiState.value.signingIn)
        assertTrue(viewModel.uiState.value.failed)
        assertTrue(viewModel.authState.value is AuthState.SignedOut)
    }

    @Test
    fun cancellingTheGoogleSheetShowsNothing() {
        viewModel.onSignInStarted()
        viewModel.onCredentialCancelled()

        assertFalse(viewModel.uiState.value.signingIn)
        assertFalse(viewModel.uiState.value.failed)
        assertNull(repository.lastSignIn)
    }

    @Test
    fun aMissingGoogleCredentialShowsTheErrorMessage() {
        viewModel.onSignInStarted()
        viewModel.onCredentialFailed()

        assertFalse(viewModel.uiState.value.signingIn)
        assertTrue(viewModel.uiState.value.failed)
    }

    @Test
    fun theErrorCanBeDismissed() {
        viewModel.onCredentialFailed()

        viewModel.onErrorDismissed()

        assertFalse(viewModel.uiState.value.failed)
    }

    @Test
    fun aSecondTapWhileSigningInIsIgnored() {
        viewModel.onSignInStarted()
        viewModel.onErrorDismissed()

        assertTrue(viewModel.uiState.value.signingIn)
    }

    private class FakeAuthRepository : AuthRepository {

        private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
        override val state: StateFlow<AuthState> = _state

        var signInResult: Result<Unit> = Result.success(Unit)
        var lastSignIn: Pair<String, String>? = null

        override suspend fun signInWithGoogleIdToken(idToken: String, rawNonce: String): Result<Unit> {
            lastSignIn = idToken to rawNonce
            if (signInResult.isSuccess) {
                _state.value = AuthState.SignedIn(userId = "user-1", email = "caregiver@example.com")
            }
            return signInResult
        }

        override suspend fun signOut(): Result<Unit> {
            _state.value = AuthState.SignedOut
            return Result.success(Unit)
        }
    }
}
