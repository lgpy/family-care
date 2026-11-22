package io.github.lgpy.familycare.feature.appointments.domain

import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.core.domain.circle.PatientId
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * Which of the two reminders an appointment has (docs/domain/appointments.md
 * "Reminders").
 *
 * - [EVENING_BEFORE]: 20:00 local on the day before, in the appointment's own
 *   zone, so a trip or a DST change never moves it.
 * - [HOUR_BEFORE]: one hour before the start instant.
 */
enum class AppointmentReminderKind {
    EVENING_BEFORE,
    HOUR_BEFORE,
}

/**
 * One reminder this phone should schedule: the appointment, which of its two
 * reminders, and the absolute instant the alarm fires.
 *
 * The appointment is carried whole so the scheduler can build the notification
 * text (patient, title/kind, time, place) without another database read, and so
 * a test can assert on the reminder without looking up anything.
 */
data class AppointmentReminder(
    val appointment: Appointment,
    val kind: AppointmentReminderKind,
    val instant: Instant,
) {

    val appointmentId: AppointmentId get() = appointment.id

    val patientId: PatientId get() = appointment.patientId
}

/**
 * Identifier rules shared by the alarm, its notification and the pending
 * intents (the same technique as the medications reminders).
 *
 * A 32-bit request code cannot be a collision-free identity for arbitrary
 * appointment ids; the plan is small (eight days of appointments), so the
 * derived code is stable and collisions are practically impossible. The
 * scheduled index stores the same codes, which keeps cancellation in step with
 * scheduling.
 */
object AppointmentReminderIds {

    /** The stable request code (and notification id) of [reminder]. */
    fun requestCode(reminder: AppointmentReminder): Int =
        "${reminder.appointmentId.value}|${reminder.kind.name}|${reminder.instant.epochSecond}"
            .hashCode() and 0x7fffffff
}

/**
 * The local reminder plan for the next [DEFAULT_HORIZON]: which of this phone's
 * reminders to schedule, already filtered by routing and by instants that have
 * passed.
 *
 * ### Who is reminded (docs/domain/appointments.md "Reminders")
 * - The **companion**, when the appointment has one — nobody else, even the
 *   other owners.
 * - Otherwise every owner and caregiver. Never a viewer.
 *
 * The caller passes only appointments of the signed-in user's circles, so the
 * patient needs no further filter here.
 *
 * ### What is excluded
 * - Cancelled appointments ([Appointment.isCancelled]): a cancelled appointment
 *   has no reminders.
 * - A reminder whose instant is already past, or beyond the horizon. An
 *   appointment can therefore keep only its 1-hour reminder when 20:00 the
 *   evening before has gone by.
 *
 * ### DST
 * The start instant comes from [Appointment.startInstant], and 20:00 is resolved
 * with `atZone` in the appointment's zone, so the evening-before reminder stays
 * at 20:00 wall-clock time even when the offset changes between the two
 * instants (see [eveningBefore]).
 */
object AppointmentReminderPlanning {

    /** The scheduling horizon: eight days, the same as the medications plan. */
    val DEFAULT_HORIZON: Duration = Duration.ofDays(8)

    /** The local time of the evening-before reminder. */
    val EVENING_BEFORE_TIME: LocalTime = LocalTime.of(20, 0)

    /** How long before the start the second reminder fires. */
    val HOUR_BEFORE: Duration = Duration.ofHours(1)

    /**
     * @param now The instant "the next eight days" starts from; also the lower
     *   bound of a scheduled reminder.
     * @param appointments Appointments of the signed-in user's circles, any
     *   order; cancelled and past ones are filtered here.
     * @param myUserId The signed-in user, compared with the companion.
     * @param myRole The signed-in user's role in the circle, used when an
     *   appointment has no companion.
     */
    fun plan(
        now: Instant,
        appointments: List<Appointment>,
        myUserId: String?,
        myRole: MemberRole?,
        horizon: Duration = DEFAULT_HORIZON,
    ): List<AppointmentReminder> {
        if (horizon.isNegative || horizon.isZero) return emptyList()
        val to = now.plus(horizon)

        return appointments
            .filter { !it.isCancelled }
            .filter { shouldRemindOnThisDevice(it, myUserId, myRole) }
            .flatMap { remindersOf(it) }
            .filter { !it.instant.isBefore(now) && !it.instant.isAfter(to) }
            .sortedWith(
                compareBy(
                    { it.instant },
                    { it.appointmentId.value },
                    { it.kind.name },
                ),
            )
    }

    /**
     * The reminders whose time has **passed** but which must stay: the shown
     * notification and the alarm that is due but not yet delivered. [plan] only
     * looks forward, so without this every reschedule after the reminder fired
     * would dismiss it.
     *
     * A reminder stays until the appointment's start has passed (its end, when
     * it has a [Appointment.durationMinutes]), the appointment is cancelled or
     * deleted (not in [appointments]), moved (the id of a reminder includes its
     * instant, so the old one is no longer returned) or no longer routed to this
     * device. Reminders that are still ahead are [plan]'s.
     */
    fun openReminders(
        now: Instant,
        appointments: List<Appointment>,
        myUserId: String?,
        myRole: MemberRole?,
    ): List<AppointmentReminder> = appointments
        .filter { !it.isCancelled }
        .filter { shouldRemindOnThisDevice(it, myUserId, myRole) }
        .filter { !now.isAfter(endInstant(it)) }
        .flatMap { remindersOf(it) }
        .filter { it.instant.isBefore(now) }
        .sortedWith(
            compareBy(
                { it.instant },
                { it.appointmentId.value },
                { it.kind.name },
            ),
        )

    /**
     * What a reschedule may cancel out of [candidateIds] (scheduled alarms or
     * showing notifications): everything that is neither in the forward
     * [planned] list nor in the [open] list of reminders that are due but whose
     * appointment has not started.
     */
    fun idsToCancel(
        candidateIds: Collection<Int>,
        planned: List<AppointmentReminder>,
        open: List<AppointmentReminder>,
    ): Set<Int> {
        val keep = (planned + open).mapTo(hashSetOf()) { AppointmentReminderIds.requestCode(it) }
        return candidateIds.filterNotTo(linkedSetOf()) { it in keep }
    }

    /** The start, or the end when the appointment has a duration. */
    private fun endInstant(appointment: Appointment): Instant {
        val start = appointment.startInstant()
        val minutes = appointment.durationMinutes ?: return start
        return start.plus(Duration.ofMinutes(minutes.toLong()))
    }

    private fun remindersOf(appointment: Appointment): List<AppointmentReminder> = listOf(
        AppointmentReminder(
            appointment = appointment,
            kind = AppointmentReminderKind.EVENING_BEFORE,
            instant = eveningBefore(appointment),
        ),
        AppointmentReminder(
            appointment = appointment,
            kind = AppointmentReminderKind.HOUR_BEFORE,
            instant = appointment.startInstant().minus(HOUR_BEFORE),
        ),
    )

    /**
     * 20:00 on the day before the appointment, **in the appointment's zone**.
     *
     * The wall clock is what is stored, so the reminder is 20:00 wherever the
     * patient is; `atZone` resolves that in the appointment's zone (a DST gap
     * would move it forward by the length of the gap, exactly as the start
     * instant is resolved).
     */
    fun eveningBefore(appointment: Appointment): Instant =
        appointment.startsLocal.toLocalDate()
            .minusDays(1)
            .atTime(EVENING_BEFORE_TIME)
            .atZone(appointment.timeZone)
            .toInstant()

    /**
     * The companion decides, when there is one; otherwise every owner and
     * caregiver is reminded, and a viewer never is.
     */
    private fun shouldRemindOnThisDevice(
        appointment: Appointment,
        myUserId: String?,
        myRole: MemberRole?,
    ): Boolean {
        val companion = appointment.companionUserId
        if (companion != null) return myUserId != null && companion == myUserId
        return myRole == MemberRole.OWNER || myRole == MemberRole.CAREGIVER
    }
}
