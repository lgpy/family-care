package io.github.lgpy.familycare.feature.medications.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Dose-status derivation and the double-confirmation warning.
 *
 * The boundary cases are the ones in shared/test-vectors/dose-status.json,
 * repeated here so a broken vector reader cannot hide them.
 */
class DoseStatusTest {

    private val slot = Fixtures.slot()

    private fun status(
        now: String,
        entries: List<DoseLogEntry> = emptyList(),
        escalation: Int = 30,
        missed: Int = 120,
    ): DoseStatus = DoseStatusRules.doseStatus(
        slot = slot,
        entries = entries,
        now = Instant.parse(now),
        escalationDelayMinutes = escalation,
        missedWindowMinutes = missed,
    )

    @Test
    fun `upcoming before the scheduled instant`() {
        val result = status("2026-11-16T07:59:59Z")

        assertEquals(DoseState.UPCOMING, result.state)
        assertEquals(0, result.confirmationCount)
        assertFalse(result.confirmedTwice)
        assertFalse(result.isConfirmed)
        assertNull(result.latestEntry)
    }

    @Test
    fun `due at the scheduled instant`() {
        assertEquals(DoseState.DUE, status("2026-11-16T08:00:00Z").state)
    }

    @Test
    fun `due at the escalation boundary, overdue one second after`() {
        assertEquals(DoseState.DUE, status("2026-11-16T08:30:00Z").state)
        assertEquals(DoseState.OVERDUE, status("2026-11-16T08:30:01Z").state)
    }

    @Test
    fun `overdue at the missed-window boundary, missed one second after`() {
        assertEquals(DoseState.OVERDUE, status("2026-11-16T10:00:00Z").state)
        assertEquals(DoseState.MISSED, status("2026-11-16T10:00:01Z").state)
    }

    @Test
    fun `a confirmed slot never turns missed`() {
        val result = status("2026-11-18T00:00:00Z", listOf(Fixtures.taken()))

        assertEquals(DoseState.TAKEN, result.state)
        assertTrue(result.isConfirmed)
    }

    @Test
    fun `a skipped slot reports skipped`() {
        val result = status(
            "2026-11-16T12:00:00Z",
            listOf(Fixtures.skipped()),
        )

        assertEquals(DoseState.SKIPPED, result.state)
        assertEquals(1, result.confirmationCount)
        assertEquals(SkipReason.REFUSED, result.latestEntry?.skipReason)
    }

    @Test
    fun `two confirmations of one slot are a double confirmation`() {
        val result = status(
            "2026-11-16T08:10:00Z",
            listOf(
                Fixtures.taken("entry-mom", "2026-11-16T08:04:00Z"),
                Fixtures.taken("entry-me", "2026-11-16T08:06:00Z"),
            ),
        )

        assertEquals(DoseState.TAKEN, result.state)
        assertEquals(2, result.confirmationCount)
        assertTrue(result.confirmedTwice)
        assertEquals(DoseLogEntryId("entry-me"), result.latestEntry?.id)
    }

    @Test
    fun `the latest effective entry decides between taken and skipped`() {
        val result = status(
            "2026-11-16T08:10:00Z",
            listOf(
                Fixtures.taken("entry-b", "2026-11-16T08:04:00Z"),
                Fixtures.entry(
                    id = "entry-a",
                    outcome = DoseOutcome.SKIPPED,
                    skipReason = SkipReason.UNAVAILABLE,
                    actualQuantity = null,
                    actualAt = "2026-11-16T08:08:00Z",
                ),
            ),
        )

        assertEquals(DoseState.SKIPPED, result.state)
        assertEquals(2, result.confirmationCount)
    }

    @Test
    fun `a correction voids the entry it names`() {
        val wrong = Fixtures.taken("entry-wrong")
        val fix = Fixtures.entry(
            id = "entry-fix",
            outcome = DoseOutcome.SKIPPED,
            skipReason = SkipReason.OTHER,
            actualQuantity = null,
            actualAt = "2026-11-16T08:10:00Z",
            correctsEntryId = "entry-wrong",
        )

        val result = status("2026-11-16T09:00:00Z", listOf(wrong, fix))

        assertEquals(DoseState.SKIPPED, result.state)
        assertEquals(1, result.confirmationCount)
        assertFalse(result.confirmedTwice)
        assertEquals(listOf(DoseLogEntryId("entry-fix")), result.effectiveEntries.map { it.id })
    }

    @Test
    fun `a chain of corrections leaves only the last entry`() {
        val first = Fixtures.taken("entry-1")
        val second = Fixtures.entry(
            id = "entry-2",
            outcome = DoseOutcome.SKIPPED,
            skipReason = SkipReason.NOT_NEEDED,
            actualQuantity = null,
            actualAt = "2026-11-16T08:10:00Z",
            correctsEntryId = "entry-1",
        )
        val third = Fixtures.entry(
            id = "entry-3",
            actualAt = "2026-11-16T08:20:00Z",
            correctsEntryId = "entry-2",
        )

        val result = status("2026-11-16T09:00:00Z", listOf(first, second, third))

        assertEquals(DoseState.TAKEN, result.state)
        assertEquals(1, result.confirmationCount)
        assertEquals(listOf(DoseLogEntryId("entry-3")), result.effectiveEntries.map { it.id })
    }

    @Test
    fun `entries of another slot are ignored`() {
        val otherSlotEntry = Fixtures.taken(
            id = "entry-tomorrow",
            actualAt = "2026-11-17T08:04:00Z",
        ).copy(scheduledLocal = java.time.LocalDateTime.parse("2026-11-17T08:00"))

        val result = status("2026-11-16T10:00:01Z", listOf(otherSlotEntry))

        assertEquals(DoseState.MISSED, result.state)
        assertEquals(0, result.confirmationCount)
    }

    @Test
    fun `entries of the same regimen but another local time are ignored`() {
        val sameRegimenOtherTime = Fixtures.taken("entry-other-time").copy(
            scheduledLocal = java.time.LocalDateTime.parse("2026-11-16T21:00"),
        )

        val result = status("2026-11-16T10:00:01Z", listOf(sameRegimenOtherTime))

        assertEquals(DoseState.MISSED, result.state)
    }

    @Test
    fun `the missed window wins when it is shorter than the escalation delay`() {
        val result = status("2026-11-16T09:30:00Z", escalation = 300, missed = 60)

        assertEquals(DoseState.MISSED, result.state)
    }

    @Test
    fun `the patient overload reads the delays from the patient`() {
        val patient = Fixtures.patient(escalationDelayMinutes = 10, missedWindowMinutes = 20)

        assertEquals(
            DoseState.DUE,
            DoseStatusRules.doseStatus(slot, emptyList(), Instant.parse("2026-11-16T08:10:00Z"), patient).state,
        )
        assertEquals(
            DoseState.OVERDUE,
            DoseStatusRules.doseStatus(slot, emptyList(), Instant.parse("2026-11-16T08:10:01Z"), patient).state,
        )
        assertEquals(
            DoseState.MISSED,
            DoseStatusRules.doseStatus(slot, emptyList(), Instant.parse("2026-11-16T08:20:01Z"), patient).state,
        )
    }

    @Test
    fun `negative delays are a programming error`() {
        assertThrows(IllegalArgumentException::class.java) {
            status("2026-11-16T08:00:00Z", escalation = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            status("2026-11-16T08:00:00Z", missed = -1)
        }
    }

    // --- Double-confirmation warning ---------------------------------------

    @Test
    fun `existingConfirmation returns nothing for an untouched slot`() {
        assertTrue(DoseLogRules.existingConfirmation(slot, emptyList()).isEmpty())
    }

    @Test
    fun `existingConfirmation lists unrelated confirmations oldest first`() {
        val entries = listOf(
            Fixtures.taken("entry-late", "2026-11-16T08:10:00Z"),
            Fixtures.taken("entry-early", "2026-11-16T08:04:00Z"),
        )

        assertEquals(
            listOf(DoseLogEntryId("entry-early"), DoseLogEntryId("entry-late")),
            DoseLogRules.existingConfirmation(slot, entries).map { it.id },
        )
    }

    @Test
    fun `existingConfirmation hides a corrected entry`() {
        val wrong = Fixtures.taken("entry-wrong")
        val fix = Fixtures.entry(
            id = "entry-fix",
            actualAt = "2026-11-16T08:10:00Z",
            correctsEntryId = "entry-wrong",
        )

        assertEquals(
            listOf(DoseLogEntryId("entry-fix")),
            DoseLogRules.existingConfirmation(slot, listOf(wrong, fix)).map { it.id },
        )
    }

    @Test
    fun `existingConfirmation works from the slot identity too`() {
        val entries = listOf(Fixtures.taken())

        assertEquals(
            1,
            DoseLogRules.existingConfirmation(slot.slotId, entries).size,
        )
    }
}
