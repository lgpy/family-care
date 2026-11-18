package io.github.lgpy.familycare.feature.medications.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import java.time.Instant

/** One thing the reminder health check found wanting. */
enum class ReminderProblem {
    NOTIFICATIONS,

    /** The dose-reminders channel exists but is blocked. */
    CHANNEL,

    EXACT_ALARMS,

    BATTERY_OPTIMISATION,

    /** The alarms scheduled on the phone do not match the plan. */
    ALARMS,
}

/**
 * What the health check found (docs/build-plan.md §M4, D19).
 *
 * @param scheduledCount Alarm request codes in the local index.
 * @param plannedCount Groups the plan wants right now.
 * @param nextAlarmAt The earliest alarm the last pass wrote.
 */
data class ReminderHealth(
    val notificationsAllowed: Boolean,
    val channelEnabled: Boolean,
    val exactAlarmsAllowed: Boolean,
    val batteryOptimisationIgnored: Boolean,
    val scheduledCount: Int,
    val plannedCount: Int,
    val nextAlarmAt: Instant?,
) {

    /** Whether the phone should reliably remind as planned. */
    val alarmsInSync: Boolean get() = scheduledCount == plannedCount

    val isHealthy: Boolean
        get() = notificationsAllowed &&
            channelEnabled &&
            exactAlarmsAllowed &&
            batteryOptimisationIgnored &&
            alarmsInSync

    val problems: List<ReminderProblem> = buildList {
        if (!notificationsAllowed) add(ReminderProblem.NOTIFICATIONS)
        if (!channelEnabled) add(ReminderProblem.CHANNEL)
        if (!exactAlarmsAllowed) add(ReminderProblem.EXACT_ALARMS)
        if (!batteryOptimisationIgnored) add(ReminderProblem.BATTERY_OPTIMISATION)
        if (!alarmsInSync) add(ReminderProblem.ALARMS)
    }
}

/**
 * Checks the settings the app can verify, plus whether the scheduled alarms
 * match the plan. Everything else in the setup guide is a manual checklist.
 *
 * Called on every app start (Application starts the coordinator, the health card
 * reads this) and whenever the setup screen refreshes.
 */
class ReminderHealthChecker(
    context: Context,
    private val scheduler: DoseReminderScheduler,
    private val store: ScheduledRemindersStore,
) {

    private val applicationContext = context.applicationContext

    suspend fun check(): ReminderHealth {
        val notifications = NotificationManagerCompat.from(applicationContext)
        val channel = applicationContext
            .getSystemService(NotificationManager::class.java)
            .getNotificationChannel(ReminderContract.CHANNEL_ID)
        val plan = runCatching { scheduler.currentPlan() }.getOrNull()

        return ReminderHealth(
            notificationsAllowed = notifications.areNotificationsEnabled(),
            // A missing channel is not a problem: the next reschedule creates it.
            channelEnabled = channel == null ||
                channel.importance != NotificationManager.IMPORTANCE_NONE,
            exactAlarmsAllowed = runCatching {
                applicationContext
                    .getSystemService(AlarmManager::class.java)
                    .canScheduleExactAlarms()
            }.getOrDefault(false),
            batteryOptimisationIgnored = runCatching {
                applicationContext
                    .getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(applicationContext.packageName)
            }.getOrDefault(false),
            scheduledCount = store.scheduled().size,
            plannedCount = plan.orEmpty().size,
            nextAlarmAt = store.nextAlarmAt()?.let(Instant::ofEpochMilli),
        )
    }
}
