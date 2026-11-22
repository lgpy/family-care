package io.github.lgpy.familycare.core.data.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.lgpy.familycare.core.domain.auth.AuthState
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

    /**
     * The caregiver the last Authenticated status named, cleared on sign-out.
     * Only the single collector below reads and writes it.
     */
    private var lastSignedIn: AuthState.SignedIn? = null

    /**
     * `currentSessionOrNull()` is null for every status except Authenticated, so
     * it cannot name the user of a refresh failure. That user is the one seen
     * last in this process or, on a cold start whose first refresh fails, the
     * session the auth plugin keeps in its session manager. A refresh failure
     * must never read as signed out while such a session exists: sign-out wipes
     * the local database, the unsynced upload queue included.
     */
    override val state: StateFlow<AuthState> = auth.sessionStatus
        .map { status -> resolve(status).also(::remember) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            // A refresh failure needs the stored session, which is a suspend
            // read: start as Loading (sync does nothing) until the first map.
            initialValue = auth.sessionStatus.value.let { status ->
                if (status is SessionStatus.RefreshFailure) AuthState.Loading else status.toAuthState()
            },
        )

    private suspend fun resolve(status: SessionStatus): AuthState = when (status) {
        is SessionStatus.RefreshFailure -> lastSignedIn ?: storedSessionState()
        else -> status.toAuthState()
    }

    private suspend fun storedSessionState(): AuthState =
        auth.sessionManager.loadSessionOrNull()?.storedAuthState() ?: AuthState.SignedOut

    private fun remember(state: AuthState) {
        when (state) {
            is AuthState.SignedIn -> lastSignedIn = state
            AuthState.SignedOut -> lastSignedIn = null
            AuthState.Loading -> Unit
        }
    }

    override suspend fun signInWithGoogleIdToken(idToken: String, rawNonce: String): Result<Unit> =
        runAuth {
            auth.signInWith(IDToken) {
                this.idToken = idToken
                provider = Google
                nonce = rawNonce
            }
        }

    override suspend fun signOut(): Result<Unit> = runAuth { auth.signOut() }

    override suspend fun signOutEverywhere(): Result<Unit> =
        runAuth { auth.signOut(SignOutScope.GLOBAL) }
}

/**
 * Maps the Supabase session onto the app's own vocabulary.
 *
 * - a refresh failure is a network or server error while refreshing (an
 *   invalid refresh token ends in NotAuthenticated instead). The stored session
 *   is kept and supabase-kt retries, so the caregiver stays signed in: the app
 *   is offline-first (D3) and must keep working, reminders included, without
 *   a connection. [storedUser] is that stored session's user, which
 *   [SupabaseAuthRepository] resolves (supabase-kt's `currentSessionOrNull()`
 *   is null during a refresh failure);
 * - a session without a user id cannot be attributed to a caregiver, so it is
 *   treated as signed out rather than half-signed-in.
 */
internal fun SessionStatus.toAuthState(storedUser: UserInfo? = null): AuthState = when (this) {
    SessionStatus.Initializing -> AuthState.Loading
    is SessionStatus.NotAuthenticated -> AuthState.SignedOut
    is SessionStatus.Authenticated -> session.user.toAuthState()
    is SessionStatus.RefreshFailure -> storedUser.toAuthState()
}

/**
 * The caregiver a stored session belongs to: its user, or the `sub` claim of
 * its access token when the session was saved without user info. The token is
 * only read, not verified: it names whose session this device already holds.
 */
internal fun UserSession.storedAuthState(): AuthState {
    val fromUser = user.toAuthState()
    if (fromUser is AuthState.SignedIn) return fromUser
    val subject = accessTokenSubject(accessToken) ?: return AuthState.SignedOut
    return AuthState.SignedIn(userId = subject, email = null)
}

private fun accessTokenSubject(token: String): String? = runCatching {
    val payload = token.split('.').getOrNull(1) ?: return null
    val json = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8))
    json.jsonObject["sub"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
}.getOrNull()

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
