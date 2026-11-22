package io.github.lgpy.familycare.feature.medications.reminders

import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.feature.medications.data.MedsResult
import io.github.lgpy.familycare.feature.medications.domain.ReminderGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The decisions of the reminder receivers and the scheduler that do not need
 * Android, so they are unit-tested without it. The classes that own an
 * `AlarmManager` or a `NotificationManager` only apply them.
 */
internal object ReminderDecisions {

    /**
     * How long a broadcast waits for the stored session to load in a fresh
     * process. It must fit inside the `goAsync()` window (about 10 s) together
     * with the work that follows.
     */
    const val AUTH_TIMEOUT_MILLIS: Long = 4_000L

    /**
     * The first auth state that is not [AuthState.Loading]. A fresh process
     * starts in `Loading` while the session loads; treating that as "signed out"
     * cancelled every alarm and dropped the reminder. After [timeoutMillis]
     * still `Loading`, `Loading` is returned: the caller decides what an
     * undetermined session means.
     */
    suspend fun resolveAuth(
        state: StateFlow<AuthState>,
        timeoutMillis: Long = AUTH_TIMEOUT_MILLIS,
    ): AuthState = withTimeoutOrNull(timeoutMillis) {
        state.first { it !is AuthState.Loading }
    } ?: state.value

    /**
     * Whether the alarm receiver posts the reminder.
     *
     * Only a resolved [AuthState.SignedOut] stays silent. A session that is
     * still [AuthState.Loading] after the timeout posts from the intent's data:
     * the alarm was scheduled while signed in, and failing to remind is the
     * worse error. [alreadyConfirmed] is null when the dose log could not be
     * read, which also posts.
     */
    fun shouldPost(auth: AuthState, alreadyConfirmed: Boolean?): Boolean = when (auth) {
        AuthState.SignedOut -> false
        AuthState.Loading, is AuthState.SignedIn -> alreadyConfirmed != true
    }

    /**
     * Whether "Given" from the notification may remove the notification: only
     * when the dose is on record, i.e. the write succeeded or the group was
     * already confirmed. [write] is null when no write was attempted (the
     * dependencies were not available).
     */
    fun shouldCancelAfterGive(alreadyConfirmed: Boolean, write: MedsResult<Unit>?): Boolean =
        alreadyConfirmed || write is MedsResult.Success

    /** What a reschedule found out about the plan. */
    sealed interface PlanRead {

        /** [planned] is the forward plan, [open] the due-but-unconfirmed groups. */
        data class Ready(
            val planned: List<ReminderGroup>,
            val open: List<ReminderGroup>,
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
}
