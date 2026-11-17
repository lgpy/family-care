package io.github.lgpy.familycare.core.ui.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyTest {

    private var now = 1_000L
    private val policy = AppLockPolicy(elapsedRealtime = { now })

    @Test
    fun coldStartIsLocked() {
        assertTrue(policy.isLocked())
    }

    @Test
    fun successfulAuthenticationUnlocks() {
        policy.onAuthenticationSucceeded()

        assertFalse(policy.isLocked())
    }

    @Test
    fun quickReturnStaysUnlocked() {
        policy.onAuthenticationSucceeded()
        policy.onAppBackgrounded()

        now += 30_000
        policy.onAppForegrounded()

        assertFalse(policy.isLocked())
    }

    @Test
    fun exactlySixtySecondsStaysUnlocked() {
        policy.onAuthenticationSucceeded()
        policy.onAppBackgrounded()

        now += AppLockPolicy.DEFAULT_BACKGROUND_TIMEOUT_MILLIS
        policy.onAppForegrounded()

        assertFalse(policy.isLocked())
    }

    @Test
    fun moreThanSixtySecondsRelocks() {
        policy.onAuthenticationSucceeded()
        policy.onAppBackgrounded()

        now += AppLockPolicy.DEFAULT_BACKGROUND_TIMEOUT_MILLIS + 1
        policy.onAppForegrounded()

        assertTrue(policy.isLocked())
    }

    @Test
    fun failedAuthenticationStaysLocked() {
        policy.onAuthenticationFailed()

        assertTrue(policy.isLocked())
    }

    @Test
    fun failedAuthenticationAfterTimeoutStaysLockedUntilSuccess() {
        policy.onAuthenticationSucceeded()
        policy.onAppBackgrounded()
        now += AppLockPolicy.DEFAULT_BACKGROUND_TIMEOUT_MILLIS + 1
        policy.onAppForegrounded()

        policy.onAuthenticationFailed()
        assertTrue(policy.isLocked())

        policy.onAuthenticationSucceeded()
        assertFalse(policy.isLocked())
    }

    @Test
    fun backgroundingWhileLockedNeverUnlocks() {
        policy.onAppBackgrounded()
        now += 5_000
        policy.onAppForegrounded()

        assertTrue(policy.isLocked())
    }
}
