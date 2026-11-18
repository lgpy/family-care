package io.github.lgpy.familycare.feature.appointments.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.domain.auth.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * The alarm fires: post the reminder it carries.
 *
 * The plans are computed when the alarms are scheduled, so this needs no
 * database read: the intent already holds the localized text and the route.
 * Signed out means no reminder. An unparsable intent is ignored and never
 * crashes the process.
 */
class AppointmentReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppointmentReminderContract.ACTION_REMIND) return
        val content = AppointmentReminderContract.contentFrom(intent) ?: return
        val id = AppointmentReminderContract.notificationIdFrom(intent) ?: return
        val koin = GlobalContext.getOrNull() ?: return
        val signedIn = koin.get<AuthRepository>().state.value is AuthState.SignedIn
        if (!signedIn) return

        val appContext = context.applicationContext
        AppointmentReminderNotifications.post(appContext, content, id)
        AppointmentRemindersStore(appContext).addPosted(id)
    }
}

/**
 * Every reschedule trigger that is a broadcast: boot, time/time-zone changes,
 * an app update and the daily safety alarm. The app's own data changes and app
 * start are handled by [AppointmentReminderCoordinator].
 *
 * `LOCKED_BOOT_COMPLETED` is deliberately **not** handled: the encrypted
 * database and the Keystore key are not available before the first unlock, so a
 * reschedule there would fail; `BOOT_COMPLETED` follows the unlock. The alarms
 * themselves do not survive a reboot, which is why this receiver exists.
 */
class AppointmentRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val koin = GlobalContext.getOrNull()
                if (koin != null) {
                    // Signed out? reschedule() computes a null plan and cancels
                    // everything, which is exactly what should happen.
                    koin.get<AppointmentReminderScheduler>().reschedule()
                }
            } catch (_: Exception) {
                // A crash here would lose the reschedule; the next trigger retries.
            } finally {
                pending.finish()
            }
        }
    }
}
