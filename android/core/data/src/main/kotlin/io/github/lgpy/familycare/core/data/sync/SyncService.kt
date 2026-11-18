package io.github.lgpy.familycare.core.data.sync

import com.powersync.PowerSyncDatabase
import com.powersync.sync.SyncClientConfiguration
import com.powersync.sync.SyncOptions
import com.powersync.sync.SyncStatusData
import io.github.jan.supabase.SupabaseClient
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.sync.RejectedUpload
import io.github.lgpy.familycare.core.domain.sync.SyncState
import io.ktor.client.HttpClient
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The sync layer as the rest of the app sees it: connection state and one call
 * to start following the session. No PowerSync type appears here, the same way
 * [io.github.lgpy.familycare.core.data.auth.AuthRepository] hides Supabase.
 */
interface SyncService {

    /** Connection state for a sync indicator. */
    val state: StateFlow<SyncState>

    /**
     * Starts following the Supabase session: connect when signed in, clear
     * local data when signed out. Safe to call once per process.
     */
    fun start()

    /**
     * Marks the latest refused change as seen: the app-shell message disappears
     * and does not come back. A later refusal is a new event and shows again.
     */
    fun acknowledgeRejectedUpload()
}

/**
 * [SyncService] backed by PowerSync and the app's own [SupabaseSyncConnector].
 *
 * The database stays open for the life of the process (it is a singleton in
 * Koin); the connection is opened and closed as the session changes.
 */
internal class PowerSyncSyncService(
    private val database: PowerSyncDatabase,
    private val connector: SupabaseSyncConnector,
    private val httpClient: HttpClient,
    private val authState: StateFlow<AuthState>,
    private val connectionFailed: MutableStateFlow<Boolean>,
    private val rejections: UploadRejections,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : SyncService, SyncSession {

    private val lifecycle = SyncLifecycle(session = this)
    private var started = false

    override val state: StateFlow<SyncState> = combine(
        database.currentStatus.asFlow().onStart { emit(database.currentStatus) },
        connectionFailed,
        rejections.state,
    ) { status, failed, rejection -> status.toSyncState(failed, rejection) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = database.currentStatus.toSyncState(
                hasError = connectionFailed.value,
                rejection = rejections.state.value,
            ),
        )

    override fun start() {
        if (started) return
        started = true
        scope.launch {
            lifecycle.run(authState) { connectionFailed.value = true }
        }
    }

    override fun acknowledgeRejectedUpload() {
        rejections.acknowledge()
    }

    /**
     * The user id is not sent anywhere: PowerSync authenticates with a
     * short-lived token, and the rows carry their own ownership columns.
     */
    override suspend fun connect(userId: String) {
        database.connect(
            connector = connector,
            options = SyncOptions(
                clientConfiguration = SyncClientConfiguration.ExistingClient(httpClient),
            ),
        )
    }

    override suspend fun disconnectAndClear() {
        // Hard clear: nothing of the signed-out user's health data may remain
        // readable in the SQLite file.
        database.disconnectAndClear(clearLocal = true, soft = false)
        // A connection error and a refused change belong to the session that
        // ended; the next user must not see either.
        connectionFailed.value = false
        rejections.acknowledge()
    }

    /** Cancels the session follower; only needed for tests and shutdown. */
    internal suspend fun close() {
        try {
            database.close()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } finally {
            scope.cancel()
        }
    }
}

private fun SyncStatusData.toSyncState(
    hasError: Boolean,
    rejection: RejectedUpload?,
): SyncState = SyncState(
    connected = connected,
    connecting = connecting,
    uploading = uploading,
    downloading = downloading,
    hasSynced = hasSynced,
    lastSyncedAt = lastSyncedAt,
    // A refusal is not a transport error but it is still something that went
    // wrong for the user: keep hasError true while it is unacknowledged.
    hasError = hasError || anyError != null || rejection != null,
    rejectedUpload = rejection,
)
