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
 * A resolved signed-out session means no reminder; a session still loading in a
 * fresh process is awaited, and if it is unresolved the reminder is posted. An unparsable intent is ignored and never
 * crashes the process.
 */
class AppointmentReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppointmentReminderContract.ACTION_REMIND) return
        val content = AppointmentReminderContract.contentFrom(intent) ?: return
        val id = AppointmentReminderContract.notificationIdFrom(intent) ?: return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // A fresh process starts with the session still loading: wait
                // for it (bounded). Still loading after that, or no Koin at
                // all, posts from the intent: the alarm was scheduled while
                // signed in and failing to remind is the worse error. Only a
                // resolved signed-out session stays silent.
                val koin = GlobalContext.getOrNull()
                val auth = if (koin == null) {
                    AuthState.Loading
                } else {
                    AppointmentReminderDecisions.resolveAuth(koin.get<AuthRepository>().state)
                }
                if (!AppointmentReminderDecisions.shouldPost(auth)) return@launch

                AppointmentReminderNotifications.post(appContext, content, id)
                AppointmentRemindersStore(appContext).addPosted(id)
            } catch (_: Exception) {
                // A receiver must not crash the process; the next reschedule
                // and the daily safety alarm remain the safety nets.
            } finally {
                pending.finish()
            }
        }
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
                    // reschedule() waits for a session that is still loading
                    // (fresh process), cancels everything only when it resolves
                    // to signed out, and changes nothing on a failed read.
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
