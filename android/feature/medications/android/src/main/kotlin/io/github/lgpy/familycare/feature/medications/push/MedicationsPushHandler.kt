package io.github.lgpy.familycare.feature.medications.push

import android.content.Context
import io.github.lgpy.familycare.core.notifications.PushHandler
import io.github.lgpy.familycare.feature.medications.domain.PushMessage
import io.github.lgpy.familycare.feature.medications.domain.PushMessages

/**
 * The medications feature's share of the app's FCM entry point (M6; D29).
 *
 * The core owns the single `FirebaseMessagingService`; this handler claims the
 * message types the medications feature sends and keeps the M4/M5 behaviour
 * unchanged:
 *
 * - **escalation** and **stock-low** alerts are posted here because Android does
 *   not display notification messages while the app is in the foreground;
 * - a **clear** (data-only, high priority) is handed to a short WorkManager job,
 *   because it needs the database and outlives this callback.
 *
 * Background/killed: the system draws an escalation itself from the payload
 * (channel `escalations`, server tag) and a stock-low alert on `stock_alerts`; a
 * clear still arrives here because FCM delivers data-only, high-priority
 * messages to the service in a background process. Nothing is logged (the
 * payload holds ids only, and tokens are personal data).
 */
class MedicationsPushHandler : PushHandler {

    override val types: Set<String> = setOf(
        PushMessages.TYPE_ESCALATION,
        PushMessages.TYPE_CLEAR,
        PushMessages.TYPE_STOCK_LOW,
    )

    override fun handle(
        context: Context,
        data: Map<String, String>,
        notificationTag: String?,
    ) {
        when (val push = PushMessages.parse(data, notificationTag)) {
            is PushMessage.Escalation -> PushNotifications.postEscalation(context, push)
            is PushMessage.StockLow -> PushNotifications.postStockLow(context, push)
            is PushMessage.Clear -> PushWork.enqueueClear(context, push)
            PushMessage.Unknown -> Unit
        }
    }
}
