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
 * - signed in as the recorded owner: keep the cached rows and the upload
 *   queue, and connect (once per process);
 * - signed in as a different user: clear first, then connect, so no row of the
 *   previous user is ever visible and their queue is never sent under the new
 *   user's token;
 * - signed out: disconnect and wipe the synced data, unless the record says the
 *   database is already clean;
 * - loading: do nothing yet.
 *
 * "Whose data is local" is persisted through [owners], not held in memory: the
 * session can end while the app is not running (revoked elsewhere, "sign out
 * all devices"), and the process can die between sign-out and the wipe. Either
 * way the next start sees the record and clears.
 *
 * The owner is recorded *before* connecting, so a process killed mid-sync
 * leaves a record that names whose rows may be in the file; after a wipe the
 * record becomes [LocalOwner.Clean]. A missing record ([LocalOwner.Unrecorded])
 * is an install upgraded from a build that kept none, or a fresh install: a
 * signed-in user is adopted as the owner without clearing (their unsynced queue
 * is theirs), and a signed-out start clears.
 */
internal class SyncLifecycle(
    private val session: SyncSession,
    private val owners: LocalOwnerStore,
) {

    /** The user this process has connected for; null when not connected. */
    private var connectedUserId: String? = null

    suspend fun onAuthState(state: AuthState) {
        when (state) {
            AuthState.Loading -> Unit
            AuthState.SignedOut -> clearUnlessClean()
            is AuthState.SignedIn -> signIn(state.userId)
        }
    }

    private suspend fun signIn(userId: String) {
        if (connectedUserId == userId) return

        val mustClear = when (val owner = owners.read()) {
            is LocalOwner.User -> owner.userId != userId
            LocalOwner.Clean -> false
            // The upgrade case: adopt this user, unless this process already
            // synced someone else into the database.
            LocalOwner.Unrecorded -> false
        } || connectedUserId != null

        if (mustClear) {
            session.disconnectAndClear()
            connectedUserId = null
        }
        owners.write(LocalOwner.User(userId))
        session.connect(userId)
        connectedUserId = userId
    }

    private suspend fun clearUnlessClean() {
        if (connectedUserId == null && owners.read() == LocalOwner.Clean) return
        session.disconnectAndClear()
        connectedUserId = null
        owners.write(LocalOwner.Clean)
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
