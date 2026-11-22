package io.github.lgpy.familycare.feature.appointments.reminders

import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentReminder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The decisions of the appointment-reminder receivers and scheduler that do not
 * need Android, so they are unit-tested without it. The classes that own an
 * `AlarmManager` or a `NotificationManager` only apply them.
 *
 * Written again rather than shared with the medications reminders: a feature
 * module depends only on the core (D5).
 */
internal object AppointmentReminderDecisions {

    /**
     * How long a broadcast waits for the stored session to load in a fresh
     * process. It must fit inside the `goAsync()` window (about 10 s).
     */
    const val AUTH_TIMEOUT_MILLIS: Long = 4_000L

    /**
     * The first auth state that is not [AuthState.Loading]. A fresh process
     * starts in `Loading` while the session loads; treating that as "signed out"
     * dropped the reminder and cancelled every alarm. After [timeoutMillis]
     * still `Loading`, `Loading` is returned and the caller decides.
     */
    suspend fun resolveAuth(
        state: StateFlow<AuthState>,
        timeoutMillis: Long = AUTH_TIMEOUT_MILLIS,
    ): AuthState = withTimeoutOrNull(timeoutMillis) {
        state.first { it !is AuthState.Loading }
    } ?: state.value

    /**
     * Whether the alarm receiver posts the reminder. Only a resolved
     * [AuthState.SignedOut] stays silent: a session still [AuthState.Loading]
     * after the timeout posts from the intent's data, because the alarm was
     * scheduled while signed in and failing to remind is the worse error.
     */
    fun shouldPost(auth: AuthState): Boolean = auth !is AuthState.SignedOut

    /** What a reschedule found out about the plan. */
    sealed interface PlanRead {

        /**
         * [planned] is the forward plan, [open] the reminders whose time has
         * passed but whose appointment has not started (or ended), [names] the
         * patient names the notification text needs.
         */
        data class Ready(
            val planned: List<AppointmentReminder>,
            val open: List<AppointmentReminder>,
            val names: Map<PatientId, String>,
        ) : PlanRead

        /** Signed out, or no circle or role: nothing may stay scheduled. */
        data object NoPlan : PlanRead

        /** Not known (session still loading, or a read failed): change nothing. */
        data object Unavailable : PlanRead
    }

    /**
     * Runs [read]; an exception (a transient database error) is
     * [PlanRead.Unavailable], never [PlanRead.NoPlan], so it cannot cancel
     * alarms. Cancellation is rethrown.
     */
    suspend fun guardedRead(read: suspend () -> PlanRead): PlanRead = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        PlanRead.Unavailable
    }

    /**
     * A reminder for a patient the phone has not synced yet cannot name its
     * subject, so it waits for the next row rather than reminding blindly.
     */
    fun nameable(
        reminders: List<AppointmentReminder>,
        names: Map<PatientId, String>,
    ): List<AppointmentReminder> = reminders.filter { !names[it.patientId].isNullOrBlank() }
}
