package io.github.lgpy.familycare.feature.medications.push

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.feature.medications.MedicationsRoutes
import io.github.lgpy.familycare.feature.medications.domain.PushMessage
import io.github.lgpy.familycare.feature.medications.reminders.ReminderContract

/**
 * Server escalation notifications (M4; D4/D26/D27).
 *
 * The server (`escalate/index.ts`) sends a `notification` message on the
 * `escalations` channel with a generic title and a body localized from the
 * recipient's profile. **In the foreground the system does not display
 * notification messages**, so this object draws the same notification itself:
 * same channel, same generic text (from resources, which are the same two
 * strings the server sends), and **the same tag** the server uses for that
 * patient + slot, so an escalation replaces an earlier one and a later `clear`
 * can cancel it.
 *
 * **Tag**: `escalation:<patient_id>:<instant>` — exactly
 * `escalationTag(patientId, toInstantString(instant))` in
 * `backend/supabase/functions/_shared/escalation.ts`. The tag is the badge: the
 * message carries it in `android.notification.tag`, so a foreground post uses
 * the server's value and never re-derives it. Cancelling a notification that
 * the *system* posted needs the same tag, which [PushClearWorker] rebuilds from
 * the local database (regimen → patient, wall clock → instant in the regimen's
 * zone).
 *
 * **Id**: Android identifies a notification by `(tag, id)`. Play services posts
 * a tagged FCM notification with id **0** (`DisplayNotification`), so posting
 * and cancelling both use [NOTIFICATION_ID]. Getting this wrong means a clear
 * silently leaves the escalation on screen.
 *
 * Nothing here is logged: the tag holds ids only, and the visible text is
 * generic (D4).
 */
object PushNotifications {

    /** The server's channel id for escalations (`android.notification.channel_id`). */
    const val CHANNEL_ID: String = "escalations"

    /** The id Play services pairs with a tag; see the class comment. */
    const val NOTIFICATION_ID: Int = 0

    private const val MAIN_ACTIVITY = "io.github.lgpy.familycare.MainActivity"

    /**
     * Creates the `escalations` channel (D27: "Dose alerts" / "Alertas de
     * tomas"). Called on every app start beside the dose-reminders channel;
     * re-creating an existing channel never changes the user's settings.
     */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.escalation_channel_name),
            // High: an escalation is a "someone should look at this" alert, not
            // a chat message (D4). It is not an alarm: the dose reminder itself
            // is the alarm, and the escalation is the fallback.
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.escalation_channel_description)
            enableVibration(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Shows an escalation received while the app is in the foreground; returns
     * false when notifications are disabled or the payload is unusable.
     */
    @SuppressLint("MissingPermission")
    fun postEscalation(context: Context, message: PushMessage.Escalation): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        ensureChannel(context)

        // D26-style privacy: the public (locked) version says only that a dose
        // needs attention. The unlocked text is already generic (D4), so the
        // private version is the same text.
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(context.getString(R.string.escalation_notification_title))
            .setContentText(context.getString(R.string.escalation_notification_public_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(context.getString(R.string.escalation_notification_title))
            .setContentText(context.getString(R.string.escalation_notification_body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            // The tag is the identity: a newer escalation for the same slot
            // replaces this one, and a clear removes it.
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(contentIntent(context, message))
            .build()

        manager.notify(message.tag, NOTIFICATION_ID, notification)
        return true
    }

    /**
     * Removes the escalation for [tag]: the notification the system posted for a
     * background message and the one this object posted in the foreground share
     * the tag, so both go.
     */
    fun cancel(context: Context, tag: String) {
        NotificationManagerCompat.from(context).cancel(tag, NOTIFICATION_ID)
    }

    /** Tapping opens the app on this slot's dose screen (or Today, if it is gone). */
    private fun contentIntent(
        context: Context,
        message: PushMessage.Escalation,
    ): PendingIntent {
        val intent = Intent()
            .setClassName(context, MAIN_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(
                ReminderContract.EXTRA_ROUTE,
                MedicationsRoutes.doseSlot(message.patientId, message.scheduledLocal),
            )
        return PendingIntent.getActivity(
            context,
            message.tag.hashCode() and 0x7fffffff,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
