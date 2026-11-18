package io.github.lgpy.familycare.feature.medications.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * The dose-log rules: the two outcomes are exclusive, "given by" is either a
 * member or someone else, and the append-only fields stay sane.
 */
class DoseLogEntryTest {

    @Test
    fun `a plain taken entry is valid`() {
        val entry = Fixtures.entry()

        assertTrue(entry.validate().isValid, "errors: ${entry.validate().errors}")
        assertFalse(entry.isCorrection)
        assertEquals(DoseSlotId(entry.regimenId, LocalDateTime.parse("2026-11-16T08:00")), entry.slotId)
    }

    @Test
    fun `a taken entry may omit the actual quantity`() {
        assertTrue(Fixtures.entry(actualQuantity = null).validate().isValid)
    }

    @Test
    fun `a taken entry may not carry a skip reason`() {
        val entry = Fixtures.entry(skipReason = SkipReason.NOT_NEEDED)

        assertEquals(setOf(MedicationField.DOSE_SKIP_REASON), entry.validate().errors)
    }

    @Test
    fun `a skipped entry requires a reason and no quantity`() {
        val missingReason = Fixtures.entry(
            outcome = DoseOutcome.SKIPPED,
            actualQuantity = null,
        )
        assertEquals(setOf(MedicationField.DOSE_SKIP_REASON), missingReason.validate().errors)

        val withQuantity = Fixtures.entry(
            outcome = DoseOutcome.SKIPPED,
            skipReason = SkipReason.REFUSED,
            actualQuantity = "1",
        )
        assertEquals(setOf(MedicationField.DOSE_QUANTITY), withQuantity.validate().errors)
    }

    @Test
    fun `actual quantities follow the dose quantity rules`() {
        listOf("0", "-1", "101", "0.0001").forEach { quantity ->
            assertEquals(
                setOf(MedicationField.DOSE_QUANTITY),
                Fixtures.entry(actualQuantity = quantity).validate().errors,
                "quantity $quantity",
            )
        }
        listOf("0.5", "1", "100").forEach { quantity ->
            assertTrue(
                Fixtures.entry(actualQuantity = quantity).validate().isValid,
                "quantity $quantity",
            )
        }
    }

    @Test
    fun `the confirmer is required`() {
        assertEquals(
            setOf(MedicationField.DOSE_CONFIRMED_BY),
            Fixtures.entry(confirmedBy = " ").validate().errors,
        )
    }

    @Test
    fun `given by is either a member or someone else, never both`() {
        val both = Fixtures.entry(givenByUserId = "user-mom", givenByOther = true)
        assertEquals(setOf(MedicationField.DOSE_GIVEN_BY), both.validate().errors)

        val member = Fixtures.entry(givenByUserId = "user-mom")
        assertTrue(member.validate().isValid)

        val other = Fixtures.entry(givenByOther = true, givenByName = "Neighbour")
        assertTrue(other.validate().isValid)

        val anonymousOther = Fixtures.entry(givenByOther = true)
        assertTrue(anonymousOther.validate().isValid)
    }

    @Test
    fun `a name only belongs to someone else and is at most 100 characters`() {
        assertEquals(
            setOf(MedicationField.DOSE_GIVEN_BY),
            Fixtures.entry(givenByName = "Neighbour").validate().errors,
        )
        assertEquals(
            setOf(MedicationField.DOSE_GIVEN_BY),
            Fixtures.entry(givenByOther = true, givenByName = "a".repeat(101)).validate().errors,
        )
    }

    @Test
    fun `a note is at most 500 characters`() {
        assertTrue(Fixtures.entry(note = "a".repeat(500)).validate().isValid)
        assertEquals(
            setOf(MedicationField.DOSE_NOTE),
            Fixtures.entry(note = "a".repeat(501)).validate().errors,
        )
    }

    @Test
    fun `the scheduled time must be a whole minute slot`() {
        val withSeconds = Fixtures.entry().copy(
            scheduledLocal = LocalDateTime.parse("2026-11-16T08:00:30"),
        )

        assertEquals(setOf(MedicationField.DOSE_SCHEDULED_TIME), withSeconds.validate().errors)
    }

    @Test
    fun `an entry cannot correct itself`() {
        val entry = Fixtures.entry(id = "entry-1", correctsEntryId = "entry-1")

        assertEquals(setOf(MedicationField.DOSE_CORRECTION), entry.validate().errors)
    }

    @Test
    fun `normalized trims the free text`() {
        val normalized = Fixtures.entry(
            confirmedBy = " user-me ",
            note = "  Given at breakfast ",
            givenByOther = true,
            givenByName = "  Neighbour ",
        ).normalized()

        assertEquals("user-me", normalized.confirmedBy)
        assertEquals("Given at breakfast", normalized.note)
        assertEquals("Neighbour", normalized.givenByName)
        assertNull(normalized.givenByUserId)
    }

    @Test
    fun `outcomes and skip reasons round-trip through their server values`() {
        DoseOutcome.entries.forEach { outcome ->
            assertEquals(outcome, DoseOutcome.fromServer(outcome.serverValue))
        }
        SkipReason.entries.forEach { reason ->
            assertEquals(reason, SkipReason.fromServer(reason.serverValue))
        }
        assertNull(DoseOutcome.fromServer("missed"))
        assertNull(SkipReason.fromServer("forgot"))
        assertEquals("taken", DoseOutcome.TAKEN.serverValue)
        assertEquals("skipped", DoseOutcome.SKIPPED.serverValue)
        assertEquals("not_needed", SkipReason.NOT_NEEDED.serverValue)
    }

    @Test
    fun `blank ids are rejected when the id type is built`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            RegimenId(" ")
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            DoseLogEntryId("")
        }
    }
}
