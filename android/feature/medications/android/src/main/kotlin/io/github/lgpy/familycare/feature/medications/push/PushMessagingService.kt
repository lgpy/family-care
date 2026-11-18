package io.github.lgpy.familycare.feature.medications.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.github.lgpy.familycare.feature.medications.domain.PushMessage
import io.github.lgpy.familycare.feature.medications.domain.PushMessages

/**
 * The FCM entry point (M4; D4/D27).
 *
 * - `onNewToken`: queue a registration. The token itself is not passed on or
 *   logged — the job re-reads the current token from Firebase, so a stale token
 *   cannot race a new one.
 * - `onMessageReceived`: an **escalation** or a **stock-low** alert is posted
 *   here because Android does not display notification messages while the app is
 *   in the foreground; a **clear** (data-only, high priority) is handed to a
 *   short WorkManager job, because it needs the database and outlives this
 *   callback.
 *
 * Background/killed: the system draws an escalation itself from the payload
 * (channel `escalations`, server tag) and a stock-low alert on `stock_alerts`; a
 * clear still arrives here because FCM delivers data-only, high-priority
 * messages to the service in a background process. Nothing is logged (the
 * payload holds ids only, and tokens are personal data).
 */
class PushMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Deliberately ignores the argument: the worker reads the current token.
        PushWork.enqueueRegistration(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        when (val push = PushMessages.parse(message.data, message.notification?.tag)) {
            is PushMessage.Escalation -> PushNotifications.postEscalation(this, push)
            is PushMessage.StockLow -> PushNotifications.postStockLow(this, push)
            is PushMessage.Clear -> PushWork.enqueueClear(this, push)
            PushMessage.Unknown -> Unit
        }
    }
}
