package io.github.lgpy.familycare.di

import android.os.SystemClock
import io.github.lgpy.familycare.core.domain.CareCircleId
import io.github.lgpy.familycare.core.ui.lock.AppLockController
import io.github.lgpy.familycare.core.ui.lock.AppLockPolicy
import io.github.lgpy.familycare.core.ui.locale.AppLanguageController
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The app's dependency graph. Deliberately tiny for M1: one value from the
 * pure domain module, so a broken graph fails at startup rather than later,
 * plus the app lock and language state that live in core/ui.
 */
val appModule: Module = module {
    single { CareCircleId("local-placeholder") }

    // Monotonic clock: elapsedRealtime is unaffected by wall-clock changes.
    single { AppLockPolicy(elapsedRealtime = SystemClock::elapsedRealtime) }
    single { AppLockController(context = androidContext(), policy = get()) }

    single { AppLanguageController(context = androidContext()) }
}
