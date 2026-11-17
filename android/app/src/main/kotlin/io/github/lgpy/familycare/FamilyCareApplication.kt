package io.github.lgpy.familycare

import android.app.Application
import io.github.lgpy.familycare.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * Koin is started once for the process; feature modules will contribute their
 * own Koin modules as they are added.
 */
class FamilyCareApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@FamilyCareApplication)
            modules(appModule)
        }
    }
}
