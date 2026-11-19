package io.github.lgpy.familycare.feature.medications.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * The alarm fires: post the reminder for the group it carries.
 *
 * Uses `goAsync` and a short coroutine. Before posting it re-checks the local
 * dose log: a slot may have been confirmed while the alarm was pending (a
 * snoozed alarm in particular), and a reminder for a dose already given is
 * noise. Signed out means no reminder. An unparsable intent is ignored and
 * never crashes the process.
 */
class DoseReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderContract.ACTION_REMIND) return
        val group = ReminderContract.groupFrom(intent) ?: return
        val patientName = ReminderContract.patientNameFrom(intent).orEmpty()
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = ScheduledRemindersStore(appContext)
                val koin = GlobalContext.getOrNull()
                val signedIn = koin != null &&
                    koin.get<io.github.lgpy.familycare.core.data.auth.AuthRepository>()
                        .state.value is io.github.lgpy.familycare.core.domain.auth.AuthState.SignedIn
                if (!signedIn) return@launch

                val doseLog = koin.get<io.github.lgpy.familycare.feature.medications.data.DoseLogRepository>()
                val entries = doseLog.observeEntries(
                    regimenIds = group.slots.map { it.regimenId },
                    from = group.scheduledLocal.toLocalDate(),
                    to = group.scheduledLocal.toLocalDate(),
                ).first()
                val confirmed = group.slots.any { slot ->
                    io.github.lgpy.familycare.feature.medications.domain.DoseLogRules
                        .effectiveEntries(slot.slotId, entries)
                        .isNotEmpty()
                }
                if (confirmed) return@launch

                // The notification's large icon is a picture of this group's
                // pills (D33): load the looks here, on Dispatchers.IO, so the
                // builder never touches a repository on the main thread.
                val medications = koin
                    .get<io.github.lgpy.familycare.feature.medications.data.MedicationRepository>()
                    .activeMedications
                    .first()
                    .associateBy { it.medication.id.value }
                val pillLooks = group.slots.map { slot ->
                    medications[slot.medicationId.value]?.medication?.pillLook
                }

                ReminderNotifications.post(appContext, group, patientName, store, pillLooks)
            } catch (_: Exception) {
                // A receiver must not crash the process; the next reschedule and
                // the server escalation remain the safety nets.
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * "Given ✓" and "Snooze 15 min" from the notification, without opening the app
 * (docs/ux/screens.md §2).
 *
 * **Given**: if any slot of the group already has an effective entry (someone
 * else confirmed it, possibly through sync), the notification is cancelled and
 * nothing is written — a second entry would read as a double dose. Otherwise the
 * whole group is confirmed in one [io.github.lgpy.familycare.feature.medications.data.DoseLogRepository.confirm]
 * call, one entry per medication, and the notification is cleared.
 *
 * **Snooze**: local only. It schedules a one-off alarm-clock alarm 15 minutes
 * later and clears the current notification; it never delays server escalation
 * (docs/domain/care-circle.md).
 */
class DoseActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ReminderContract.ACTION_GIVE && action != ReminderContract.ACTION_SNOOZE) {
            return
        }
        val group = ReminderContract.groupFrom(intent) ?: return
        val patientName = ReminderContract.patientNameFrom(intent).orEmpty()
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val koin = GlobalContext.getOrNull()
                val store = ScheduledRemindersStore(appContext)
                val id = io.github.lgpy.familycare.feature.medications.domain.ReminderIds
                    .requestCode(group.patientId, group.slots.minOf { it.instant })

                when (action) {
                    ReminderContract.ACTION_GIVE -> {
                        if (koin != null) {
                            val doseLog = koin.get<io.github.lgpy.familycare.feature.medications.data.DoseLogRepository>()
                            val entries = doseLog.observeEntries(
                                regimenIds = group.slots.map { it.regimenId },
                                from = group.scheduledLocal.toLocalDate(),
                                to = group.scheduledLocal.toLocalDate(),
                            ).first()
                            val alreadyGiven = group.slots.any { slot ->
                                io.github.lgpy.familycare.feature.medications.domain.DoseLogRules
                                    .effectiveEntries(slot.slotId, entries)
                                    .isNotEmpty()
                            }
                            if (!alreadyGiven) {
                                doseLog.confirm(
                                    group = group,
                                    givenBy = io.github.lgpy.familycare.feature.medications.data.GivenBy.Me,
                                )
                            }
                        }
                        ReminderNotifications.cancel(appContext, id, store)
                    }

                    ReminderContract.ACTION_SNOOZE -> {
                        if (koin != null) {
                            val scheduler = koin.get<DoseReminderScheduler>()
                            scheduler.snooze(group, patientName)
                        }
                        ReminderNotifications.cancel(appContext, id, store)
                    }
                }
            } catch (_: Exception) {
                // Never crash from a notification action.
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Every reschedule trigger that is a broadcast: boot, time/time-zone changes,
 * an app update and the daily safety alarm. The app's own data changes and app
 * start are handled by [ReminderSchedulerCoordinator].
 *
 * `LOCKED_BOOT_COMPLETED` is deliberately **not** handled: the encrypted
 * database and the Keystore key are not available before the first unlock, so a
 * reschedule there would fail; `BOOT_COMPLETED` follows the unlock. The reminder
 * alarms themselves survive a reboot? They do not, which is why this receiver
 * exists.
 */
class ReminderRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val koin = GlobalContext.getOrNull()
                if (koin != null) {
                    // Signed out? reschedule() computes a null plan and cancels
                    // everything, which is exactly what should happen.
                    koin.get<DoseReminderScheduler>().reschedule()
                }
            } catch (_: Exception) {
                // A crash here would lose the reschedule; the next trigger retries.
            } finally {
                pending.finish()
            }
        }
    }
}
