package io.github.lgpy.familycare.di

import android.os.SystemClock
import io.github.lgpy.familycare.BuildConfig
import io.github.lgpy.familycare.auth.GoogleSignIn
import io.github.lgpy.familycare.core.data.SupabaseClientFactory
import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.data.auth.SupabaseAuthRepository
import io.github.lgpy.familycare.core.domain.CareCircleId
import io.github.lgpy.familycare.core.ui.lock.AppLockController
import io.github.lgpy.familycare.core.ui.lock.AppLockPolicy
import io.github.lgpy.familycare.core.ui.locale.AppLanguageController
import io.github.lgpy.familycare.ui.SignInViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The app's dependency graph. Deliberately tiny for M1: one value from the
 * pure domain module, so a broken graph fails at startup rather than later,
 * plus the app lock, language state and the Supabase session.
 *
 * Environment values come from BuildConfig (see android/environments); the
 * Supabase client itself is built in core/data so the sync layer can reuse it.
 */
val appModule: Module = module {
    single { CareCircleId("local-placeholder") }

    // Monotonic clock: elapsedRealtime is unaffected by wall-clock changes.
    single { AppLockPolicy(elapsedRealtime = SystemClock::elapsedRealtime) }
    single { AppLockController(context = androidContext(), policy = get()) }

    single { AppLanguageController(context = androidContext()) }

    // D12: Supabase session. The publishable key is public by design; RLS
    // protects the data (security-and-privacy §3).
    single<AuthRepository> {
        SupabaseAuthRepository.create(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        )
    }

    // Needs an Activity at call time, so it is a plain collaborator rather than
    // a singleton holding one.
    single { GoogleSignIn(webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID) }

    viewModel { SignInViewModel(get()) }
}
