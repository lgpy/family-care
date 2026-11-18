package io.github.lgpy.familycare.core.data

import android.content.Context
import com.powersync.ExperimentalPowerSyncAPI
import com.powersync.PowerSyncDatabase
import com.powersync.encryption.AndroidEncryptedDatabaseFactory
import com.powersync.encryption.Key
import com.powersync.sync.configureSyncHttpClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.data.auth.SupabaseAuthRepository
import io.github.lgpy.familycare.core.data.circle.PowerSyncCareCircleRepository
import io.github.lgpy.familycare.core.data.circle.PowerSyncPatientRepository
import io.github.lgpy.familycare.core.data.circle.PowerSyncProfileRepository
import io.github.lgpy.familycare.core.data.circle.SupabaseCareCircleRpc
import io.github.lgpy.familycare.core.data.sync.AndroidKeystoreCipher
import io.github.lgpy.familycare.core.data.sync.CoreSchema
import io.github.lgpy.familycare.core.data.sync.DATABASE_FILE_NAME
import io.github.lgpy.familycare.core.data.sync.FileKeyBlobStore
import io.github.lgpy.familycare.core.data.sync.PowerSyncSyncService
import io.github.lgpy.familycare.core.data.sync.SupabaseCrudUploader
import io.github.lgpy.familycare.core.data.sync.SupabaseSyncConnector
import io.github.lgpy.familycare.core.data.sync.SyncService
import io.github.lgpy.familycare.core.data.sync.WrappedDatabaseKey
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.ProfileRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The data layer, built once for the process: one Supabase client, one
 * encrypted PowerSync database, one sync service and the core repositories.
 *
 * The app's Koin module creates this and exposes [authRepository], [sync] and
 * the three repository interfaces, so no module outside `core/data` needs a
 * PowerSync, Ktor or Supabase type.
 */
class DataLayer internal constructor(
    val authRepository: AuthRepository,
    val sync: SyncService,
    val careCircleRepository: CareCircleRepository,
    val patientRepository: PatientRepository,
    val profileRepository: ProfileRepository,
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
         */
        @OptIn(ExperimentalPowerSyncAPI::class)
        fun create(
            context: Context,
            supabaseUrl: String,
            publishableKey: String,
            powerSyncUrl: String,
        ): DataLayer {
            val supabase = SupabaseClientFactory.create(supabaseUrl, publishableKey)
            val authRepository = SupabaseAuthRepository(supabase)

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
                schema = CoreSchema,
                dbFilename = DATABASE_FILE_NAME,
                scope = scope,
            )

            // One HTTP client for the sync stream. The Ktor engine is the one
            // supabase-kt already uses (ktor-client-android): an OkHttp engine
            // would pull artifacts that demand a newer compileSdk.
            val httpClient = HttpClient(Android) { configureSyncHttpClient() }

            val uploadFailure = MutableStateFlow(false)
            val connector = SupabaseSyncConnector(
                supabase = supabase,
                endpoint = powerSyncUrl,
                uploader = SupabaseCrudUploader(supabase.postgrest),
                uploadFailure = uploadFailure,
            )
            val sync = PowerSyncSyncService(
                database = database,
                connector = connector,
                httpClient = httpClient,
                authState = authRepository.state,
                uploadFailure = uploadFailure,
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
                ),
                profileRepository = PowerSyncProfileRepository(
                    database = database,
                    authState = authRepository.state,
                ),
            )
        }
    }
}
