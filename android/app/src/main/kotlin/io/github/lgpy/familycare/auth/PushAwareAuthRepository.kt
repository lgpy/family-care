package io.github.lgpy.familycare.auth

import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.data.sync.SyncService
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.notifications.PushSession
import kotlinx.coroutines.flow.StateFlow

/**
 * [AuthRepository] with the M4 push sign-out step (D27).
 *
 * Every screen signs out through the repository (`More`, the circle gate), so
 * wrapping it is what guarantees the order the server needs:
 *
 * 1. delete this install's `core.push_devices` row and the FCM token, **while
 *    the session still exists** (the RPC is authenticated);
 * 2. then clear the session.
 *
 * Unregistering is best effort: offline the row stays, but the deleted token
 * stops being deliverable and the server removes it when FCM reports it dead —
 * a sign-out must never fail or wait because of push.
 */
class PushAwareAuthRepository(
    private val delegate: AuthRepository,
    private val push: PushSession,
) : AuthRepository {

    override val state: StateFlow<AuthState> get() = delegate.state

    override suspend fun signInWithGoogleIdToken(
        idToken: String,
        rawNonce: String,
    ): Result<Unit> = delegate.signInWithGoogleIdToken(idToken, rawNonce)

    override suspend fun signOut(): Result<Unit> {
        push.unregisterForSignOut()
        return delegate.signOut()
    }

    override suspend fun signOutEverywhere(): Result<Unit> {
        push.unregisterForSignOut()
        return delegate.signOutEverywhere()
    }
}
