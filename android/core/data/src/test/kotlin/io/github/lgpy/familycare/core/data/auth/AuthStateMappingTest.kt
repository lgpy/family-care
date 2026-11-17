package io.github.lgpy.familycare.core.data.auth

import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.lgpy.familycare.core.domain.auth.AuthState
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthStateMappingTest {

    @Test
    fun initializingSessionIsLoading() {
        assertEquals(AuthState.Loading, SessionStatus.Initializing.toAuthState())
    }

    @Test
    fun missingSessionIsSignedOut() {
        assertEquals(AuthState.SignedOut, SessionStatus.NotAuthenticated().toAuthState())
        assertEquals(AuthState.SignedOut, SessionStatus.NotAuthenticated(isSignOut = true).toAuthState())
    }

    @Test
    fun authenticatedSessionExposesUserIdAndEmail() {
        val state = SessionStatus.Authenticated(session("user-1", "caregiver@example.com")).toAuthState()

        assertEquals(AuthState.SignedIn(userId = "user-1", email = "caregiver@example.com"), state)
    }

    @Test
    fun authenticatedSessionWithoutEmailIsStillSignedIn() {
        val state = SessionStatus.Authenticated(session("user-1")).toAuthState()

        assertEquals(AuthState.SignedIn(userId = "user-1", email = null), state)
    }

    @Test
    fun blankEmailBecomesNull() {
        val state = SessionStatus.Authenticated(session("user-1", "   ")).toAuthState()

        assertNull((state as AuthState.SignedIn).email)
    }

    @Test
    fun sessionWithoutUserIsSignedOut() {
        assertEquals(AuthState.SignedOut, SessionStatus.Authenticated(session(userId = null)).toAuthState())
    }

    @Test
    fun failedRefreshWhileOfflineKeepsTheStoredSessionSignedIn() {
        val status = SessionStatus.RefreshFailure(RefreshFailureCause.NetworkError(IOException("offline")))
        val stored = session("user-1", "caregiver@example.com").user

        assertEquals(
            AuthState.SignedIn(userId = "user-1", email = "caregiver@example.com"),
            status.toAuthState(storedUser = stored),
        )
    }

    @Test
    fun failedRefreshWithoutAStoredSessionIsSignedOut() {
        val status = SessionStatus.RefreshFailure(RefreshFailureCause.NetworkError(IOException("offline")))

        assertEquals(AuthState.SignedOut, status.toAuthState(storedUser = null))
    }

    private fun session(userId: String?, email: String? = null): UserSession = UserSession(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        expiresIn = 3_600,
        tokenType = "bearer",
        user = userId?.let { id -> UserInfo(aud = "authenticated", id = id, email = email) },
    )
}
