package io.github.lgpy.familycare.feature.medications.reminders

import android.content.Context

/**
 * The small local index of alarms this phone has scheduled (docs/build-plan.md
 * M4). It exists only so a reschedule can cancel the alarms it no longer wants:
 * the source of truth is always the freshly computed plan.
 *
 * Request codes are stored as decimal strings because a `StringSet` preference
 * is the cheapest durable store and the set stays tiny (a day or two of dose
 * times per patient). Never backed up (`allowBackup=false`) and never synced:
 * alarms are device-local (D4).
 */
class ScheduledRemindersStore(context: Context) {

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

    /** Notification ids currently showing for dose reminders. */
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

    /**
     * Records which dose slots a posted notification shows, so a server `clear`
     * for one slot can find and cancel it (M4). An entry is
     * `<notification id>|<slot key>` (see [ReminderContract.slotKey]).
     */
    fun addPostedSlots(id: Int, slotKeys: Collection<String>) {
        if (slotKeys.isEmpty()) return
        preferences.edit()
            .putStringSet(
                KEY_POSTED_SLOTS,
                postedSlots() + slotKeys.map { entry(id, it) }.toSet(),
            )
            .apply()
    }

    /** The notification id showing [slotKey], or null when nobody shows it. */
    fun notificationForSlot(slotKey: String): Int? = postedSlots()
        .firstNotNullOfOrNull { line ->
            val parts = line.split(ENTRY_SEPARATOR, limit = 2)
            if (parts.size == 2 && parts[1] == slotKey) parts[0].toIntOrNull() else null
        }

    /** Forgets a notification and every slot index entry pointing at it. */
    fun forgetNotification(id: Int) {
        preferences.edit()
            .putStringSet(
                KEY_POSTED_SLOTS,
                postedSlots().filterNot { line ->
                    line.substringBefore(ENTRY_SEPARATOR).toIntOrNull() == id
                }.toSet(),
            )
            .apply()
    }

    private fun postedSlots(): Set<String> =
        preferences.getStringSet(KEY_POSTED_SLOTS, emptySet()).orEmpty()

    private fun entry(id: Int, slotKey: String): String = "$id$ENTRY_SEPARATOR$slotKey"

    /** The earliest alarm the last scheduling pass wrote, or null. */
    fun nextAlarmAt(): Long? =
        preferences.getLong(KEY_NEXT_ALARM, 0L).takeIf { it > 0L }

    fun setNextAlarmAt(epochMillis: Long?) {
        preferences.edit().putLong(KEY_NEXT_ALARM, epochMillis ?: 0L).apply()
    }

    /** Whether a reschedule has completed at least once (health check wording). */
    fun hasScheduled(): Boolean = preferences.contains(KEY_SCHEDULED)

    private companion object {
        const val PREFERENCES_NAME = "dose_reminder_alarms"
        const val KEY_SCHEDULED = "scheduled_ids"
        const val KEY_POSTED = "posted_ids"
        const val KEY_POSTED_SLOTS = "posted_slots"
        const val KEY_NEXT_ALARM = "next_alarm_epoch_ms"

        /** Unit separator: never part of a regimen id or a wall clock. */
        const val ENTRY_SEPARATOR = "\u001F"
    }
}
