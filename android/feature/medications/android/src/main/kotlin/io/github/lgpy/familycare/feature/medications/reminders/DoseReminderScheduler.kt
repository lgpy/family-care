package io.github.lgpy.familycare.feature.medications.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.feature.medications.data.DoseLogRepository
import io.github.lgpy.familycare.feature.medications.data.MedicationRepository
import io.github.lgpy.familycare.feature.medications.domain.ReminderGroup
import io.github.lgpy.familycare.feature.medications.domain.ReminderIds
import io.github.lgpy.familycare.feature.medications.domain.ReminderPlanning
import io.github.lgpy.familycare.feature.medications.domain.ReminderSource
import io.github.lgpy.familycare.feature.medications.domain.TimeSlotGroup
import io.github.lgpy.familycare.feature.medications.reminders.ReminderDecisions.PlanRead
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/**
 * The device's dose-reminder scheduler (D4, D19, D25).
 *
 * One alarm-clock alarm per planned time-slot group
 * (`ReminderPlanning.plan`), so the status bar shows the alarm icon and phone
 * makers are least willing to defer it. Every pass:
 *
 * 1. recomputes the plan from the local database (routing, confirmations, the
 *    next 48 h);
 * 2. cancels alarms the previous pass scheduled that are no longer wanted;
 * 3. schedules the new ones, replacing same-identity alarms;
 * 4. cancels notifications whose group is gone (confirmed by sync or removed).
 *    A group whose time has passed stays while it is still open: not confirmed
 *    and not yet missed (`ReminderPlanning.openGroups`);
 * 5. records the next alarm time for the health check.
 *
 * **Signed out, or no circle/role**: the plan is null and everything is
 * cancelled. A receiver must never crash in that state. A session that is still
 * loading (a fresh process) is awaited with a timeout and, if unresolved, or a
 * failed read, changes nothing.
 *
 * **Exactness**: [AlarmManager.setAlarmClock] is used when
 * `canScheduleExactAlarms()` allows it (D25: `USE_EXACT_ALARM`, granted at
 * install for a sideloaded reminder app). When a system build refuses it, an
 * inexact `setAndAllowWhileIdle` fallback still reminds, and the health check
 * reports the problem.
 */
class DoseReminderScheduler(
    private val context: Context,
    private val medicationRepository: MedicationRepository,
    private val doseLogRepository: DoseLogRepository,
    private val patientRepository: PatientRepository,
    private val careCircleRepository: CareCircleRepository,
    private val authRepository: AuthRepository,
    private val store: ScheduledRemindersStore,
    private val alarmManager: AlarmManager,
    private val now: () -> Instant = { Instant.now() },
) {

    private val applicationContext: Context = context.applicationContext

    /**
     * The current plan, or null when signed out / not in a circle (or the
     * session is still loading). Reads the same flows the screens do, so a
     * reminder cannot point at data the user cannot see.
     */
    suspend fun currentPlan(): List<ReminderGroup>? =
        (readPlan() as? PlanRead.Ready)?.planned

    /**
     * Reads the plan and the due-but-unconfirmed groups once. Waits for a
     * session that is still loading in a fresh process; after the timeout it
     * is [PlanRead.Unavailable], not "signed out".
     */
    private suspend fun readPlan(): PlanRead {
        val auth = ReminderDecisions.resolveAuth(authRepository.state)
        when (auth) {
            AuthState.Loading -> return PlanRead.Unavailable
            AuthState.SignedOut -> return PlanRead.NoPlan
            is AuthState.SignedIn -> Unit
        }
        val role = careCircleRepository.myRole.first() ?: return PlanRead.NoPlan

        val medications = medicationRepository.activeMedications.first()
        val patients = patientRepository.patients.first().associateBy { it.id }
        val sources = medications.map {
            ReminderSource(medication = it.medication, regimens = it.regimens)
        }
        val regimenIds = medications.flatMap { it.regimens }.map { it.id }
        val today = now().atZone(ZoneId.systemDefault()).toLocalDate()
        val entries = if (regimenIds.isEmpty()) {
            emptyList()
        } else {
            // Wide enough for a 48 h horizon even when the regimen's zone
            // differs from the phone's; observeEntries works on local dates.
            doseLogRepository.observeEntries(
                regimenIds = regimenIds,
                from = today.minusDays(2),
                to = today.plusDays(4),
            ).first()
        }

        val planned = ReminderPlanning.plan(
            now = now(),
            sources = sources,
            patients = patients,
            entries = entries,
            myUserId = auth.userId,
            myRole = role,
        )
        val open = ReminderPlanning.openGroups(
            now = now(),
            sources = sources,
            patients = patients,
            entries = entries,
            myUserId = auth.userId,
            myRole = role,
        )
        return PlanRead.Ready(planned, open)
    }

    /**
     * Recomputes the plan and rewrites the alarms and shown notifications.
     *
     * - A read that fails, or a session that is still loading, changes nothing.
     * - Signed out (resolved), or no circle/role, cancels everything.
     * - Otherwise a group whose time has passed but which is still open (not
     *   confirmed, not missed) keeps its due alarm and its notification.
     */
    suspend fun reschedule() {
        val read = ReminderDecisions.guardedRead { readPlan() }
        val ready = when (read) {
            PlanRead.Unavailable -> return
            PlanRead.NoPlan -> {
                cancelAll()
                return
            }
            is PlanRead.Ready -> read
        }
        val planned = ready.planned
        val previous = store.scheduled()
        val plannedIds = planned.map { ReminderIds.requestCode(it) }.toSet()
        val names = runCatching {
            patientRepository.patients.first().associate { it.id to it.displayName }
        }.getOrDefault(emptyMap())

        // Alarms and notifications that are neither planned nor open: the slot
        // synced as confirmed or went missed, the medication/patient went away.
        val alarmsToCancel = ReminderPlanning.idsToCancel(previous, planned, ready.open)
        alarmsToCancel.forEach { cancelAlarm(it) }
        ReminderPlanning.idsToCancel(store.posted(), planned, ready.open)
            .forEach { cancelNotification(it) }

        ensureChannel()

        planned.forEach { group ->
            scheduleGroup(group, names[group.patientId].orEmpty())
        }
        store.replaceScheduled(plannedIds + (previous - alarmsToCancel))
        store.setNextAlarmAt(planned.firstOrNull()?.instant?.toEpochMilli())
        scheduleDailySafety()
    }

    /** Schedules a one-off reminder [minutes] after [now] (local snooze, D26). */
    fun snooze(group: TimeSlotGroup, patientName: String, minutes: Long = SNOOZE_MINUTES) {
        val triggerAt = now().plusSeconds(minutes * 60L)
        val requestCode = snoozeRequestCode(
            ReminderIds.requestCode(group.patientId, group.instantOfGroup()),
            triggerAt,
        )
        schedule(
            requestCode = requestCode,
            triggerAtMillis = triggerAt.toEpochMilli(),
            intent = alarmIntent(group, patientName, requestCode),
        )
    }

    /** Cancels every alarm and posted reminder, e.g. on sign-out. */
    suspend fun cancelAll() {
        store.scheduled().forEach { cancelAlarm(it) }
        store.posted().forEach { cancelNotification(it) }
        store.replaceScheduled(emptySet())
        store.setNextAlarmAt(null)
    }

    private fun scheduleGroup(group: ReminderGroup, patientName: String) {
        val timeSlotGroup = TimeSlotGroup(
            patientId = group.patientId,
            scheduledLocal = group.scheduledLocal,
            slots = group.slots,
        )
        val requestCode = ReminderIds.requestCode(group)
        schedule(
            requestCode = requestCode,
            triggerAtMillis = group.instant.toEpochMilli(),
            intent = alarmIntent(timeSlotGroup, patientName, requestCode),
        )
    }

    private fun schedule(requestCode: Int, triggerAtMillis: Long, intent: Intent) {
        val operation = alarmPendingIntent(intent, requestCode)
        val exactAllowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (exactAllowed) {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent(intent, requestCode)),
                operation,
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                operation,
            )
        }
    }

    private fun alarmIntent(
        group: TimeSlotGroup,
        patientName: String,
        requestCode: Int,
    ): Intent = Intent(applicationContext, DoseReminderReceiver::class.java)
        .setAction(ReminderContract.ACTION_REMIND)
        .setData(Uri.parse("familycare://dose-reminder/$requestCode"))
        .also { ReminderContract.putGroup(it, group, patientName) }

    private fun alarmPendingIntent(intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            applicationContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Tapping the alarm-clock icon opens the dose-slot screen. */
    private fun showIntent(intent: Intent, requestCode: Int): PendingIntent {
        val group = ReminderContract.groupFrom(intent)
        val open = Intent()
            .setClassName(applicationContext, MAIN_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (group != null) open.putExtra(ReminderContract.EXTRA_ROUTE, ReminderContract.routeFor(group))
        return PendingIntent.getActivity(
            applicationContext,
            requestCode,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelAlarm(requestCode: Int) {
        val intent = Intent(applicationContext, DoseReminderReceiver::class.java)
            .setAction(ReminderContract.ACTION_REMIND)
            .setData(Uri.parse("familycare://dose-reminder/$requestCode"))
        val operation = PendingIntent.getBroadcast(
            applicationContext,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (operation != null) {
            alarmManager.cancel(operation)
            operation.cancel()
        }
    }

    private fun cancelNotification(id: Int) {
        NotificationManagerCompat.from(applicationContext).cancel(id)
        store.removePosted(id)
        store.forgetNotification(id)
    }

    /**
     * A daily inexact alarm so a missed system broadcast cannot leave the phone
     * without alarms for long. It is not an alarm-clock alarm: it must not show
     * the alarm icon or ring, and it only reschedules.
     */
    private fun scheduleDailySafety() {
        val intent = Intent(applicationContext, ReminderRescheduleReceiver::class.java)
            .setAction(ReminderContract.ACTION_RESCHEDULE)
        val operation = PendingIntent.getBroadcast(
            applicationContext,
            DAILY_SAFETY_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            now().plusSeconds(DAILY_SAFETY_SECONDS).toEpochMilli(),
            operation,
        )
    }

    /** Creates the D26 channel once; re-creating it does not change user settings. */
    fun ensureChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            ReminderContract.CHANNEL_ID,
            applicationContext.getString(io.github.lgpy.familycare.core.ui.R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = applicationContext.getString(
                io.github.lgpy.familycare.core.ui.R.string.reminder_channel_description,
            )
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    private fun TimeSlotGroup.instantOfGroup(): Instant =
        slots.minOfOrNull { it.instant } ?: Instant.now()

    private companion object {
        const val MAIN_ACTIVITY = "io.github.lgpy.familycare.MainActivity"
        const val SNOOZE_MINUTES = 15L
        const val DAILY_SAFETY_REQUEST_CODE = 0x46434A53
        const val DAILY_SAFETY_SECONDS = 24L * 60 * 60

        /**
         * A snooze alarm must not replace the original group alarm, and a second
         * snooze of the same group must not replace the first. Deriving the code
         * from the group and the snooze instant satisfies both.
         */
        fun snoozeRequestCode(groupCode: Int, triggerAt: Instant): Int =
            "snooze|$groupCode|${triggerAt.epochSecond}".hashCode() and 0x7fffffff
    }
}
