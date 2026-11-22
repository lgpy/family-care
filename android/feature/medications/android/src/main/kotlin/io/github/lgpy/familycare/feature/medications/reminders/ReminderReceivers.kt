package io.github.lgpy.familycare.feature.medications.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.feature.medications.data.DoseLogRepository
import io.github.lgpy.familycare.feature.medications.data.GivenBy
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.domain.DoseLogRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext

/**
 * The alarm fires: post the reminder for the group it carries.
 *
 * Uses `goAsync` and a short coroutine. Before posting it re-checks the local
 * dose log: a slot may have been confirmed while the alarm was pending (a
 * snoozed alarm in particular), and a reminder for a dose already given is
 * noise. A resolved signed-out session means no reminder; a session still
 * loading in a fresh process is awaited, and if it is unresolved the reminder is
 * posted from the intent. An unparsable intent is ignored and
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
                // A fresh process starts with the session still loading: wait
                // for it (bounded). Still loading after that, or no Koin at
                // all, posts from the intent: the alarm was scheduled while
                // signed in and failing to remind is the worse error.
                val auth = if (koin == null) {
                    AuthState.Loading
                } else {
                    ReminderDecisions.resolveAuth(koin.get<AuthRepository>().state)
                }
                if (!ReminderDecisions.shouldPost(auth, alreadyConfirmed = null)) return@launch

                // Re-check the dose log; when it cannot be read in time the
                // reminder is posted anyway.
                val confirmed: Boolean? = if (koin == null) {
                    null
                } else {
                    withTimeoutOrNull(DOSE_LOG_TIMEOUT_MILLIS) {
                        runCatching {
                            val entries = koin.get<DoseLogRepository>().observeEntries(
                                regimenIds = group.slots.map { it.regimenId },
                                from = group.scheduledLocal.toLocalDate(),
                                to = group.scheduledLocal.toLocalDate(),
                            ).first()
                            group.slots.any { slot ->
                                DoseLogRules.effectiveEntries(slot.slotId, entries).isNotEmpty()
                            }
                        }.getOrNull()
                    }
                }
                if (!ReminderDecisions.shouldPost(auth, confirmed)) return@launch

                // The notification's large icon is a picture of this group's
                // pills (D33): load the looks here, on Dispatchers.IO, so the
                // builder never touches a repository on the main thread.
                val pillLooks = if (koin == null) {
                    emptyList()
                } else {
                    withTimeoutOrNull(DOSE_LOG_TIMEOUT_MILLIS) {
                        runCatching {
                            val medications = koin.get<MedicationRepository>()
                                .activeMedications
                                .first()
                                .associateBy { it.medication.id.value }
                            group.slots.map { slot ->
                                medications[slot.medicationId.value]?.medication?.pillLook
                            }
                        }.getOrNull()
                    }.orEmpty()
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

/** Bound for each local read of the alarm receiver, inside the `goAsync()` window. */
private const val DOSE_LOG_TIMEOUT_MILLIS = 2_000L

/**
 * "Given ✓" and "Snooze 15 min" from the notification, without opening the app
 * (docs/ux/screens.md §2).
 *
 * **Given**: if any slot of the group already has an effective entry (someone
 * else confirmed it, possibly through sync), the notification is cancelled and
 * nothing is written — a second entry would read as a double dose. Otherwise the
 * whole group is confirmed in one [io.github.lgpy.familycare.feature.medications.data.DoseLogRepository.confirm]
 * call, one entry per medication, and the notification is cleared **only if
 * that write succeeded** — a failed write leaves it showing.
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
                        // The notification goes only when the dose is on record:
                        // the write succeeded or the group was already
                        // confirmed. A failed write (viewer, medication gone,
                        // validation, database) or no Koin leaves it showing.
                        if (koin != null) {
                            // A cold process may still be loading the session
                            // the write needs: wait for it (bounded).
                            ReminderDecisions.resolveAuth(koin.get<AuthRepository>().state)
                            val doseLog = koin.get<DoseLogRepository>()
                            val entries = doseLog.observeEntries(
                                regimenIds = group.slots.map { it.regimenId },
                                from = group.scheduledLocal.toLocalDate(),
                                to = group.scheduledLocal.toLocalDate(),
                            ).first()
                            val alreadyGiven = group.slots.any { slot ->
                                DoseLogRules.effectiveEntries(slot.slotId, entries).isNotEmpty()
                            }
                            val write = if (alreadyGiven) {
                                null
                            } else {
                                doseLog.confirm(group = group, givenBy = GivenBy.Me)
                            }
                            if (ReminderDecisions.shouldCancelAfterGive(alreadyGiven, write)) {
                                ReminderNotifications.cancel(appContext, id, store)
                            }
                        }
                    }

                    ReminderContract.ACTION_SNOOZE -> {
                        // Without the scheduler no snooze alarm exists, so the
                        // notification must stay rather than vanish.
                        if (koin != null) {
                            koin.get<DoseReminderScheduler>().snooze(group, patientName)
                            ReminderNotifications.cancel(appContext, id, store)
                        }
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
                    // reschedule() waits for a session that is still loading
                    // (fresh process), cancels everything only when it resolves
                    // to signed out, and changes nothing on a failed read.
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
