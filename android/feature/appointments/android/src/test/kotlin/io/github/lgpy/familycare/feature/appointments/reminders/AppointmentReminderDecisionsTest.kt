package io.github.lgpy.familycare.feature.appointments.reminders

import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.feature.appointments.domain.Appointment
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentId
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentKind
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentReminder
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentReminderKind
import io.github.lgpy.familycare.feature.appointments.reminders.AppointmentReminderDecisions.PlanRead
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppointmentReminderDecisionsTest {

    private val signedIn = AuthState.SignedIn(userId = "u1", email = null)

    // --- Fix 2: a cold process starts in Loading ------------------------------

    @Test
    fun `an already resolved session is returned without waiting`() = runTest {
        assertEquals(signedIn, AppointmentReminderDecisions.resolveAuth(MutableStateFlow(signedIn)))
        assertEquals(
            AuthState.SignedOut,
            AppointmentReminderDecisions.resolveAuth(MutableStateFlow(AuthState.SignedOut)),
        )
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `a loading session is awaited until it resolves`() = runTest {
        val state = MutableStateFlow<AuthState>(AuthState.Loading)

        val resolved = async {
            AppointmentReminderDecisions.resolveAuth(state, timeoutMillis = 4_000)
        }
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(resolved.isCompleted)

        state.value = signedIn
        assertEquals(signedIn, resolved.await())
        assertTrue(testScheduler.currentTime < 4_000)
    }

    @Test
    fun `a session that stays loading times out as loading, not as signed out`() = runTest {
        val state = MutableStateFlow<AuthState>(AuthState.Loading)

        val resolved = AppointmentReminderDecisions.resolveAuth(state, timeoutMillis = 4_000)

        assertEquals(AuthState.Loading, resolved)
        assertEquals(4_000L, testScheduler.currentTime)
    }

    @Test
    fun `the timeout fits inside the broadcast window`() {
        assertTrue(AppointmentReminderDecisions.AUTH_TIMEOUT_MILLIS in 1_000L..6_000L)
    }

    @Test
    fun `the alarm receiver posts when signed in and when the session is unresolved`() {
        assertTrue(AppointmentReminderDecisions.shouldPost(signedIn))
        assertTrue(AppointmentReminderDecisions.shouldPost(AuthState.Loading))
    }

    @Test
    fun `the alarm receiver stays silent only for a resolved signed out session`() {
        assertFalse(AppointmentReminderDecisions.shouldPost(AuthState.SignedOut))
    }

    // --- Fix 4: a failed read is not "signed out" -------------------------------

    @Test
    fun `a read that throws leaves everything untouched`() = runTest {
        val read = AppointmentReminderDecisions.guardedRead { throw IOException("database is locked") }

        assertEquals(PlanRead.Unavailable, read)
    }

    @Test
    fun `a real empty answer passes through`() = runTest {
        assertEquals(PlanRead.NoPlan, AppointmentReminderDecisions.guardedRead { PlanRead.NoPlan })
        val ready = PlanRead.Ready(planned = emptyList(), open = emptyList(), names = emptyMap())
        assertEquals(ready, AppointmentReminderDecisions.guardedRead { ready })
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        try {
            AppointmentReminderDecisions.guardedRead { throw CancellationException("cancelled") }
            fail("the cancellation was swallowed")
        } catch (_: CancellationException) {
            // expected
        }
    }

    // --- patients that are not synced yet ----------------------------------------

    private fun reminder(patient: String) = AppointmentReminder(
        appointment = Appointment(
            id = AppointmentId("a-$patient"),
            patientId = PatientId(patient),
            kind = AppointmentKind.CONSULTATION,
            startsLocal = LocalDateTime.of(2026, 11, 20, 10, 30),
            timeZone = ZoneId.of("Europe/Lisbon"),
        ),
        kind = AppointmentReminderKind.HOUR_BEFORE,
        instant = Instant.parse("2026-11-20T09:30:00Z"),
    )

    @Test
    fun `a reminder of a patient without a name waits for the row`() {
        val named = reminder("p1")
        val blank = reminder("p2")
        val unknown = reminder("p3")
        val names = mapOf(PatientId("p1") to "Grandma", PatientId("p2") to " ")

        assertEquals(
            listOf(named),
            AppointmentReminderDecisions.nameable(listOf(named, blank, unknown), names),
        )
    }
}
