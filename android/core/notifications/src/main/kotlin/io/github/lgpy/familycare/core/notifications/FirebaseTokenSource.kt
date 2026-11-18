package io.github.lgpy.familycare.core.notifications

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The real FCM token behind [FcmTokenSource].
 *
 * `FirebaseMessaging.getToken()` returns a `Task`; awaiting it here avoids the
 * extra `kotlinx-coroutines-play-services` dependency for one call. The token is
 * a personal, bearer credential for this install (security-and-privacy.md) and
 * is **never** logged, not even on failure.
 *
 * A fetch is wrapped in a timeout because it can hang without Play services (a
 * de-Googled phone): registration then fails quietly and is retried, instead of
 * blocking a sign-out.
 */
class FirebaseTokenSource : FcmTokenSource {

    override suspend fun token(): String? {
        val messaging = runCatching { FirebaseMessaging.getInstance() }.getOrNull() ?: return null
        return withTimeoutOrNull(TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                messaging.token.addOnCompleteListener { task ->
                    continuation.resume(
                        if (task.isSuccessful) task.result?.takeIf(String::isNotBlank) else null,
                    )
                }
            }
        }
    }

    override suspend fun deleteToken(): Boolean {
        val messaging = runCatching { FirebaseMessaging.getInstance() }.getOrNull() ?: return false
        return withTimeoutOrNull(TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                messaging.deleteToken().addOnCompleteListener { task ->
                    continuation.resume(task.isSuccessful)
                }
            }
        } ?: false
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}

/**
 * [PushRegistrationStore] on the phone's app-private preferences.
 *
 * `allowBackup=false` (the manifest), so the flag never leaves the device; it is
 * not synced and holds no personal data — only "a token was registered".
 */
class SharedPreferencesPushRegistrationStore(context: Context) : PushRegistrationStore {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override val registered: Boolean
        get() = preferences.getBoolean(KEY_REGISTERED, false)

    override fun markRegistered() {
        preferences.edit().putBoolean(KEY_REGISTERED, true).apply()
    }

    override fun clear() {
        preferences.edit().putBoolean(KEY_REGISTERED, false).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "push_registration"
        const val KEY_REGISTERED = "registered"
    }
}
