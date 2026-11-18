package io.github.lgpy.familycare.feature.medications.push

import io.github.lgpy.familycare.core.domain.auth.AuthState
import io.github.lgpy.familycare.core.domain.push.PushDeviceRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * The token registration half of [PushDeviceRepository] (M4, D27).
 *
 * Pure Kotlin — no Firebase, Android or WorkManager types — so the whole flow
 * (sign-in, retry, sign-out) is unit-tested against fakes. The Android glue
 * ([PushSession], [PushRegistrationWorker]) supplies the real token source and
 * decides *when* to call it:
 *
 * | event | call |
 * |---|---|
 * | app start while signed in, or a fresh sign-in | [registerNow] |
 * | `FirebaseMessagingService.onNewToken` | [registerNow] (the token is re-read) |
 * | sign-out | [unregisterForSignOut], before the session is cleared |
 *
 * Registration is idempotent: `core.register_push_device` upserts by token, so a
 * repeated call only bumps `last_seen_at`. Nothing here logs the token.
 *
 * The first success sets [PushRegistrationStore.registered], which the
 * Reminders health check reports (informational: it blocks nothing).
 */
class PushTokenRegistrar(
    private val repository: PushDeviceRepository,
    private val authState: StateFlow<AuthState>,
    private val tokens: FcmTokenSource,
    private val store: PushRegistrationStore,
) {

    /**
     * Registers this install's current token for the signed-in user.
     *
     * @return success when registered **or** when nobody is signed in (nothing
     *   to do: registration follows the session), failure when the token could
     *   not be read or the server could not be reached — retried later.
     */
    suspend fun registerNow(): Result<Unit> {
        if (authState.value !is AuthState.SignedIn) return Result.success(Unit)

        val token = tokens.token()
            ?: return Result.failure(IllegalStateException("the FCM token is not available"))

        val result = repository.register(token)
        if (result.isSuccess) store.markRegistered()
        return result
    }

    /**
     * Best effort, **before** the session is cleared: delete this user's device
     * row for the current token, then delete the token so the next sign-in gets
     * a fresh one.
     *
     * A failure is deliberately swallowed: offline, the row stays until the
     * token starts failing (FCM reports it unregistered and the server deletes
     * it), and a sign-out must never be blocked by a push call.
     */
    suspend fun unregisterForSignOut() {
        runCatching {
            tokens.token()?.let { repository.unregister(it) }
        }
        runCatching { tokens.deleteToken() }
        store.clear()
    }
}

/** This install's FCM token, abstracted so the registrar stays testable. */
interface FcmTokenSource {

    /** The current token, or null when it cannot be read (no Play services). */
    suspend fun token(): String?

    /** Deletes the token on this device; the next call to [token] mints a new one. */
    suspend fun deleteToken(): Boolean
}

/**
 * Whether this install's token has ever been registered successfully
 * (persisted on the phone — the server row is not synced, D3/D27).
 */
interface PushRegistrationStore {

    val registered: Boolean

    fun markRegistered()

    fun clear()
}
