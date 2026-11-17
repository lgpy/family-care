package io.github.lgpy.familycare.core.data.sync

import io.github.lgpy.familycare.core.domain.auth.AuthState
import java.io.IOException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the sync layer does when the session changes (M1, decision 6):
 * connect, keep, or clear first. A fake [SyncSession] records the calls.
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

    private val userA = AuthState.SignedIn(userId = "user-a", email = "a@example.com")
    private val userB = AuthState.SignedIn(userId = "user-b", email = "b@example.com")

    @Test
    fun loadingDoesNothing() = runTest {
        val session = FakeSession()
        val lifecycle = SyncLifecycle(session)

        lifecycle.onAuthState(AuthState.Loading)

        assertEquals(emptyList<String>(), session.events)
        assertNull(lifecycle.localOwnerId)
    }

    @Test
    fun signingInConnects() = runTest {
        val session = FakeSession()
        val lifecycle = SyncLifecycle(session)

        lifecycle.onAuthState(userA)

        assertEquals(listOf("connect:user-a"), session.events)
        assertEquals("user-a", lifecycle.localOwnerId)
    }

    @Test
    fun theSameUserIsNotReconnectedOnEveryEmission() = runTest {
        val session = FakeSession()
        val lifecycle = SyncLifecycle(session)

        lifecycle.onAuthState(userA)
        lifecycle.onAuthState(userA)
        lifecycle.onAuthState(AuthState.Loading)
        lifecycle.onAuthState(userA)

        assertEquals(listOf("connect:user-a"), session.events)
    }

    @Test
    fun aDifferentUserClearsBeforeConnecting() = runTest {
        val session = FakeSession()
        val lifecycle = SyncLifecycle(session)

        lifecycle.onAuthState(userA)
        lifecycle.onAuthState(userB)

        assertEquals(listOf("connect:user-a", "clear", "connect:user-b"), session.events)
        assertEquals("user-b", lifecycle.localOwnerId)
    }

    @Test
    fun signingOutWipesTheLocalData() = runTest {
        val session = FakeSession()
        val lifecycle = SyncLifecycle(session)

        lifecycle.onAuthState(userA)
        lifecycle.onAuthState(AuthState.SignedOut)

        assertEquals(listOf("connect:user-a", "clear"), session.events)
        assertNull(lifecycle.localOwnerId)
    }

    @Test
    fun signingOutWithoutLocalDataDoesNotClear() = runTest {
        val session = FakeSession()
        val lifecycle = SyncLifecycle(session)

        lifecycle.onAuthState(AuthState.SignedOut)

        assertEquals(emptyList<String>(), session.events)
    }

    @Test
    fun signingInAgainAfterSignOutClearsNothingExtra() = runTest {
        val session = FakeSession()
        val lifecycle = SyncLifecycle(session)

        lifecycle.onAuthState(userA)
        lifecycle.onAuthState(AuthState.SignedOut)
        lifecycle.onAuthState(userA)

        assertEquals(listOf("connect:user-a", "clear", "connect:user-a"), session.events)
    }

    @Test
    fun aFailedConnectIsRetriedOnTheNextSignIn() = runTest {
        val session = FakeSession().apply { failNextConnect = IOException("offline") }
        val lifecycle = SyncLifecycle(session)
        var failures = 0

        lifecycle.run(flowOf(userA)) { failures++ }
        assertNull(lifecycle.localOwnerId)

        lifecycle.onAuthState(userA)

        assertEquals(1, failures)
        assertEquals(listOf("connect-failed:user-a", "connect:user-a"), session.events)
        assertEquals("user-a", lifecycle.localOwnerId)
    }

    @Test
    fun theWholeSessionSequenceIsFollowed() = runTest {
        val session = FakeSession()
        val lifecycle = SyncLifecycle(session)
        var failures = 0

        lifecycle.run(flowOf(AuthState.Loading, userA, AuthState.SignedOut, userB)) { failures++ }

        assertEquals(0, failures)
        assertEquals(listOf("connect:user-a", "clear", "connect:user-b"), session.events)
        assertTrue(lifecycle.localOwnerId == "user-b")
    }
}
