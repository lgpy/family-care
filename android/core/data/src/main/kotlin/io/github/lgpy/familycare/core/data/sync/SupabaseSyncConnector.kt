package io.github.lgpy.familycare.core.data.sync

import com.powersync.PowerSyncDatabase
import com.powersync.connectors.PowerSyncBackendConnector
import com.powersync.connectors.PowerSyncCredentials
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Connect PowerSync to this app's own backend: Supabase Auth for credentials and
 * PostgREST (schema `core`) for uploads.
 *
 * PowerSync's prebuilt Supabase connector is intentionally not used: it uploads
 * through the `public` stream schema, while every real table lives in a module
 * schema (`core`, `meds`, ...). Auth and PostgREST are still supabase-kt, so the
 * session and the publishable key are shared with the rest of the app.
 */
internal class SupabaseSyncConnector(
    private val supabase: SupabaseClient,
    private val endpoint: String,
    private val uploader: ProbeUploader,
    private val uploadFailure: MutableStateFlow<Boolean>,
    private val now: () -> Instant = { Clock.System.now() },
) : PowerSyncBackendConnector() {

    /**
     * Returns the current Supabase access token, or null when signed out.
     *
     * A token that is expired or about to expire is refreshed first, so a
     * reconnect after hours in the background does not fail once. If the
     * refresh fails because of the network, the error is thrown rather than
     * returning null: null means "signed out" to PowerSync and would stop sync
     * until the next sign-in, while a thrown error is retried (D3: offline-first).
     */
    override suspend fun fetchCredentials(): PowerSyncCredentials? {
        val auth = supabase.auth
        if (auth.sessionStatus.value is SessionStatus.Initializing) auth.awaitInitialization()
        if (auth.sessionStatus.value is SessionStatus.NotAuthenticated) return null

        val session = auth.currentSessionOrNull()
        val token = when {
            session == null -> refreshedToken()
            session.expiresAt - now() <= REFRESH_MARGIN -> refreshedToken()
            else -> session.accessToken
        }
        return token?.let { PowerSyncCredentials(endpoint = endpoint, token = it) }
    }

    /**
     * Sends the queued local writes, oldest transaction first.
     *
     * A permanent failure completes the transaction so the rest of the queue is
     * not blocked behind a row the server will never accept, and raises the
     * error flag instead. Everything else is rethrown for PowerSync to retry.
     * Neither branch logs row data, and neither puts a token anywhere.
     */
    override suspend fun uploadData(database: PowerSyncDatabase) {
        while (true) {
            val transaction = database.getNextCrudTransaction() ?: return
            try {
                // M1 has a single synced table; changes to any other local table
                // are not uploaded and the transaction is completed below.
                uploadProbeWrites(transaction.probeWrites(), uploader)
                uploadFailure.value = false
                transaction.complete(null)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (!UploadFailure.of(error).isPermanent) throw error

                uploadFailure.value = true
                transaction.complete(null)
            }
        }
    }

    private suspend fun refreshedToken(): String? {
        supabase.auth.refreshCurrentSession()
        return supabase.auth.currentAccessTokenOrNull()
    }

    private companion object {
        /** Refresh ahead of expiry, so an upload never races a token change. */
        val REFRESH_MARGIN = 60.seconds
    }
}
