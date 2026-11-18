package io.github.lgpy.familycare.feature.appointments.reminders

import android.content.Context

/**
 * The small local index of appointment alarms this phone has scheduled,
 * following the technique of the medications reminders (written again here
 * because D5 forbids importing the medications feature).
 *
 * Its only purpose is to let a reschedule cancel the alarms — and the shown
 * notifications — it no longer wants: the source of truth is always the freshly
 * computed plan. Request codes are stored as decimal strings because a
 * `StringSet` preference is the cheapest durable store and the set stays tiny
 * (a week and a half of two reminders each). Never backed up
 * (`allowBackup=false`) and never synced: alarms are device-local (D4).
 */
class AppointmentRemindersStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Request codes currently scheduled. */
    fun scheduled(): Set<Int> = preferences.getStringSet(KEY_SCHEDULED, emptySet())
        .orEmpty()
        .mapNotNull { it.toIntOrNull() }
        .toSet()

    /** Replaces the scheduled set, dropping stale entries. */
    fun replaceScheduled(codes: Set<Int>) {
        preferences.edit()
            .putStringSet(KEY_SCHEDULED, codes.map { it.toString() }.toSet())
            .apply()
    }

    /** Notification ids currently showing for appointment reminders. */
    fun posted(): Set<Int> = preferences.getStringSet(KEY_POSTED, emptySet())
        .orEmpty()
        .mapNotNull { it.toIntOrNull() }
        .toSet()

    fun addPosted(id: Int) {
        preferences.edit()
            .putStringSet(KEY_POSTED, (posted() + id).map { it.toString() }.toSet())
            .apply()
    }

    fun removePosted(id: Int) {
        preferences.edit()
            .putStringSet(KEY_POSTED, (posted() - id).map { it.toString() }.toSet())
            .apply()
    }

    /** The earliest alarm the last scheduling pass wrote, or null. */
    fun nextAlarmAt(): Long? =
        preferences.getLong(KEY_NEXT_ALARM, 0L).takeIf { it > 0L }

    fun setNextAlarmAt(epochMillis: Long?) {
        preferences.edit().putLong(KEY_NEXT_ALARM, epochMillis ?: 0L).apply()
    }

    /** Whether a reschedule has completed at least once. */
    fun hasScheduled(): Boolean = preferences.contains(KEY_SCHEDULED)

    private companion object {
        const val PREFERENCES_NAME = "appointment_reminder_alarms"
        const val KEY_SCHEDULED = "scheduled_ids"
        const val KEY_POSTED = "posted_ids"
        const val KEY_NEXT_ALARM = "next_alarm_epoch_ms"
    }
}
