package io.github.lgpy.familycare.feature.appointments.push

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.lgpy.familycare.core.notifications.PushHandler
import io.github.lgpy.familycare.core.ui.R
import io.github.lgpy.familycare.feature.appointments.AppointmentsRoutes
import io.github.lgpy.familycare.feature.appointments.reminders.AppointmentReminderContract

/**
 * The appointments share of the app's single FCM entry point (M6; D29).
 *
 * The server posts `type=appointment_changed` to the circle's owners and
 * caregivers when an appointment is added or changed (docs/domain/appointments.md
 * "Change push"). The payload is generic: the text is the same "An appointment
 * was added or changed" for everyone, and **in the foreground the system does not
 * display notification messages**, so this handler draws the same notification
 * itself.
 *
 * **Tag**: the message's `android.notification.tag`, so a newer push for the
 * same appointment replaces the older one and the system's own background
 * notification and ours never duplicate. Without a tag the notification is
 * untagged, exactly as Play services posts it.
 *
 * Nothing here is logged, and the text carries no patient or appointment detail
 * (D4/D26).
 */
class AppointmentsPushHandler : PushHandler {

    override val types: Set<String> = setOf(TYPE_APPOINTMENT_CHANGED)

    override fun handle(
        context: Context,
        data: Map<String, String>,
        notificationTag: String?,
    ) {
        if (data[TYPE_KEY] != TYPE_APPOINTMENT_CHANGED) return
        AppointmentPush.postUpdate(context, notificationTag)
    }

    companion object {
        /** The server's message type (`appointment-changed` edge function). */
        const val TYPE_APPOINTMENT_CHANGED: String = "appointment_changed"

        /** The `type` key of the FCM data map. */
        const val TYPE_KEY: String = "type"
    }
}

/**
 * Draws and channels the appointment-change push.
 *
 * The channel exists before any message arrives because the system draws a
 * background notification itself; `FamilyCareApplication` calls [ensureChannel]
 * on every start, beside the reminders channel. Re-creating an existing channel
 * never changes the user's settings.
 */
object AppointmentPush {

    /** The server's channel id for appointment changes. */
    const val CHANNEL_ID: String = "appointment_updates"

    private const val MAIN_ACTIVITY = "io.github.lgpy.familycare.MainActivity"

    /** Creates the `appointment_updates` channel ("Appointment changes"). */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.appointments_updates_channel_name),
            // Default, not high: a change is something to look at later, not an
            // alarm. The reminder itself is the alarm.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.appointments_updates_channel_description)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Shows the generic change notification received in the foreground; returns
     * false when notifications are disabled.
     */
    @SuppressLint("MissingPermission")
    fun postUpdate(context: Context, notificationTag: String?): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        ensureChannel(context)

        // The text is generic (D4), so the public version is identical (D26).
        val body = context.getString(R.string.appointments_push_body)
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(contentIntent(context, notificationTag))
            .build()

        if (notificationTag == null) {
            manager.notify(NOTIFICATION_ID, notification)
        } else {
            manager.notify(notificationTag, NOTIFICATION_ID, notification)
        }
        return true
    }

    /** Tapping opens the app on the Agenda. */
    private fun contentIntent(context: Context, notificationTag: String?): PendingIntent {
        val intent = Intent()
            .setClassName(context, MAIN_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(AppointmentReminderContract.EXTRA_ROUTE, AppointmentsRoutes.AGENDA)
        return PendingIntent.getActivity(
            context,
            (notificationTag?.hashCode() ?: 0) and 0x7fffffff,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * The id Play services pairs with a tagged notification; a tagged push is
     * posted by the system with id 0, so ours must match for a replacement.
     */
    private const val NOTIFICATION_ID: Int = 0
}
