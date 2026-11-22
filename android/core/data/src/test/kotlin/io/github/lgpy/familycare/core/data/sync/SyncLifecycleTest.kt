package io.github.lgpy.familycare.core.data.sync

import io.github.lgpy.familycare.core.domain.auth.AuthState
import java.io.IOException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the sync layer does when the session changes (M1, decision 6):
 * connect, keep, or clear first. A fake [SyncSession] records the calls and a
 * fake [LocalOwnerStore] stands in for the file that outlives the process, so
 * a restart is a new [SyncLifecycle] over the same store.
 */
class SyncLifecycleTest {

    private class FakeSession : SyncSession {
        val events = mutableListOf<String>()
        var failNextConnect: Exception? = null

        override suspend fun connect(userId: String) {
            failNextConnect?.let { failure ->
                failNextConnect = null
                events += "connect-failed:$userId"
                throw failure
            }
            events += "connect:$userId"
        }

        override suspend fun disconnectAndClear() {
            events += "clear"
        }
    }

    private class FakeOwnerStore(var owner: LocalOwner = LocalOwner.Unrecorded) : LocalOwnerStore {
        override fun read(): LocalOwner = owner

        override fun write(owner: LocalOwner) {
            this.owner = owner
        }
    }

    private val userA = AuthState.SignedIn(userId = "user-a", email = "a@example.com")
    private val userB = AuthState.SignedIn(userId = "user-b", email = "b@example.com")

    private val session = FakeSession()

    /** A database that was cleared before (or never held data). */
    private val store = FakeOwnerStore(LocalOwner.Clean)

    private fun lifecycle(owners: LocalOwnerStore = store) = SyncLifecycle(session, owners)

    @Test
    fun loadingDoesNothing() = runTest {
        val unrecorded = FakeOwnerStore()

        lifecycle(unrecorded).onAuthState(AuthState.Loading)

        assertEquals(emptyList<String>(), session.events)
        assertEquals(LocalOwner.Unrecorded, unrecorded.owner)
    }

    @Test
    fun signingInConnects() = runTest {
        lifecycle().onAuthState(userA)

        assertEquals(listOf("connect:user-a"), session.events)
        assertEquals(LocalOwner.User("user-a"), store.owner)
    }

    @Test
    fun theSameUserIsNotReconnectedOnEveryEmission() = runTest {
        val lifecycle = lifecycle()

        lifecycle.onAuthState(userA)
        lifecycle.onAuthState(userA)
        lifecycle.onAuthState(AuthState.Loading)
        lifecycle.onAuthState(userA)

        assertEquals(listOf("connect:user-a"), session.events)
    }

    @Test
    fun aDifferentUserClearsBeforeConnecting() = runTest {
        val lifecycle = lifecycle()

        lifecycle.onAuthState(userA)
        lifecycle.onAuthState(userB)

        assertEquals(listOf("connect:user-a", "clear", "connect:user-b"), session.events)
        assertEquals(LocalOwner.User("user-b"), store.owner)
    }

    @Test
    fun signingOutWipesTheLocalData() = runTest {
        val lifecycle = lifecycle()

        lifecycle.onAuthState(userA)
        lifecycle.onAuthState(AuthState.SignedOut)

        assertEquals(listOf("connect:user-a", "clear"), session.events)
        assertEquals(LocalOwner.Clean, store.owner)
    }

    @Test
    fun signingOutWithACleanDatabaseDoesNotClear() = runTest {
        lifecycle().onAuthState(AuthState.SignedOut)

        assertEquals(emptyList<String>(), session.events)
    }

    @Test
    fun signingInAgainAfterSignOutClearsNothingExtra() = runTest {
        val lifecycle = lifecycle()

        lifecycle.onAuthState(userA)
        lifecycle.onAuthState(AuthState.SignedOut)
        lifecycle.onAuthState(userA)

        assertEquals(listOf("connect:user-a", "clear", "connect:user-a"), session.events)
    }

    @Test
    fun aFailedConnectIsRetriedOnTheNextSignIn() = runTest {
        session.failNextConnect = IOException("offline")
        val lifecycle = lifecycle()
        var failures = 0

        lifecycle.run(flowOf(userA)) { failures++ }
        lifecycle.onAuthState(userA)

        assertEquals(1, failures)
        // No clear in between: whatever the failed attempt left belongs to user-a.
        assertEquals(listOf("connect-failed:user-a", "connect:user-a"), session.events)
        assertEquals(LocalOwner.User("user-a"), store.owner)
    }

    @Test
    fun theWholeSessionSequenceIsFollowed() = runTest {
        var failures = 0

        lifecycle().run(flowOf(AuthState.Loading, userA, AuthState.SignedOut, userB)) { failures++ }

        assertEquals(0, failures)
        assertEquals(listOf("connect:user-a", "clear", "connect:user-b"), session.events)
        assertEquals(LocalOwner.User("user-b"), store.owner)
    }

    // A process restart: a new SyncLifecycle over the same store.

    @Test
    fun aRestartSignedInAsTheOwnerKeepsTheData() = runTest {
        lifecycle().onAuthState(userA)

        lifecycle().onAuthState(userA)

        assertEquals(listOf("connect:user-a", "connect:user-a"), session.events)
        assertEquals(LocalOwner.User("user-a"), store.owner)
    }

    @Test
    fun aRestartSignedOutClearsThePreviousUsersData() = runTest {
        // The session was revoked while the app was not running.
        lifecycle().onAuthState(userA)

        lifecycle().onAuthState(AuthState.SignedOut)

        assertEquals(listOf("connect:user-a", "clear"), session.events)
        assertEquals(LocalOwner.Clean, store.owner)
    }

    @Test
    fun aRestartAsAnotherUserClearsBeforeConnecting() = runTest {
        lifecycle().onAuthState(userA)

        lifecycle().onAuthState(userB)

        assertEquals(listOf("connect:user-a", "clear", "connect:user-b"), session.events)
        assertEquals(LocalOwner.User("user-b"), store.owner)
    }

    @Test
    fun aProcessKilledBeforeTheWipeIsClearedOnTheNextStart() = runTest {
        // Signed out, but the process died before disconnectAndClear ran: the
        // record still names user-a.
        lifecycle().onAuthState(userA)

        val next = lifecycle()
        next.onAuthState(AuthState.SignedOut)
        next.onAuthState(userB)

        assertEquals(listOf("connect:user-a", "clear", "connect:user-b"), session.events)
    }

    @Test
    fun aRestartAfterACleanSignOutDoesNotClearAgain() = runTest {
        val first = lifecycle()
        first.onAuthState(userA)
        first.onAuthState(AuthState.SignedOut)

        lifecycle().onAuthState(AuthState.SignedOut)

        assertEquals(listOf("connect:user-a", "clear"), session.events)
    }

    // An install upgrading from a build that kept no record.

    @Test
    fun anUpgradeSignedInAdoptsThatUserWithoutClearing() = runTest {
        val unrecorded = FakeOwnerStore()

        lifecycle(unrecorded).onAuthState(userA)

        assertEquals(listOf("connect:user-a"), session.events)
        assertEquals(LocalOwner.User("user-a"), unrecorded.owner)
    }

    @Test
    fun anUpgradeSignedOutClears() = runTest {
        val unrecorded = FakeOwnerStore()

        lifecycle(unrecorded).onAuthState(AuthState.SignedOut)

        assertEquals(listOf("clear"), session.events)
        assertEquals(LocalOwner.Clean, unrecorded.owner)
    }

    @Test
    fun aMissingRecordAfterTheFirstDecisionIsTreatedAsData() = runTest {
        // The record disappeared after this process had already decided, so it
        // can no longer be the upgrade case.
        val flaky = FakeOwnerStore()
        val lifecycle = lifecycle(flaky)
        lifecycle.onAuthState(userA)
        flaky.owner = LocalOwner.Unrecorded

        lifecycle.onAuthState(userB)

        assertEquals(listOf("connect:user-a", "clear", "connect:user-b"), session.events)
    }
}
