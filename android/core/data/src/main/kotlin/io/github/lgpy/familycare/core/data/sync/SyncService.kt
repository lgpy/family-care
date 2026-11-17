package io.github.lgpy.familycare.core.data.sync

import com.powersync.PowerSyncDatabase
import com.powersync.sync.SyncClientConfiguration
import com.powersync.sync.SyncOptions
import com.powersync.sync.SyncStatusData
import io.github.jan.supabase.SupabaseClient
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.sync.SyncProbeRepository
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
 * The sync layer as the rest of the app sees it: local notes, sync state, and
 * one call to start following the session. No PowerSync type appears here, the
 * same way [io.github.lgpy.familycare.core.data.auth.AuthRepository] hides
 * Supabase.
 */
interface SyncService {

    /** Connection state for a sync indicator. */
    val state: StateFlow<SyncState>

    /** The M1 probe notes, readable and writable offline. */
    val probeNotes: SyncProbeRepository

    /**
     * Starts following the Supabase session: connect when signed in, clear
     * local data when signed out. Safe to call once per process.
     */
    fun start()
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
    override val probeNotes: SyncProbeRepository,
    private val uploadFailure: MutableStateFlow<Boolean>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : SyncService, SyncSession {

    private val lifecycle = SyncLifecycle(session = this)
    private var started = false

    override val state: StateFlow<SyncState> = combine(
        database.currentStatus.asFlow().onStart { emit(database.currentStatus) },
        uploadFailure,
    ) { status, failed -> status.toSyncState(failed) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = database.currentStatus.toSyncState(uploadFailure.value),
        )

    override fun start() {
        if (started) return
        started = true
        scope.launch {
            lifecycle.run(authState) { uploadFailure.value = true }
        }
    }

    /**
     * The user id is not sent anywhere: PowerSync authenticates with a
     * short-lived token, and the rows carry `owner_id` themselves.
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
        uploadFailure.value = false
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

private fun SyncStatusData.toSyncState(hasError: Boolean): SyncState = SyncState(
    connected = connected,
    connecting = connecting,
    uploading = uploading,
    downloading = downloading,
    hasSynced = hasSynced,
    lastSyncedAt = lastSyncedAt,
    hasError = hasError || anyError != null,
)
