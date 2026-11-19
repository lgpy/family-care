package io.github.lgpy.familycare.core.data.auth

import io.github.lgpy.familycare.core.domain.auth.AuthState
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's view of the signed-in user (D12). Supabase is the only
 * implementation today; tests inject a fake.
 *
 * Sign-in failures are returned as [Result.failure] instead of thrown, so the
 * UI must decide what to show and no exception text reaches the screen.
 */
interface AuthRepository {

    /** Current session state, observed by the sign-in gate and Settings. */
    val state: StateFlow<AuthState>

    /**
     * Exchanges a Google ID token for a Supabase session.
     *
     * @param idToken the ID token Credential Manager returned.
     * @param rawNonce the *unhashed* nonce whose SHA-256 was given to Google.
     *   Supabase hashes it again and compares it with the token's `nonce` claim.
     */
    suspend fun signInWithGoogleIdToken(idToken: String, rawNonce: String): Result<Unit>

    /** Ends the local session (and revokes the refresh token Supabase-side). */
    suspend fun signOut(): Result<Unit>

    /**
     * Signs out of **every** device: Supabase revokes all of the user's refresh
     * tokens, so each session, this one included, ends (D30, docs/domain/
     * patient-lifecycle.md §Account).
     *
     * The default falls back to [signOut] for an implementation that cannot
     * revoke other sessions; the app's repository always can.
     */
    suspend fun signOutEverywhere(): Result<Unit> = signOut()
}
