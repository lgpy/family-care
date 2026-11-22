package io.github.lgpy.familycare.feature.medications.reminders

import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.feature.medications.data.MedsError
import io.github.lgpy.familycare.feature.medications.data.MedsResult
import io.github.lgpy.familycare.feature.medications.reminders.ReminderDecisions.PlanRead
import java.io.IOException
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
class ReminderDecisionsTest {

    private val signedIn = AuthState.SignedIn(userId = "u1", email = null)

    // --- Fix 2: a cold process starts in Loading ------------------------------

    @Test
    fun `an already resolved session is returned without waiting`() = runTest {
        assertEquals(signedIn, ReminderDecisions.resolveAuth(MutableStateFlow(signedIn)))
        assertEquals(AuthState.SignedOut, ReminderDecisions.resolveAuth(MutableStateFlow(AuthState.SignedOut)))
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `a loading session is awaited until it resolves`() = runTest {
        val state = MutableStateFlow<AuthState>(AuthState.Loading)

        val resolved = async { ReminderDecisions.resolveAuth(state, timeoutMillis = 4_000) }
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

        val resolved = ReminderDecisions.resolveAuth(state, timeoutMillis = 4_000)

        assertEquals(AuthState.Loading, resolved)
        assertEquals(4_000L, testScheduler.currentTime)
    }

    @Test
    fun `the timeout fits inside the broadcast window`() {
        assertTrue(ReminderDecisions.AUTH_TIMEOUT_MILLIS in 1_000L..6_000L)
    }

    @Test
    fun `the alarm receiver posts when signed in and when the session is unresolved`() {
        assertTrue(ReminderDecisions.shouldPost(signedIn, alreadyConfirmed = false))
        assertTrue(ReminderDecisions.shouldPost(AuthState.Loading, alreadyConfirmed = null))
    }

    @Test
    fun `the alarm receiver stays silent only for a resolved signed out session`() {
        assertFalse(ReminderDecisions.shouldPost(AuthState.SignedOut, alreadyConfirmed = null))
        assertFalse(ReminderDecisions.shouldPost(AuthState.SignedOut, alreadyConfirmed = false))
    }

    @Test
    fun `an already confirmed group is not posted, an unreadable dose log still is`() {
        assertFalse(ReminderDecisions.shouldPost(signedIn, alreadyConfirmed = true))
        assertFalse(ReminderDecisions.shouldPost(AuthState.Loading, alreadyConfirmed = true))
        assertTrue(ReminderDecisions.shouldPost(signedIn, alreadyConfirmed = null))
    }

    // --- Fix 3: Given from the notification ------------------------------------

    @Test
    fun `given removes the notification after a successful write`() {
        assertTrue(
            ReminderDecisions.shouldCancelAfterGive(
                alreadyConfirmed = false,
                write = MedsResult.Success(Unit),
            ),
        )
    }

    @Test
    fun `given removes the notification when the group was already confirmed`() {
        assertTrue(ReminderDecisions.shouldCancelAfterGive(alreadyConfirmed = true, write = null))
    }

    @Test
    fun `given keeps the notification when the write failed`() {
        listOf(
            MedsError.NotAllowed,
            MedsError.NotFound,
            MedsError.NoCircle,
            MedsError.Unknown,
        ).forEach { error ->
            assertFalse(
                "$error",
                ReminderDecisions.shouldCancelAfterGive(
                    alreadyConfirmed = false,
                    write = MedsResult.Failure(error),
                ),
            )
        }
    }

    @Test
    fun `given keeps the notification when no write was attempted`() {
        assertFalse(ReminderDecisions.shouldCancelAfterGive(alreadyConfirmed = false, write = null))
    }

    // --- Fix 4: a failed read is not "signed out" -------------------------------

    @Test
    fun `a read that throws leaves everything untouched`() = runTest {
        val read = ReminderDecisions.guardedRead { throw IOException("database is locked") }

        assertEquals(PlanRead.Unavailable, read)
    }

    @Test
    fun `a real empty answer passes through`() = runTest {
        assertEquals(PlanRead.NoPlan, ReminderDecisions.guardedRead { PlanRead.NoPlan })
        val ready = PlanRead.Ready(planned = emptyList(), open = emptyList())
        assertEquals(ready, ReminderDecisions.guardedRead { ready })
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        try {
            ReminderDecisions.guardedRead { throw CancellationException("cancelled") }
            fail("the cancellation was swallowed")
        } catch (_: CancellationException) {
            // expected
        }
    }
}
