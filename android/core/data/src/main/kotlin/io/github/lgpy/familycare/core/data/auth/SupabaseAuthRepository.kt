package io.github.lgpy.familycare.core.data.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.lgpy.familycare.core.data.SupabaseClientFactory
import io.github.lgpy.familycare.core.domain.auth.AuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Supabase Auth behind [AuthRepository].
 *
 * Session persistence and refresh are supabase-kt's defaults, which is what M1
 * needs (decision 5):
 *
 * TODO(T1, security-and-privacy §4): those defaults keep the session, including
 * the refresh token, in app-private storage **unencrypted** (the default
 * SettingsSessionManager over SharedPreferences). Move it to Keystore-backed
 * encrypted storage before real health data is synced.
 */
class SupabaseAuthRepository(
    supabase: SupabaseClient,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AuthRepository {

    private val auth = supabase.auth

    override val state: StateFlow<AuthState> = auth.sessionStatus
        .map { it.toAuthState(auth.currentSessionOrNull()?.user) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = auth.sessionStatus.value.toAuthState(auth.currentSessionOrNull()?.user),
        )

    override suspend fun signInWithGoogleIdToken(idToken: String, rawNonce: String): Result<Unit> =
        runAuth {
            auth.signInWith(IDToken) {
                this.idToken = idToken
                provider = Google
                nonce = rawNonce
            }
        }

    override suspend fun signOut(): Result<Unit> = runAuth { auth.signOut() }

    companion object {
        /**
         * Builds the client and the repository together, so callers (the app's
         * Koin graph) never need Supabase types on their classpath.
         */
        fun create(supabaseUrl: String, publishableKey: String): SupabaseAuthRepository =
            SupabaseAuthRepository(SupabaseClientFactory.create(supabaseUrl, publishableKey))
    }
}

/**
 * Maps the Supabase session onto the app's own vocabulary.
 *
 * - a refresh failure is a network or server error while refreshing (an
 *   invalid refresh token ends in NotAuthenticated instead). The stored session
 *   is kept and supabase-kt retries, so the caregiver stays signed in: the app
 *   is offline-first (D3) and must keep working, reminders included, without
 *   a connection. [storedUser] is that stored session's user;
 * - a session without a user id cannot be attributed to a caregiver, so it is
 *   treated as signed out rather than half-signed-in.
 */
internal fun SessionStatus.toAuthState(storedUser: UserInfo? = null): AuthState = when (this) {
    SessionStatus.Initializing -> AuthState.Loading
    is SessionStatus.NotAuthenticated -> AuthState.SignedOut
    is SessionStatus.Authenticated -> session.user.toAuthState()
    is SessionStatus.RefreshFailure -> storedUser.toAuthState()
}

private fun UserInfo?.toAuthState(): AuthState {
    val userId = this?.id?.takeIf(String::isNotBlank) ?: return AuthState.SignedOut
    return AuthState.SignedIn(
        userId = userId,
        email = email?.takeIf(String::isNotBlank),
    )
}

private suspend fun <T> runAuth(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Exception) {
    Result.failure(error)
}
