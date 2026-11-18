package io.github.lgpy.familycare.core.notifications

import android.content.Context

/**
 * One feature's share of the FCM entry point (M6; D29).
 *
 * A feature registers a handler for the message `type`s it owns; [PushHandlers]
 * routes an incoming message to the one handler that claims its type. The core
 * service never parses a payload, so adding a feature's push type needs no
 * change here (D5: features depend only on the core).
 *
 * [handle] runs on the same thread the system used to call the service (the
 * main thread today) and is deliberately synchronous; work that outlives the
 * callback (database access, WorkManager) is the handler's concern.
 */
interface PushHandler {

    /**
     * The message `type` values this handler owns (the `type` key of the FCM
     * data map). A type claimed by two handlers is rejected when the registry
     * is built, at start-up.
     */
    val types: Set<String>

    /**
     * Handles one message whose `type` is in [types].
     *
     * @param data The message's data map, exactly as FCM delivered it.
     * @param notificationTag The notification tag the message carried, when the
     *   transport exposes it (`RemoteMessage.notification?.tag`).
     */
    fun handle(context: Context, data: Map<String, String>, notificationTag: String?)
}
