package io.github.lgpy.familycare.feature.appointments.reminders

import android.content.Intent

/**
 * The extras and actions the appointment alarm and its notification share.
 *
 * Everything the notification needs is carried by the alarm intent: the plan is
 * computed when the alarm is scheduled (the same technique as the medications
 * reminders), so a receiver never reads the database to draw a reminder.
 * Nothing here is persisted beyond the alarm's `PendingIntent`.
 */
object AppointmentReminderContract {

    /** The notification channel (docs/domain/appointments.md "Reminders"). */
    const val CHANNEL_ID: String = "appointment_reminders"

    /** Fired by the alarm scheduled for one reminder. */
    const val ACTION_REMIND = "io.github.lgpy.familycare.action.APPOINTMENT_REMIND"

    /** Fired by the daily safety alarm and the system broadcasts. */
    const val ACTION_RESCHEDULE = "io.github.lgpy.familycare.action.RESCHEDULE_APPOINTMENT_REMINDERS"

    /**
     * Extra the app shell reads to open a screen from a notification
     * (`MainActivity` → `FamilyCareApp`). The key matches the one the
     * medications reminders use, because the shell maps a single extra; the
     * string is repeated here rather than imported, so the feature depends on no
     * other feature (D5).
     */
    const val EXTRA_ROUTE = "route"

    const val EXTRA_APPOINTMENT_ID = "appointmentId"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
    const val EXTRA_NOTIFICATION_ID = "notificationId"

    /** Writes [content] and its notification id into the alarm intent. */
    fun putContent(intent: Intent, content: AppointmentReminderContent, notificationId: Int): Intent =
        intent
            .putExtra(EXTRA_APPOINTMENT_ID, content.appointmentId)
            .putExtra(EXTRA_TITLE, content.title)
            .putExtra(EXTRA_BODY, content.body)
            .putExtra(EXTRA_NOTIFICATION_ID, notificationId)

    /** The notification content an alarm intent carries, or null when malformed. */
    fun contentFrom(intent: Intent): AppointmentReminderContent? {
        val appointmentId = intent.getStringExtra(EXTRA_APPOINTMENT_ID) ?: return null
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return null
        val body = intent.getStringExtra(EXTRA_BODY) ?: return null
        return AppointmentReminderContent(
            appointmentId = appointmentId,
            title = title,
            body = body,
            route = routeFor(appointmentId),
        )
    }

    /** The notification id an alarm intent carries, or null when malformed. */
    fun notificationIdFrom(intent: Intent): Int? =
        intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1).takeIf { it >= 0 }

    /** The route that opens this appointment's detail screen. */
    fun routeFor(appointmentId: String): String =
        io.github.lgpy.familycare.feature.appointments.AppointmentsRoutes.appointmentDetail(appointmentId)
}

/**
 * What a posted reminder shows: the patient as the title and the private line
 * that names the appointment, already localized when the alarm was scheduled.
 *
 * [route] is the module's own detail route; the shell only maps the extra.
 */
data class AppointmentReminderContent(
    val appointmentId: String,
    val title: String,
    val body: String,
    val route: String,
)
