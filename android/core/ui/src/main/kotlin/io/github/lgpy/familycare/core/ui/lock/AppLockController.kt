package io.github.lgpy.familycare.core.ui.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide app lock state (D12). The policy itself lives in [AppLockPolicy];
 * this class feeds it the process foreground/background transitions of
 * [ProcessLifecycleOwner] and publishes the resulting lock state to Compose.
 */
class AppLockController(
    context: Context,
    private val policy: AppLockPolicy,
) {
    private val biometricManager = BiometricManager.from(context)

    private val _locked = MutableStateFlow(policy.isLocked())
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _screenLockConfigured = MutableStateFlow(hasScreenLock())
    val screenLockConfigured: StateFlow<Boolean> = _screenLockConfigured.asStateFlow()

    private var observing = false

    private fun hasScreenLock(): Boolean =
        biometricManager.canAuthenticate(ALLOWED_AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /** Start observing the process lifecycle; safe to call more than once. */
    fun start() {
        if (observing) return
        observing = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
    }

    fun unlock() {
        policy.onAuthenticationSucceeded()
        _locked.value = policy.isLocked()
    }

    fun onAuthenticationFailed() {
        policy.onAuthenticationFailed()
        _locked.value = policy.isLocked()
    }

    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            policy.onAppForegrounded()
            _locked.value = policy.isLocked()
            // The user may have enrolled a screen lock in system settings
            // while the app was in the background.
            _screenLockConfigured.value = hasScreenLock()
        }

        override fun onStop(owner: LifecycleOwner) {
            policy.onAppBackgrounded()
        }
    }

    companion object {
        /** Fingerprint/face (strong) or the device PIN, pattern or password. */
        const val ALLOWED_AUTHENTICATORS: Int =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
