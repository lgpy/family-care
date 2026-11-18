package io.github.lgpy.familycare.core.notifications

import android.content.Context
import io.github.lgpy.familycare.core.domain.auth.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * When this device registers its FCM token (M4, D27).
 *
 * The session is the trigger, not the screen: on app start while signed in and
 * on every sign-in, one registration is queued ([PushWork.enqueueRegistration]).
 * The job needs the network, so an offline sign-in is retried instead of lost.
 *
 * Sign-out goes the other way and must run **before** the session is cleared:
 * [unregisterForSignOut] deletes this user's `core.push_devices` row and then
 * the token itself, so a shared phone stops receiving escalations for the
 * account that left. The app wraps [io.github.lgpy.familycare.core.data.auth.AuthRepository]
 * so *every* sign-out path does this (see `PushAwareAuthRepository`).
 */
class PushSession(
    private val context: Context,
    private val registrar: PushTokenRegistrar,
    private val authState: StateFlow<AuthState>,
    private val scope: CoroutineScope,
) {

    /** Starts following the session; safe to call once, from `Application`. */
    fun start() {
        scope.launch {
            authState.collect { state ->
                if (state is AuthState.SignedIn) {
                    PushWork.enqueueRegistration(context)
                }
            }
        }
    }

    /** A new token from FCM: register it now (queued, network-constrained). */
    fun onNewToken() {
        PushWork.enqueueRegistration(context)
    }

    /** Best effort, before the session is cleared; never throws. */
    suspend fun unregisterForSignOut() {
        runCatching { registrar.unregisterForSignOut() }
    }
}
