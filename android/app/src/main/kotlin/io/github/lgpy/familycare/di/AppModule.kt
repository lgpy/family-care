package io.github.lgpy.familycare.di

import android.os.SystemClock
import io.github.lgpy.familycare.BuildConfig
import io.github.lgpy.familycare.auth.GoogleSignIn
import io.github.lgpy.familycare.core.data.DataLayer
import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.data.sync.SyncService
import io.github.lgpy.familycare.core.domain.circle.CareCircleId
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.domain.circle.ProfileRepository
import io.github.lgpy.familycare.core.ui.lock.AppLockController
import io.github.lgpy.familycare.core.ui.lock.AppLockPolicy
import io.github.lgpy.familycare.core.ui.locale.AppLanguageController
import io.github.lgpy.familycare.ui.SignInViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The app's dependency graph: the app lock, language state, the Supabase
 * session, sync and the core repositories (care circle, patients, profile).
 *
 * Environment values come from BuildConfig (see android/environments). The
 * Supabase client, the encrypted local database, the PowerSync connector and
 * the repository implementations are built inside core/data, so this module
 * never sees those types. Screens inject the domain interfaces:
 * `koinInject<CareCircleRepository>()`, `koinInject<PatientRepository>()`,
 * `koinInject<ProfileRepository>()`.
 */
val appModule: Module = module {
    // Placeholder for the Today screen until the M2 screen task reads
    // CareCircleRepository.setupState instead.
    single { CareCircleId("local-placeholder") }

    // Monotonic clock: elapsedRealtime is unaffected by wall-clock changes.
    single { AppLockPolicy(elapsedRealtime = SystemClock::elapsedRealtime) }
    single { AppLockController(context = androidContext(), policy = get()) }

    single { AppLanguageController(context = androidContext()) }

    // D12: Supabase session, plus the sync layer (one Supabase client and one
    // encrypted PowerSync database for the process). The publishable key is
    // public by design; RLS protects the data (security-and-privacy §3).
    single {
        DataLayer.create(
            context = androidContext(),
            supabaseUrl = BuildConfig.SUPABASE_URL,
            publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            powerSyncUrl = BuildConfig.POWERSYNC_URL,
        )
    }
    single<AuthRepository> { get<DataLayer>().authRepository }
    single<SyncService> { get<DataLayer>().sync }

    // M2 care circle. Reads are local and offline-first; membership changes go
    // through the core RPCs.
    single<CareCircleRepository> { get<DataLayer>().careCircleRepository }
    single<PatientRepository> { get<DataLayer>().patientRepository }
    single<ProfileRepository> { get<DataLayer>().profileRepository }

    // Needs an Activity at call time, so it is a plain collaborator rather than
    // a singleton holding one.
    single { GoogleSignIn(webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID) }

    viewModel { SignInViewModel(get()) }
}
