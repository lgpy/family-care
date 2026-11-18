package io.github.lgpy.familycare.core.notifications

import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.push.PushDeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The token lifecycle (M4/D27), against a fake API and a fake token source:
 * sign-in registers, a failure is reported for a retry (never marked as
 * registered), and sign-out unregisters before the session goes.
 */
class PushTokenRegistrarTest {

    private class FakePushDevices(
        private val registerResult: Result<Unit> = Result.success(Unit),
        private val unregisterResult: Result<Unit> = Result.success(Unit),
    ) : PushDeviceRepository {

        val registered = mutableListOf<String>()
        val unregistered = mutableListOf<String>()

        override suspend fun register(token: String): Result<Unit> {
            registered += token
            return registerResult
        }

        override suspend fun unregister(token: String): Result<Unit> {
            unregistered += token
            return unregisterResult
        }
    }

    private class FakeTokens(private val token: String? = "fcm-token") : FcmTokenSource {
        var deleted = false
        override suspend fun token(): String? = token
        override suspend fun deleteToken(): Boolean {
            deleted = true
            return true
        }
    }

    private class FakeStore(initialRegistered: Boolean = false) : PushRegistrationStore {
        override var registered: Boolean = initialRegistered
        var clears = 0

        override fun markRegistered() {
            registered = true
        }

        override fun clear() {
            registered = false
            clears += 1
        }
    }

    private val signedIn = MutableStateFlow<AuthState>(
        AuthState.SignedIn(userId = "user-1", email = "a@example.com"),
    )

    @Test
    fun `signed in registers the current token once`() = runTest {
        val devices = FakePushDevices()
        val store = FakeStore()
        val registrar = PushTokenRegistrar(devices, signedIn, FakeTokens("token-a"), store)

        assertTrue(registrar.registerNow().isSuccess)
        assertEquals(listOf("token-a"), devices.registered)
        assertTrue(store.registered)
    }

    @Test
    fun `signed out does nothing`() = runTest {
        val devices = FakePushDevices()
        val store = FakeStore()
        val registrar = PushTokenRegistrar(
            devices,
            MutableStateFlow(AuthState.SignedOut),
            FakeTokens(),
            store,
        )

        assertTrue(registrar.registerNow().isSuccess)
        assertTrue(devices.registered.isEmpty())
        assertFalse(store.registered)
    }

    @Test
    fun `a failed registration is not marked, so it is retried`() = runTest {
        val devices = FakePushDevices(
            registerResult = Result.failure(IllegalStateException("offline")),
        )
        val store = FakeStore()
        val registrar = PushTokenRegistrar(devices, signedIn, FakeTokens(), store)

        assertTrue(registrar.registerNow().isFailure)
        assertFalse(store.registered)
    }

    @Test
    fun `a missing token is a failure and never reaches the API`() = runTest {
        val devices = FakePushDevices()
        val registrar = PushTokenRegistrar(devices, signedIn, FakeTokens(token = null), FakeStore())

        assertTrue(registrar.registerNow().isFailure)
        assertTrue(devices.registered.isEmpty())
    }

    @Test
    fun `sign out unregisters, deletes the token and clears the flag`() = runTest {
        val devices = FakePushDevices()
        val tokens = FakeTokens("token-b")
        val store = FakeStore(initialRegistered = true)
        val registrar = PushTokenRegistrar(devices, signedIn, tokens, store)

        registrar.unregisterForSignOut()

        assertEquals(listOf("token-b"), devices.unregistered)
        assertTrue(tokens.deleted)
        assertFalse(store.registered)
        assertEquals(1, store.clears)
    }

    @Test
    fun `sign out still deletes the token when the server call fails`() = runTest {
        val devices = FakePushDevices(
            unregisterResult = Result.failure(IllegalStateException("offline")),
        )
        val tokens = FakeTokens("token-c")
        val store = FakeStore(initialRegistered = true)
        val registrar = PushTokenRegistrar(devices, signedIn, tokens, store)

        // Must not throw: sign-out is never blocked by push.
        registrar.unregisterForSignOut()

        assertEquals(listOf("token-c"), devices.unregistered)
        assertTrue(tokens.deleted)
        assertFalse(store.registered)
    }
}
