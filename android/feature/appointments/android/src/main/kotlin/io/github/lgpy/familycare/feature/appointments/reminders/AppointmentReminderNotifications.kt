package io.github.lgpy.familycare.feature.appointments.reminders

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.lgpy.familycare.core.ui.R

/**
 * Posts one appointment reminder (docs/domain/appointments.md "Reminders").
 *
 * - Channel [AppointmentReminderContract.CHANNEL_ID]: high importance, so it
 *   behaves like a reminder rather than a chat message.
 * - **Lock-screen privacy (D26)**: `VISIBILITY_PRIVATE` plus a public version
 *   that says only "Family Care · Appointment reminder" (pt-PT "Family Care ·
 *   Lembrete de consulta"). The unlocked notification may name the patient, the
 *   appointment and the place; the public one never does.
 * - **No health data in logs**: nothing here is logged.
 *
 * The full-screen intent is deliberately absent: the notification plus the
 * alarm-clock alarm is enough, exactly as for the dose reminders.
 */
object AppointmentReminderNotifications {

    /** Posts (or replaces) the reminder [content] under [id]. */
    @SuppressLint("MissingPermission")
    fun post(context: Context, content: AppointmentReminderContent, id: Int) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val publicVersion = NotificationCompat.Builder(
            context,
            AppointmentReminderContract.CHANNEL_ID,
        )
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(context.getString(R.string.appointments_reminder_public_title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notification = NotificationCompat.Builder(
            context,
            AppointmentReminderContract.CHANNEL_ID,
        )
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(contentIntent(context, content, id))
            .build()

        manager.notify(id, notification)
    }

    /** Removes a shown reminder, e.g. after the appointment was cancelled. */
    fun cancel(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    private fun contentIntent(
        context: Context,
        content: AppointmentReminderContent,
        id: Int,
    ): PendingIntent {
        val intent = Intent()
            .setClassName(context, MAIN_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(AppointmentReminderContract.EXTRA_ROUTE, content.route)
        return PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val MAIN_ACTIVITY = "io.github.lgpy.familycare.MainActivity"
}
