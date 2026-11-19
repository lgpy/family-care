package io.github.lgpy.familycare.core.data

import android.content.Context
import com.powersync.ExperimentalPowerSyncAPI
import com.powersync.PowerSyncDatabase
import com.powersync.encryption.AndroidEncryptedDatabaseFactory
import com.powersync.encryption.Key
import com.powersync.sync.configureSyncHttpClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.lgpy.familycare.core.data.activity.ActivityRepository
import io.github.lgpy.familycare.core.data.activity.SupabaseActivityRepository
import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.data.auth.SupabaseAuthRepository
import io.github.lgpy.familycare.core.data.circle.PowerSyncCareCircleRepository
import io.github.lgpy.familycare.core.data.circle.PowerSyncPatientRepository
import io.github.lgpy.familycare.core.data.circle.PowerSyncProfileRepository
import io.github.lgpy.familycare.core.data.circle.SupabaseCareCircleRpc
import io.github.lgpy.familycare.core.data.circle.SupabasePatientRpc
import io.github.lgpy.familycare.core.data.push.SupabasePushDeviceRepository
import io.github.lgpy.familycare.core.data.sync.AndroidKeystoreCipher
import io.github.lgpy.familycare.core.data.sync.CoreSyncedTables
import io.github.lgpy.familycare.core.data.sync.DATABASE_FILE_NAME
import io.github.lgpy.familycare.core.data.sync.FileKeyBlobStore
import io.github.lgpy.familycare.core.data.sync.PowerSyncSyncService
import io.github.lgpy.familycare.core.data.sync.SupabaseCrudUploader
import io.github.lgpy.familycare.core.data.sync.SupabaseSyncConnector
import io.github.lgpy.familycare.core.data.sync.SyncService
import io.github.lgpy.familycare.core.data.sync.SyncedTables
import io.github.lgpy.familycare.core.data.sync.TableCatalog
import io.github.lgpy.familycare.core.data.sync.UploadRejections
import io.github.lgpy.familycare.core.data.sync.WrappedDatabaseKey
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.ProfileRepository
import io.github.lgpy.familycare.core.domain.push.PushDeviceRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The data layer, built once for the process: one Supabase client, one
 * encrypted PowerSync database, one sync service and the core repositories.
 *
 * The app's Koin module creates this and exposes [authRepository], [sync] and
 * the three core repository interfaces. Feature modules own their own tables and
 * repositories: they contribute their synced tables to [create] through
 * [SyncedTables] and build their repositories from [database] and [authState]
 * (see the medications module). Nothing outside `core/data` and those feature
 * data modules needs a PowerSync, Ktor or Supabase type.
 */
class DataLayer internal constructor(
    val authRepository: AuthRepository,
    val sync: SyncService,
    val careCircleRepository: CareCircleRepository,
    val patientRepository: PatientRepository,
    val profileRepository: ProfileRepository,
    /**
     * The owner-only activity feed (D30). Online only: the audit log is not
     * synced, so this read goes straight to the server's RPC.
     */
    val activityRepository: ActivityRepository,
    /**
     * The local, encrypted database. Feature data modules run their own SQL on
     * it (D5: a module owns its tables); the app's DI passes it to their
     * repository factories.
     */
    val database: PowerSyncDatabase,
    /** Who is signed in, so a feature repository can scope its reads and writes. */
    val authState: StateFlow<AuthState>,
    /**
     * This install's FCM token on the server (D27). Part of the core because
     * push registration is a core RPC and every module may need it; the token
     * itself never leaves the push client.
     */
    val pushDeviceRepository: PushDeviceRepository,
) {

    companion object {

        /** Keystore alias of the key that wraps the local database key. */
        private const val WRAPPING_KEY_ALIAS = "familycare-sync-database-key"

        /**
         * Opens the encrypted database and wires sync to Supabase.
         *
         * @param context Application context: the database file, the wrapped key
         *   and the PowerSync SDK all hang off it.
         * @param powerSyncUrl The instance endpoint from the build environment.
         * @param featureTables The synced tables feature modules contribute (D5).
         *   They are merged with the core's own tables into the local schema and
         *   the upload rules; the core never names a feature.
         */
        @OptIn(ExperimentalPowerSyncAPI::class)
        fun create(
            context: Context,
            supabaseUrl: String,
            publishableKey: String,
            powerSyncUrl: String,
            featureTables: List<SyncedTables> = emptyList(),
        ): DataLayer {
            val supabase = SupabaseClientFactory.create(supabaseUrl, publishableKey)
            val authRepository = SupabaseAuthRepository(supabase)

            // The core's tables first, then every feature's contribution. A
            // duplicate table name fails here, at start-up.
            val tables = TableCatalog(listOf(CoreSyncedTables) + featureTables)

            // The database lives as long as the process; PowerSync wants a scope
            // that outlives individual connections.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            // 32 random bytes, wrapped by a non-exportable Keystore key and
            // stored in noBackupFilesDir (security-and-privacy §4). Never logged.
            val passphrase = WrappedDatabaseKey(
                blobs = FileKeyBlobStore(File(context.noBackupFilesDir, WrappedDatabaseKey.BLOB_FILE_NAME)),
                cipher = AndroidKeystoreCipher(WRAPPING_KEY_ALIAS),
            ).passphrase()

            val database = PowerSyncDatabase(
                factory = AndroidEncryptedDatabaseFactory(context, Key.Passphrase(passphrase)),
                schema = tables.schema,
                dbFilename = DATABASE_FILE_NAME,
                scope = scope,
            )

            // One HTTP client for the sync stream. The Ktor engine is the one
            // supabase-kt already uses (ktor-client-android): an OkHttp engine
            // would pull artifacts that demand a newer compileSdk.
            val httpClient = HttpClient(Android) { configureSyncHttpClient() }

            val connectionFailed = MutableStateFlow(false)
            val rejections = UploadRejections()
            val connector = SupabaseSyncConnector(
                supabase = supabase,
                endpoint = powerSyncUrl,
                uploader = SupabaseCrudUploader(supabase.postgrest, tables),
                rejections = rejections,
                tables = tables,
            )
            val sync = PowerSyncSyncService(
                database = database,
                connector = connector,
                httpClient = httpClient,
                authState = authRepository.state,
                connectionFailed = connectionFailed,
                rejections = rejections,
                scope = scope,
            )

            // Reads come from the local database, so the repositories only need
            // the sync state (hasSynced) and, for membership changes, PostgREST.
            val careCircleRepository = PowerSyncCareCircleRepository(
                database = database,
                authState = authRepository.state,
                syncState = sync.state,
                rpc = SupabaseCareCircleRpc(supabase.postgrest),
            )

            return DataLayer(
                authRepository = authRepository,
                sync = sync,
                careCircleRepository = careCircleRepository,
                patientRepository = PowerSyncPatientRepository(
                    database = database,
                    authState = authRepository.state,
                    rpc = SupabasePatientRpc(supabase.postgrest),
                ),
                profileRepository = PowerSyncProfileRepository(
                    database = database,
                    authState = authRepository.state,
                ),
                activityRepository = SupabaseActivityRepository(supabase.postgrest),
                database = database,
                authState = authRepository.state,
                pushDeviceRepository = SupabasePushDeviceRepository(supabase.postgrest),
            )
        }
    }
}
