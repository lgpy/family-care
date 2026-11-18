package io.github.lgpy.familycare.core.notifications

import android.content.Context

/**
 * Routes a push message to the feature handler that owns its `type` (M6; D29).
 *
 * Built once from the registered `PushHandler`s (`getAll<PushHandler>()`), like
 * the dashboard's card list. A message whose `type` is missing, blank or not
 * claimed by any handler is ignored: the payload may come from a newer server
 * version, and nothing is logged (payloads hold ids, and tokens are personal
 * data).
 *
 * Duplicate type claims are rejected here, at start-up, rather than silently
 * letting one feature starve another (the same rule as `TableCatalog` for
 * synced tables).
 */
class PushHandlers(handlers: List<PushHandler>) {

    private val byType: Map<String, PushHandler>

    init {
        val claims = handlers.flatMap { handler -> handler.types.map { it to handler } }
        val duplicates = claims.groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .keys
        require(duplicates.isEmpty()) {
            "Two push handlers claim the same type: ${duplicates.sorted()}"
        }

        byType = claims.associate { (type, handler) -> type to handler }
    }

    /** The handler that owns [type], or null when nobody does. */
    fun handlerFor(type: String?): PushHandler? =
        type?.takeIf(String::isNotBlank)?.let(byType::get)

    /** Delivers one message to its handler; unknown or absent types are ignored. */
    fun handle(context: Context, data: Map<String, String>, notificationTag: String?) {
        dispatch(data) { handler -> handler.handle(context, data, notificationTag) }
    }

    /** The routing half of [handle], without a transport, so it is unit-tested. */
    internal fun dispatch(data: Map<String, String>, deliver: (PushHandler) -> Unit) {
        handlerFor(data[KEY_TYPE])?.let(deliver)
    }

    private companion object {
        const val KEY_TYPE = "type"
    }
}
