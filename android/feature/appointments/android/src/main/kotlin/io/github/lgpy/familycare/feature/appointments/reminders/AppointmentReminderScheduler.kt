package io.github.lgpy.familycare.feature.appointments.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.lgpy.familycare.core.data.auth.AuthRepository
import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.CareCircleRepository
import io.github.lgpy.familycare.core.domain.circle.PatientRepository
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.feature.appointments.AppointmentsRoutes
import io.github.lgpy.familycare.feature.appointments.data.AppointmentRepository
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentReminder
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentReminderIds
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentReminderKind
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentReminderPlanning
import io.github.lgpy.familycare.feature.appointments.reminders.AppointmentReminderDecisions.PlanRead
import io.github.lgpy.familycare.feature.appointments.ui.formatAppointmentTime
import io.github.lgpy.familycare.feature.appointments.ui.labelRes
import java.time.Instant
import kotlinx.coroutines.flow.first

/**
 * The device's appointment-reminder scheduler (docs/domain/appointments.md
 * "Reminders"; D4, D19, D25).
 *
 * One alarm-clock alarm per planned reminder
 * ([AppointmentReminderPlanning.plan]), so the status bar shows the alarm icon
 * and phone makers are least willing to defer it. Every pass:
 *
 * 1. recomputes the plan from the local database (routing, cancelled, the next
 *    eight days);
 * 2. cancels alarms the previous pass scheduled that are no longer wanted;
 * 3. schedules the new ones, replacing same-identity alarms;
 * 4. cancels notifications whose reminder is gone (the appointment was
 *    cancelled, moved, deleted, started, the companion changed, or the phone is
 *    signed out). A reminder whose time has passed stays until then
 *    (`AppointmentReminderPlanning.openReminders`);
 * 5. records the next alarm time and re-arms the daily safety alarm.
 *
 * **Signed out, or no circle/role**: the plan is null and everything is
 * cancelled. A receiver must never crash in that state. A session that is still
 * loading (a fresh process) is awaited with a timeout and, if unresolved, or a
 * failed read, changes nothing.
 *
 * **Exactness**: [AlarmManager.setAlarmClock] is used when
 * `canScheduleExactAlarms()` allows it (D25: `USE_EXACT_ALARM`, granted at
 * install for a sideloaded reminder app). When a system build refuses it, an
 * inexact `setAndAllowWhileIdle` fallback still reminds.
 *
 * This is new code written from the technique of the medications scheduler, not
 * an import of it: a feature module depends only on the core (D5).
 */
class AppointmentReminderScheduler(
    private val context: Context,
    private val appointmentRepository: AppointmentRepository,
    private val patientRepository: PatientRepository,
    private val careCircleRepository: CareCircleRepository,
    private val authRepository: AuthRepository,
    private val store: AppointmentRemindersStore,
    private val alarmManager: AlarmManager,
    private val now: () -> Instant = { Instant.now() },
) {

    private val applicationContext: Context = context.applicationContext

    /**
     * The current plan, or null when signed out / not in a circle (or the
     * session is still loading). Reads the same flows the screens do, so a
     * reminder cannot point at data the user cannot see.
     */
    suspend fun currentPlan(): List<AppointmentReminder>? =
        (readPlan() as? PlanRead.Ready)?.planned

    /**
     * Reads the plan and the open reminders once. Waits for a session that is
     * still loading in a fresh process; after the timeout it is
     * [PlanRead.Unavailable], not "signed out".
     */
    private suspend fun readPlan(): PlanRead {
        val auth = AppointmentReminderDecisions.resolveAuth(authRepository.state)
        when (auth) {
            AuthState.Loading -> return PlanRead.Unavailable
            AuthState.SignedOut -> return PlanRead.NoPlan
            is AuthState.SignedIn -> Unit
        }
        val role = careCircleRepository.myRole.first() ?: return PlanRead.NoPlan

        val appointments = appointmentRepository.observeAgenda(includeCancelled = true)
            .first()
            .map { it.appointment }
        val names = patientRepository.patients.first().associate { it.id to it.displayName }

        val planned = AppointmentReminderPlanning.plan(
            now = now(),
            appointments = appointments,
            myUserId = auth.userId,
            myRole = role,
        )
        val open = AppointmentReminderPlanning.openReminders(
            now = now(),
            appointments = appointments,
            myUserId = auth.userId,
            myRole = role,
        )
        return PlanRead.Ready(planned = planned, open = open, names = names)
    }

    /**
     * Recomputes the plan and rewrites the alarms and shown notifications.
     *
     * - A read that fails, or a session that is still loading, changes nothing.
     * - Signed out (resolved), or no circle/role, cancels everything.
     * - Otherwise a reminder whose time has passed stays until its appointment
     *   has started (or ended), is cancelled, moved, or no longer routed here.
     */
    suspend fun reschedule() {
        val ready = when (val read = AppointmentReminderDecisions.guardedRead { readPlan() }) {
            PlanRead.Unavailable -> return
            PlanRead.NoPlan -> {
                cancelAll()
                return
            }
            is PlanRead.Ready -> read
        }
        val names = ready.names

        val planned = AppointmentReminderDecisions.nameable(ready.planned, names)
        val open = AppointmentReminderDecisions.nameable(ready.open, names)
        val plannedIds = planned.map { AppointmentReminderIds.requestCode(it) }.toSet()
        val previous = store.scheduled()

        // Alarms and notifications that are neither planned nor open.
        val alarmsToCancel = AppointmentReminderPlanning.idsToCancel(previous, planned, open)
        alarmsToCancel.forEach { cancelAlarm(it) }
        AppointmentReminderPlanning.idsToCancel(store.posted(), planned, open)
            .forEach { cancelNotification(it) }

        ensureChannel()

        planned.forEach { reminder ->
            scheduleReminder(reminder, names[reminder.patientId].orEmpty())
        }
        store.replaceScheduled(plannedIds + (previous - alarmsToCancel))
        // The plan is sorted by instant.
        store.setNextAlarmAt(planned.firstOrNull()?.instant?.toEpochMilli())
        scheduleDailySafety()
    }

    /** Cancels every alarm and posted reminder, e.g. on sign-out. */
    suspend fun cancelAll() {
        store.scheduled().forEach { cancelAlarm(it) }
        store.posted().forEach { cancelNotification(it) }
        store.replaceScheduled(emptySet())
        store.setNextAlarmAt(null)
    }

    /** Creates the D26 channel once; re-creating it does not change user settings. */
    fun ensureChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            AppointmentReminderContract.CHANNEL_ID,
            applicationContext.getString(R.string.appointments_reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = applicationContext.getString(
                R.string.appointments_reminder_channel_description,
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

    private fun scheduleReminder(reminder: AppointmentReminder, patientName: String) {
        val requestCode = AppointmentReminderIds.requestCode(reminder)
        schedule(
            requestCode = requestCode,
            triggerAtMillis = reminder.instant.toEpochMilli(),
            intent = alarmIntent(reminder, patientName, requestCode),
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
        reminder: AppointmentReminder,
        patientName: String,
        requestCode: Int,
    ): Intent = Intent(applicationContext, AppointmentReminderReceiver::class.java)
        .setAction(AppointmentReminderContract.ACTION_REMIND)
        .setData(Uri.parse("familycare://appointment-reminder/$requestCode"))
        .also {
            AppointmentReminderContract.putContent(
                it,
                content(reminder, patientName),
                requestCode,
            )
        }

    /** What this reminder's notification says, localized when the alarm is set. */
    private fun content(
        reminder: AppointmentReminder,
        patientName: String,
    ): AppointmentReminderContent {
        val appointment = reminder.appointment
        val label = appointment.title ?: applicationContext.getString(appointment.kind.labelRes())
        val time = formatAppointmentTime(appointment.localTime)
        val place = appointment.place
        val body = when (reminder.kind) {
            AppointmentReminderKind.EVENING_BEFORE -> if (place.isNullOrBlank()) {
                applicationContext.getString(
                    R.string.appointments_reminder_text_tomorrow,
                    patientName,
                    label,
                    time,
                )
            } else {
                applicationContext.getString(
                    R.string.appointments_reminder_text_tomorrow_place,
                    patientName,
                    label,
                    time,
                    place,
                )
            }

            AppointmentReminderKind.HOUR_BEFORE -> if (place.isNullOrBlank()) {
                applicationContext.getString(
                    R.string.appointments_reminder_text_in_hour,
                    patientName,
                    label,
                )
            } else {
                applicationContext.getString(
                    R.string.appointments_reminder_text_in_hour_place,
                    patientName,
                    label,
                    place,
                )
            }
        }

        return AppointmentReminderContent(
            appointmentId = appointment.id.value,
            title = patientName,
            body = body,
            route = AppointmentsRoutes.appointmentDetail(appointment.id.value),
        )
    }

    private fun alarmPendingIntent(intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            applicationContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Tapping the alarm-clock icon opens the appointment. */
    private fun showIntent(intent: Intent, requestCode: Int): PendingIntent {
        val content = AppointmentReminderContract.contentFrom(intent)
        val open = Intent()
            .setClassName(applicationContext, MAIN_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (content != null) {
            open.putExtra(AppointmentReminderContract.EXTRA_ROUTE, content.route)
        }
        return PendingIntent.getActivity(
            applicationContext,
            requestCode,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelAlarm(requestCode: Int) {
        val intent = Intent(applicationContext, AppointmentReminderReceiver::class.java)
            .setAction(AppointmentReminderContract.ACTION_REMIND)
            .setData(Uri.parse("familycare://appointment-reminder/$requestCode"))
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
        AppointmentReminderNotifications.cancel(applicationContext, id)
        store.removePosted(id)
    }

    /**
     * A daily inexact alarm so a missed system broadcast cannot leave the phone
     * without alarms for long. It is not an alarm-clock alarm: it must not show
     * the alarm icon or ring, and it only reschedules.
     */
    private fun scheduleDailySafety() {
        val intent = Intent(applicationContext, AppointmentRescheduleReceiver::class.java)
            .setAction(AppointmentReminderContract.ACTION_RESCHEDULE)
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

    private companion object {
        const val MAIN_ACTIVITY = "io.github.lgpy.familycare.MainActivity"
        const val DAILY_SAFETY_REQUEST_CODE = 0x46434A41
        const val DAILY_SAFETY_SECONDS = 24L * 60 * 60
    }
}
