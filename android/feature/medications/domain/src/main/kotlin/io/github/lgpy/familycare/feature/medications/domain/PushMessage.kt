package io.github.lgpy.familycare.feature.medications.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One server push, as the phone understands it (M4; D4/D27).
 *
 * The server sends two kinds of message (see
 * `backend/supabase/functions/escalate/index.ts` and `dose-confirmed/index.ts`):
 *
 * | type | payload data | payload notification |
 * |---|---|---|
 * | `escalation` | `type=escalation, patient_id, scheduled_local` | title "Family Care", localized body, `android.notification.tag` |
 * | `clear` | `type=clear, regimen_id, scheduled_local` | none (data-only, high priority) |
 *
 * Payloads carry ids and a wall-clock time only — no medication, patient or
 * health data (D4) — and nothing here logs them.
 */
sealed interface PushMessage {

    /**
     * A slot stayed unconfirmed past its escalation delay: show it.
     *
     * @param tag The notification tag the server used, so the same slot replaces
     *   an earlier push instead of stacking; [PushMessages.escalationTag] is the
     *   fallback when a message arrives without one.
     */
    data class Escalation(
        val patientId: String,
        val scheduledLocal: LocalDateTime,
        val tag: String,
    ) : PushMessage

    /**
     * Someone confirmed the dose elsewhere: cancel this phone's notification for
     * the slot (the local dose-reminder and any escalation).
     */
    data class Clear(
        val regimenId: String,
        val scheduledLocal: LocalDateTime,
    ) : PushMessage

    /** Not a message this build knows (another type, or a malformed payload). */
    data object Unknown : PushMessage
}

/**
 * Parsing and tag formatting for [PushMessage], kept pure so the mapping is
 * unit-tested on the JVM (D22) — the Android service only moves bytes.
 *
 * The formats mirror the server exactly:
 * - `scheduled_local` is a wall clock **without** a zone, at minute precision
 *   (`2026-11-16T08:00`; PostgREST may render it with `:00` seconds, which is
 *   the same slot — `normalizeScheduledLocal` on the server).
 * - the escalation tag is `escalation:<patient_id>:<instant>`, where the
 *   instant is `toInstantString` on the server: UTC, seconds, `Z`
 *   (`2026-11-16T08:00:00Z`).
 */
object PushMessages {

    const val TYPE_ESCALATION: String = "escalation"
    const val TYPE_CLEAR: String = "clear"

    const val KEY_TYPE = "type"
    const val KEY_PATIENT_ID = "patient_id"
    const val KEY_REGIMEN_ID = "regimen_id"
    const val KEY_SCHEDULED_LOCAL = "scheduled_local"

    /**
     * Maps a message's data map onto a [PushMessage].
     *
     * @param notificationTag The notification tag the message carried, when the
     *   transport exposes it (`RemoteMessage.notification?.tag`). An escalation
     *   without it falls back to the tag derived from the data, so a message is
     *   still replaceable and cancellable.
     */
    fun parse(data: Map<String, String>, notificationTag: String? = null): PushMessage =
        when (data[KEY_TYPE]) {
            TYPE_ESCALATION -> parseEscalation(data, notificationTag)
            TYPE_CLEAR -> parseClear(data)
            else -> PushMessage.Unknown
        }

    private fun parseEscalation(
        data: Map<String, String>,
        notificationTag: String?,
    ): PushMessage {
        val patientId = data[KEY_PATIENT_ID]?.takeIf(String::isNotBlank)
            ?: return PushMessage.Unknown
        val scheduledLocal = parseScheduledLocal(data[KEY_SCHEDULED_LOCAL])
            ?: return PushMessage.Unknown
        return PushMessage.Escalation(
            patientId = patientId,
            scheduledLocal = scheduledLocal,
            tag = notificationTag?.takeIf(String::isNotBlank)
                // Defensive only: the server always sets the tag. Without the
                // instant the data cannot build the exact one.
                ?: "escalation:$patientId:${formatScheduledLocal(scheduledLocal)}",
        )
    }

    private fun parseClear(data: Map<String, String>): PushMessage {
        val regimenId = data[KEY_REGIMEN_ID]?.takeIf(String::isNotBlank)
            ?: return PushMessage.Unknown
        val scheduledLocal = parseScheduledLocal(data[KEY_SCHEDULED_LOCAL])
            ?: return PushMessage.Unknown
        return PushMessage.Clear(regimenId = regimenId, scheduledLocal = scheduledLocal)
    }

    /** A wall-clock slot at minute precision, or null when it is malformed. */
    fun parseScheduledLocal(value: String?): LocalDateTime? {
        if (value.isNullOrBlank()) return null
        return runCatching { LocalDateTime.parse(value) }
            .getOrNull()
            ?.withSecond(0)
            ?.withNano(0)
    }

    /** `2026-11-16T08:00`, the server's `scheduled_local` form. */
    fun formatScheduledLocal(value: LocalDateTime): String =
        value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))

    /**
     * The server's notification tag for a slot
     * (`escalationTag(patientId, instant)` in `_shared/escalation.ts`).
     */
    fun escalationTag(patientId: String, instant: Instant): String =
        "escalation:$patientId:${instantText(instant)}"

    /** `2026-11-16T08:00:00Z`: the server's `toInstantString` (UTC, seconds). */
    fun instantText(instant: Instant): String =
        INSTANT_FORMAT.format(instant)

    private val INSTANT_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
}
