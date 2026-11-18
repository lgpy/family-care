package io.github.lgpy.familycare.core.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.koin.core.context.GlobalContext

/**
 * The app's single FCM entry point (M4; D4/D27, moved to the core by D29).
 *
 * Android allows one `FirebaseMessagingService` per app, so it lives here and
 * routes by the message's `type` to the feature that owns it ([PushHandlers]);
 * a second feature can then receive pushes without depending on the first (D5).
 *
 * - `onNewToken`: queue a registration. The token itself is not passed on or
 *   logged — the job re-reads the current token from Firebase, so a stale token
 *   cannot race a new one.
 * - `onMessageReceived`: hand the payload to the registered handler. Background
 *   notification messages are drawn by the system itself and never reach here;
 *   data-only, high-priority messages (such as a medications `clear`) do, so the
 *   feature's handler can start work that outlives this callback.
 *
 * Nothing is logged (the payload holds ids only, and tokens are personal data).
 */
class CorePushMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Deliberately ignores the argument: the worker reads the current token.
        PushWork.enqueueRegistration(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Koin is started by FamilyCareApplication before any service runs.
        val handlers = GlobalContext.getOrNull()?.get<PushHandlers>() ?: return
        handlers.handle(this, message.data, message.notification?.tag)
    }
}
