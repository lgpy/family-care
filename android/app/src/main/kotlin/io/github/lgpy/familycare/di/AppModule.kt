package io.github.lgpy.familycare.di

import io.github.lgpy.familycare.core.domain.CareCircleId
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The app's dependency graph. Deliberately tiny for M1: one value from the
 * pure domain module, so a broken graph fails at startup rather than later.
 */
val appModule: Module = module {
    single { CareCircleId("local-placeholder") }
}
