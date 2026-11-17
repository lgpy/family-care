package io.github.lgpy.familycare.core.data.sync

import io.github.lgpy.familycare.core.domain.auth.AuthState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * The part of the sync layer the lifecycle logic drives. Kept as an interface so
 * the decision table (connect, clear, keep) is tested with a fake and without
 * PowerSync.
 */
internal interface SyncSession {
    suspend fun connect(userId: String)

    /** Disconnects and deletes the synced data, e.g. on sign-out. */
    suspend fun disconnectAndClear()
}

/**
 * Decides what to do with the local database when the session changes
 * (M1, decision 6):
 *
 * - signed in as the same user: keep the open connection and the cached rows;
 * - signed in as a different user: clear first, then connect, so no row of the
 *   previous user is ever visible;
 * - signed out: disconnect and wipe the synced data;
 * - loading: do nothing yet.
 *
 * The "whose data is local" fact lives in memory, which is enough while the
 * database and this object live for the process (single instance in Koin):
 * signing out clears the database, and signing in is only reachable from the
 * signed-out state.
 */
internal class SyncLifecycle(
    private val session: SyncSession,
) {

    /** The user whose synced rows are in the local database, if any. */
    var localOwnerId: String? = null
        private set

    suspend fun onAuthState(state: AuthState) {
        when (state) {
            AuthState.Loading -> Unit
            AuthState.SignedOut -> clearIfNeeded()
            is AuthState.SignedIn -> {
                if (localOwnerId == state.userId) return
                clearIfNeeded()
                session.connect(state.userId)
                localOwnerId = state.userId
            }
        }
    }

    /**
     * Follows [states] for the life of the process. A failure to connect is not
     * fatal (the app must keep working offline); it stops this cycle and the
     * next session change retries.
     */
    suspend fun run(states: Flow<AuthState>, onFailure: () -> Unit) {
        try {
            states.collect { state -> onFailureIfThrows(onFailure) { onAuthState(state) } }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            onFailure()
        }
    }

    private suspend fun clearIfNeeded() {
        if (localOwnerId == null) return
        session.disconnectAndClear()
        localOwnerId = null
    }

    private suspend fun onFailureIfThrows(onFailure: () -> Unit, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            onFailure()
        }
    }
}
