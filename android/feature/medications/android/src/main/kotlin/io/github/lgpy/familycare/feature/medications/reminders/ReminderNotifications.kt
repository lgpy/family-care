package io.github.lgpy.familycare.feature.medications.reminders

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.feature.medications.domain.TimeSlotGroup
import io.github.lgpy.familycare.feature.medications.ui.formatTime

/**
 * Posts the dose notification (D26).
 *
 * - Channel [ReminderContract.CHANNEL_ID]: high importance, alarm sound, so it
 *   behaves like a reminder rather than a chat message.
 * - **Lock-screen privacy**: `VISIBILITY_PRIVATE` plus a public version that is
 *   only "Family Care · A dose is due" (pt-PT "Family Care · Há uma toma para
 *   dar"). The unlocked notification may name the patient, the time and the
 *   number of medications; the public one never does.
 * - **No health data in logs**: nothing here is logged.
 *
 * The full-screen intent is deliberately absent: the notification plus the
 * alarm-clock alarm is enough, and a full-screen takeover for a routine dose is
 * disproportionate.
 */
object ReminderNotifications {

    /** Posts (or replaces) the reminder for [group]; returns its notification id. */
    @SuppressLint("MissingPermission")
    fun post(
        context: Context,
        group: TimeSlotGroup,
        patientName: String,
        store: ScheduledRemindersStore,
    ): Int {
        val id = io.github.lgpy.familycare.feature.medications.domain.ReminderIds.requestCode(
            group.patientId,
            group.slots.minOf { it.instant },
        )
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return id

        val time = formatTime(group.scheduledLocal.toLocalTime())
        val count = group.slots.size
        val privateText = context.getString(
            R.string.reminder_notification_text,
            time,
            context.resources.getQuantityString(
                R.plurals.reminder_notification_medications,
                count,
                count,
            ),
        )
        val publicVersion = NotificationCompat.Builder(context, ReminderContract.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(context.getString(R.string.reminder_notification_public_title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notification = NotificationCompat.Builder(context, ReminderContract.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(patientName.ifBlank {
                context.getString(R.string.reminder_notification_title)
            })
            .setContentText(privateText)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setContentIntent(contentIntent(context, group, id))
            .addAction(
                0,
                context.getString(R.string.reminder_action_given),
                actionIntent(context, group, patientName, ReminderContract.ACTION_GIVE, id),
            )
            .addAction(
                0,
                context.getString(R.string.reminder_action_snooze),
                actionIntent(context, group, patientName, ReminderContract.ACTION_SNOOZE, id),
            )
            .build()

        manager.notify(id, notification)
        store.addPosted(id)
        // M4: a server `clear` finds the group notification through its slots.
        store.addPostedSlots(
            id,
            group.slots.map { slot ->
                ReminderContract.slotKey(slot.regimenId.value, slot.scheduledLocal)
            },
        )
        return id
    }

    /** Removes a shown reminder, e.g. after "Given ✓" or a sync confirmation. */
    fun cancel(context: Context, id: Int, store: ScheduledRemindersStore) {
        NotificationManagerCompat.from(context).cancel(id)
        store.removePosted(id)
        store.forgetNotification(id)
    }

    /**
     * M4 `clear`: someone confirmed `(regimenId, scheduledLocal)` on another
     * phone, so the notification of the group containing it goes.
     *
     * The lookup uses the slot index written in [post], so this needs no
     * database. Returns whether a notification was showing: a clear for a slot
     * this phone never posted is normal (the dose was confirmed before the
     * alarm, or the reminder was already gone) and changes nothing.
     */
    fun cancelSlot(
        context: Context,
        regimenId: String,
        scheduledLocal: java.time.LocalDateTime,
        store: ScheduledRemindersStore,
    ): Boolean {
        val key = ReminderContract.slotKey(regimenId, scheduledLocal)
        val id = store.notificationForSlot(key) ?: return false
        NotificationManagerCompat.from(context).cancel(id)
        store.removePosted(id)
        store.forgetNotification(id)
        return true
    }

    private fun contentIntent(context: Context, group: TimeSlotGroup, id: Int): PendingIntent {
        val intent = Intent()
            .setClassName(context, MAIN_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(ReminderContract.EXTRA_ROUTE, ReminderContract.routeFor(group))
        return PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(
        context: Context,
        group: TimeSlotGroup,
        patientName: String,
        action: String,
        id: Int,
    ): PendingIntent {
        val intent = Intent(context, DoseActionReceiver::class.java)
            .setAction(action)
            .setData(android.net.Uri.parse("familycare://dose-action/$action/$id"))
            .also { ReminderContract.putGroup(it, group, patientName) }
        val secret = "$action|$id".hashCode() and 0x7fffffff
        return PendingIntent.getBroadcast(
            context,
            secret,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val MAIN_ACTIVITY = "io.github.lgpy.familycare.MainActivity"
}
