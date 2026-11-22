@file:OptIn(SupabaseInternal::class, ExperimentalCoroutinesApi::class)

package io.github.lgpy.familycare.core.data.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.lgpy.familycare.core.domain.auth.AuthState
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SupabaseAuthRepository] over a real supabase-kt [Auth] plugin, with the
 * session status driven by hand: no network, the session kept in memory.
 *
 * The point is the production path, not the mapping function alone. In
 * supabase-kt `currentSessionOrNull()` is null unless the status is
 * Authenticated, so a refresh failure must be resolved from what the
 * repository saw last or from the session manager's stored session.
 */
class SupabaseAuthRepositoryTest {

    private val offline = SessionStatus.RefreshFailure(RefreshFailureCause.NetworkError(IOException("offline")))

    @Test
    fun aFailedRefreshAfterSigningInKeepsTheCaregiverSignedIn() = runTest {
        val supabase = client(stored = null)
        val repository = repository(supabase)

        supabase.auth.importSession(expiredSession("user-1", "caregiver@example.com"), autoRefresh = false)
        assertEquals(signedIn, repository.state.value)

        // The access token expired while offline and the refresh failed.
        supabase.auth.setSessionStatus(offline)

        assertEquals(signedIn, repository.state.value)
    }

    @Test
    fun aColdStartThatCannotRefreshUsesTheStoredSession() = runTest {
        val supabase = client(stored = expiredSession("user-1", "caregiver@example.com"))
        supabase.auth.setSessionStatus(SessionStatus.Initializing)
        val repository = repository(supabase)
        assertEquals(AuthState.Loading, repository.state.value)

        supabase.auth.setSessionStatus(offline)

        assertEquals(signedIn, repository.state.value)
    }

    @Test
    fun aStoredSessionWithoutUserInfoIsAttributedThroughItsToken() = runTest {
        val supabase = client(stored = expiredSession(userId = null).copy(accessToken = jwtWithSubject("user-1")))
        supabase.auth.setSessionStatus(SessionStatus.Initializing)
        val repository = repository(supabase)

        supabase.auth.setSessionStatus(offline)

        assertEquals(AuthState.SignedIn(userId = "user-1", email = null), repository.state.value)
    }

    @Test
    fun aRejectedRefreshTokenStillSignsOut() = runTest {
        val supabase = client(stored = null)
        val repository = repository(supabase)
        supabase.auth.importSession(expiredSession("user-1", "caregiver@example.com"), autoRefresh = false)

        // supabase-kt reports an invalid refresh token as NotAuthenticated and
        // deletes the stored session.
        supabase.auth.clearSession()
        assertEquals(AuthState.SignedOut, repository.state.value)

        // A later refresh failure must not bring the previous user back.
        supabase.auth.setSessionStatus(offline)
        assertEquals(AuthState.SignedOut, repository.state.value)
    }

    @Test
    fun aFailedRefreshWithNoStoredSessionIsSignedOut() = runTest {
        val supabase = client(stored = null)
        supabase.auth.setSessionStatus(SessionStatus.Initializing)
        val repository = repository(supabase)

        supabase.auth.setSessionStatus(offline)

        assertEquals(AuthState.SignedOut, repository.state.value)
    }

    private val signedIn = AuthState.SignedIn(userId = "user-1", email = "caregiver@example.com")

    private fun client(stored: UserSession?): SupabaseClient {
        val supabase = createSupabaseClient(
            supabaseUrl = "https://example.supabase.co",
            supabaseKey = "publishable-key",
        ) {
            install(Auth) {
                sessionManager = MemorySessionManager(stored)
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                alwaysAutoRefresh = false
                enableLifecycleCallbacks = false
                autoSetupPlatform = false
            }
        }
        // autoLoadFromStorage = false leaves the status at Initializing: each
        // test drives it from there, as supabase-kt does on a real start.
        return supabase
    }

    private fun TestScope.repository(supabase: SupabaseClient) = SupabaseAuthRepository(
        supabase = supabase,
        scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
    )

    private fun expiredSession(userId: String?, email: String? = null): UserSession = UserSession(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        expiresIn = 3_600,
        expiresAt = Clock.System.now() - 2.hours,
        tokenType = "bearer",
        user = userId?.let { id -> UserInfo(aud = "authenticated", id = id, email = email) },
    )

    private fun jwtWithSubject(subject: String): String {
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString("""{"sub":"$subject","role":"authenticated"}""".toByteArray())
        return "$header.$payload.signature"
    }
}
