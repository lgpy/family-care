package io.github.lgpy.familycare.di

import android.os.SystemClock
import io.github.lgpy.familycare.BuildConfig
import io.github.lgpy.familycare.auth.GoogleSignIn
import io.github.lgpy.familycare.core.data.DataLayer
import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.data.sync.SyncService
import io.github.lgpy.familycare.core.domain.CareCircleId
import io.github.lgpy.familycare.core.domain.sync.SyncProbeRepository
import io.github.lgpy.familycare.core.ui.lock.AppLockController
import io.github.lgpy.familycare.core.ui.lock.AppLockPolicy
import io.github.lgpy.familycare.core.ui.locale.AppLanguageController
import io.github.lgpy.familycare.ui.SignInViewModel
import io.github.lgpy.familycare.ui.SyncTestViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The app's dependency graph. Deliberately tiny for M1: one value from the
 * pure domain module, so a broken graph fails at startup rather than later,
 * plus the app lock, language state, the Supabase session and sync.
 *
 * Environment values come from BuildConfig (see android/environments). The
 * Supabase client, the encrypted local database and the PowerSync connector are
 * built inside core/data, so this module never sees those types.
 */
val appModule: Module = module {
    single { CareCircleId("local-placeholder") }

    // Monotonic clock: elapsedRealtime is unaffected by wall-clock changes.
    single { AppLockPolicy(elapsedRealtime = SystemClock::elapsedRealtime) }
    single { AppLockController(context = androidContext(), policy = get()) }

    single { AppLanguageController(context = androidContext()) }

    // D12: Supabase session, plus the M1 sync layer (one Supabase client and one
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
    single<SyncProbeRepository> { get<DataLayer>().sync.probeNotes }

    // Needs an Activity at call time, so it is a plain collaborator rather than
    // a singleton holding one.
    single { GoogleSignIn(webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID) }

    viewModel { SignInViewModel(get()) }

    // M1 probe: the "Sync test" screen. Delete in M2 together with core.sync_probe.
    viewModel { SyncTestViewModel(get()) }
}
