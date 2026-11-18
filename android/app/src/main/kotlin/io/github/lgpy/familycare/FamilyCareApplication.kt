package io.github.lgpy.familycare

import android.app.Application
import io.github.lgpy.familycare.core.data.sync.SyncService
import io.github.lgpy.familycare.core.ui.lock.AppLockController
import io.github.lgpy.familycare.di.appModule
import io.github.lgpy.familycare.feature.medications.reminders.DoseReminderScheduler
import io.github.lgpy.familycare.feature.medications.reminders.ReminderSchedulerCoordinator
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * Koin is started once for the process; feature modules will contribute their
 * own Koin modules as they are added.
 */
class FamilyCareApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val koin = startKoin {
            androidContext(this@FamilyCareApplication)
            modules(appModule)
        }.koin

        // Start observing the process lifecycle before the first activity
        // starts, so a cold start begins locked (D12).
        koin.get<AppLockController>().start()

        // Sync follows the session for the life of the process: it connects when
        // the stored session is signed in, and uploads anything queued while the
        // phone had no network (M1's airplane-mode test).
        koin.get<SyncService>().start()

        // M4: schedule the dose reminders on every app start and keep them in
        // step with local/synced changes (D4/D19). Also creates the D26 channel.
        koin.get<DoseReminderScheduler>().ensureChannel()
        koin.get<ReminderSchedulerCoordinator>().start()
    }
}
