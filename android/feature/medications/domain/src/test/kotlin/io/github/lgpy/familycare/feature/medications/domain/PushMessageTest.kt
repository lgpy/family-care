package io.github.lgpy.familycare.feature.medications.domain

import java.time.Instant
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The wire contract with `backend/supabase/functions/escalate` and
 * `dose-confirmed` (M4). The payloads here are the ones those functions build;
 * the tag format is `escalationTag` / `toInstantString` in
 * `_shared/escalation.ts` and `_shared/doseSlots.ts`.
 */
class PushMessageTest {

    private val patientId = "6f1a8b1e-2a5b-4a1e-9a3f-1c2d3e4f5a6b"
    private val regimenId = "0b7c9d2e-3f4a-4b5c-8d6e-7f8091a2b3c4"
    private val medicationId = "7c2d9e3f-4a5b-4c6d-9e7f-8091a2b3c4d5"

    @Test
    fun `escalation carries the patient, the slot and the server tag`() {
        val message = PushMessages.parse(
            mapOf(
                "type" to "escalation",
                "patient_id" to patientId,
                "scheduled_local" to "2026-11-16T08:00",
            ),
            notificationTag = "escalation:$patientId:2026-11-16T08:00:00Z",
        )

        assertEquals(
            PushMessage.Escalation(
                patientId = patientId,
                scheduledLocal = LocalDateTime.parse("2026-11-16T08:00"),
                tag = "escalation:$patientId:2026-11-16T08:00:00Z",
            ),
            message,
        )
    }

    @Test
    fun `clear carries the regimen and the slot`() {
        assertEquals(
            PushMessage.Clear(
                regimenId = regimenId,
                scheduledLocal = LocalDateTime.parse("2026-11-16T08:00"),
            ),
            PushMessages.parse(
                mapOf(
                    "type" to "clear",
                    "regimen_id" to regimenId,
                    // PostgREST renders timestamp-without-zone with seconds.
                    "scheduled_local" to "2026-11-16T08:00:00",
                ),
            ),
        )
    }

    @Test
    fun `an unknown type is ignored`() {
        assertEquals(
            PushMessage.Unknown,
            PushMessages.parse(mapOf("type" to "low_stock", "regimen_id" to regimenId)),
        )
        assertEquals(PushMessage.Unknown, PushMessages.parse(emptyMap()))
    }

    @Test
    fun `stock low carries the medication tag`() {
        assertEquals(
            PushMessage.StockLow(tag = "stock-$medicationId"),
            PushMessages.parse(
                mapOf("type" to "stock_low"),
                notificationTag = "stock-$medicationId",
            ),
        )
    }

    @Test
    fun `stock low without a tag is still a stock alert`() {
        assertEquals(
            PushMessage.StockLow(tag = null),
            PushMessages.parse(mapOf("type" to "stock_low")),
        )
        assertEquals(
            PushMessage.StockLow(tag = null),
            PushMessages.parse(mapOf("type" to "stock_low"), notificationTag = "  "),
        )
    }

    @Test
    fun `a malformed or incomplete payload is ignored`() {
        assertEquals(
            PushMessage.Unknown,
            PushMessages.parse(
                mapOf("type" to "escalation", "patient_id" to patientId),
            ),
        )
        assertEquals(
            PushMessage.Unknown,
            PushMessages.parse(
                mapOf("type" to "clear", "regimen_id" to regimenId, "scheduled_local" to "8:00"),
            ),
        )
        assertEquals(
            PushMessage.Unknown,
            PushMessages.parse(mapOf("type" to "clear", "scheduled_local" to "2026-11-16T08:00")),
        )
    }

    @Test
    fun `the tag matches the server's format`() {
        // `escalationTag(patientId, toInstantString(instantMs))`.
        assertEquals(
            "escalation:$patientId:2026-11-16T08:00:00Z",
            PushMessages.escalationTag(patientId, Instant.parse("2026-11-16T08:00:00Z")),
        )
        // Seconds are truncated, not rounded, and the offset is UTC.
        assertEquals(
            "2026-11-16T08:00:00Z",
            PushMessages.instantText(Instant.parse("2026-11-16T08:00:00.999Z")),
        )
        assertEquals(
            "2026-03-29T01:30:00Z",
            PushMessages.instantText(Instant.parse("2026-03-29T01:30:00Z")),
        )
    }

    @Test
    fun `an escalation without a notification tag falls back to a derived one`() {
        val message = PushMessages.parse(
            mapOf(
                "type" to "escalation",
                "patient_id" to patientId,
                "scheduled_local" to "2026-11-16T08:00",
            ),
        )
        assertTrue(message is PushMessage.Escalation)
        assertEquals(
            "escalation:$patientId:2026-11-16T08:00",
            (message as PushMessage.Escalation).tag,
        )
    }

    @Test
    fun `slot times round-trip at minute precision`() {
        assertEquals(
            "2026-11-16T08:00",
            PushMessages.formatScheduledLocal(LocalDateTime.parse("2026-11-16T08:00:00")),
        )
        assertEquals(
            LocalDateTime.parse("2026-11-16T08:00"),
            PushMessages.parseScheduledLocal("2026-11-16T08:00:59"),
        )
        assertEquals(null, PushMessages.parseScheduledLocal(null))
    }
}
