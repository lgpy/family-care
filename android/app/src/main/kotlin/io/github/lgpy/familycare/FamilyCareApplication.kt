package io.github.lgpy.familycare

import android.app.Application
import io.github.lgpy.familycare.core.data.sync.SyncService
import io.github.lgpy.familycare.core.notifications.PushHandlers
import io.github.lgpy.familycare.core.notifications.PushSession
import io.github.lgpy.familycare.core.ui.lock.AppLockController
import io.github.lgpy.familycare.di.appModule
import io.github.lgpy.familycare.feature.appointments.push.AppointmentPush
import io.github.lgpy.familycare.feature.appointments.reminders.AppointmentReminderCoordinator
import io.github.lgpy.familycare.feature.appointments.reminders.AppointmentReminderScheduler
import io.github.lgpy.familycare.feature.medications.push.PushNotifications
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

        // M6: appointment reminders follow the same rules (D4/D19/D25) on their
        // own channel, and the appointment-change push gets a channel before the
        // first message arrives, because the system draws a background one.
        koin.get<AppointmentReminderScheduler>().ensureChannel()
        koin.get<AppointmentReminderCoordinator>().start()
        AppointmentPush.ensureChannel(this)

        // M4 push (D4/D27, D29): the escalation channel Android's settings show,
        // plus token registration. Registration follows the session, so a start
        // while signed in registers (or retries) without any screen being opened.
        // M5 adds the low-stock channel beside it so a background alert has
        // somewhere to land (D28). The route table is built here too, so two
        // handlers claiming the same push type fail at start-up, not at delivery.
        PushNotifications.ensureChannel(this)
        PushNotifications.ensureStockChannel(this)
        koin.get<PushHandlers>()
        koin.get<PushSession>().start()
    }
}
