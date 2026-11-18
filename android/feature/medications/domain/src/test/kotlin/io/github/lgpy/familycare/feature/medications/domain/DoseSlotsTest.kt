package io.github.lgpy.familycare.feature.medications.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Hand-computed slot cases for the rules the shared vectors also pin down:
 * DST resolution, inclusive bounds, anchoring and the instant window.
 *
 * The Lisbon transitions in 2026 are 29 March 01:00 WET → 02:00 WEST and
 * 25 October 02:00 WEST → 01:00 WET.
 */
class DoseSlotsTest {

    private val day = { text: String -> LocalDate.parse(text) }

    @Test
    fun `dates are inclusive on both ends`() {
        val regimen = Fixtures.regimen(
            startDate = day("2026-11-01"),
            times = listOf(Fixtures.time("09:00", 1)),
        )

        val slots = DoseSlotRules.doseSlots(listOf(regimen), day("2026-11-16"), day("2026-11-18"))

        assertEquals(
            listOf(
                LocalDateTime.parse("2026-11-16T09:00"),
                LocalDateTime.parse("2026-11-17T09:00"),
                LocalDateTime.parse("2026-11-18T09:00"),
            ),
            slots.map { it.scheduledLocal },
        )
    }

    @Test
    fun `a reversed range produces no slots`() {
        val slots = DoseSlotRules.doseSlots(
            listOf(Fixtures.regimen()),
            day("2026-11-18"),
            day("2026-11-16"),
        )

        assertTrue(slots.isEmpty())
    }

    @Test
    fun `the regimen bounds clip the requested range`() {
        val regimen = Fixtures.regimen(
            startDate = day("2026-11-17"),
            endDate = day("2026-11-18"),
            times = listOf(Fixtures.time("09:00", 1)),
        )

        val slots = DoseSlotRules.doseSlots(listOf(regimen), day("2026-11-16"), day("2026-11-19"))

        assertEquals(2, slots.size)
        assertEquals(day("2026-11-17"), slots.first().localDate)
        assertEquals(day("2026-11-18"), slots.last().localDate)
    }

    @Test
    fun `times are emitted in ascending order with their own quantities`() {
        val regimen = Fixtures.regimen(
            times = listOf(
                Fixtures.time("21:00", "0.5"),
                Fixtures.time("08:00", 1),
                Fixtures.time("13:00", "0.25"),
            ),
        )

        val slots = DoseSlotRules.doseSlots(listOf(regimen), day("2026-11-16"), day("2026-11-16"))

        assertEquals(
            listOf("08:00", "13:00", "21:00"),
            slots.map { it.localTime.toString() },
        )
        assertEquals(
            listOf("1", "0.25", "0.5"),
            slots.map { it.formattedQuantity },
        )
    }

    @Test
    fun `slots are sorted by instant then medication`() {
        val late = Fixtures.regimen(
            id = RegimenId("reg-late"),
            medicationId = MedicationId("med-b"),
            times = listOf(Fixtures.time("21:00", 1)),
        )
        val early = Fixtures.regimen(
            id = RegimenId("reg-early"),
            medicationId = MedicationId("med-a"),
            times = listOf(Fixtures.time("08:00", 1)),
        )

        val slots = DoseSlotRules.doseSlots(
            listOf(late, early),
            day("2026-11-16"),
            day("2026-11-16"),
        )

        assertEquals(
            listOf("med-a", "med-b"),
            slots.map { it.medicationId.value },
        )
    }

    // --- DST ---------------------------------------------------------------

    @Test
    fun `a spring-forward gap shifts the slot forward by the gap length`() {
        val regimen = Fixtures.regimen(
            startDate = day("2026-03-01"),
            times = listOf(Fixtures.time("01:30", 1)),
        )

        val slots = DoseSlotRules.doseSlots(listOf(regimen), day("2026-03-29"), day("2026-03-29"))

        val slot = slots.single()
        assertEquals(LocalDateTime.parse("2026-03-29T01:30"), slot.scheduledLocal)
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), slot.instant)
        // 02:30+01:00 is what 01:30 resolves to; the local identity is kept.
        assertEquals("01:30", slot.localTime.toString())
    }

    @Test
    fun `a spring-forward week keeps one slot per configured time`() {
        val regimen = Fixtures.regimen(
            startDate = day("2026-03-01"),
            times = listOf(Fixtures.time("01:30", 1), Fixtures.time("08:00", "0.5")),
        )

        val slots = DoseSlotRules.doseSlots(listOf(regimen), day("2026-03-23"), day("2026-03-29"))

        assertEquals(14, slots.size)
        assertEquals(7, slots.count { it.localTime.toString() == "01:30" })
        // The 08:00 dose on the transition day is already in WEST.
        val transitionMorning = slots.first {
            it.scheduledLocal == LocalDateTime.parse("2026-03-29T08:00")
        }
        assertEquals(Instant.parse("2026-03-29T07:00:00Z"), transitionMorning.instant)
        // The day before it was still WET.
        val previousMorning = slots.first {
            it.scheduledLocal == LocalDateTime.parse("2026-03-28T08:00")
        }
        assertEquals(Instant.parse("2026-03-28T08:00:00Z"), previousMorning.instant)
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), slots.first {
            it.scheduledLocal == LocalDateTime.parse("2026-03-29T01:30")
        }.instant)
    }

    @Test
    fun `an autumn overlap uses the earlier offset`() {
        val regimen = Fixtures.regimen(
            startDate = day("2026-10-01"),
            times = listOf(Fixtures.time("01:30", 1), Fixtures.time("08:00", 1)),
        )

        val slots = DoseSlotRules.doseSlots(listOf(regimen), day("2026-10-25"), day("2026-10-25"))

        assertEquals(
            Instant.parse("2026-10-25T00:30:00Z"),
            slots.first { it.localTime.toString() == "01:30" }.instant,
        )
        assertEquals(
            Instant.parse("2026-10-25T08:00:00Z"),
            slots.first { it.localTime.toString() == "08:00" }.instant,
        )
        // The day before, 01:30 was still WEST too.
        val previousDay = DoseSlotRules.doseSlots(
            listOf(regimen),
            day("2026-10-24"),
            day("2026-10-24"),
        )
        assertEquals(
            Instant.parse("2026-10-24T00:30:00Z"),
            previousDay.first { it.localTime.toString() == "01:30" }.instant,
        )
    }

    @Test
    fun `two configured times may share an instant on a gap day and both slots exist`() {
        val regimen = Fixtures.regimen(
            startDate = day("2026-03-01"),
            times = listOf(Fixtures.time("01:30", 1), Fixtures.time("02:30", 1)),
        )

        val slots = DoseSlotRules.doseSlots(listOf(regimen), day("2026-03-29"), day("2026-03-29"))

        assertEquals(2, slots.size)
        assertEquals(
            listOf(
                LocalDateTime.parse("2026-03-29T01:30"),
                LocalDateTime.parse("2026-03-29T02:30"),
            ),
            slots.map { it.scheduledLocal },
        )
        assertEquals(slots[0].instant, slots[1].instant)
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), slots[0].instant)
    }

    @Test
    fun `a zone without DST uses its fixed offset`() {
        val saoPaulo = Fixtures.regimen(
            timeZone = ZoneId.of("America/Sao_Paulo"),
            times = listOf(Fixtures.time("08:00", 1)),
        )

        val slots = DoseSlotRules.doseSlots(
            listOf(saoPaulo),
            day("2026-11-16"),
            day("2026-11-16"),
        )

        assertEquals(Instant.parse("2026-11-16T11:00:00Z"), slots.single().instant)
    }

    @Test
    fun `every N days includes the leap day when the anchor says so`() {
        val regimen = Fixtures.regimen(
            scheduleType = ScheduleType.EVERY_N_DAYS,
            intervalDays = 7,
            startDate = day("2028-02-01"),
            times = listOf(Fixtures.time("12:00", 1)),
        )

        val slots = DoseSlotRules.doseSlots(listOf(regimen), day("2028-02-25"), day("2028-03-02"))

        assertEquals(listOf(day("2028-02-29")), slots.map { it.localDate })
        assertEquals(Instant.parse("2028-02-29T12:00:00Z"), slots.single().instant)
    }

    // --- Instant window ----------------------------------------------------

    @Test
    fun `the instant window is inclusive on both ends`() {
        val regimen = Fixtures.regimen(
            startDate = day("2026-11-01"),
            times = listOf(Fixtures.time("08:00", 1)),
        )

        val slots = DoseSlotRules.slotsDueBetween(
            listOf(regimen),
            Instant.parse("2026-11-16T08:00:00Z"),
            Instant.parse("2026-11-17T08:00:00Z"),
        )

        assertEquals(
            listOf(
                Instant.parse("2026-11-16T08:00:00Z"),
                Instant.parse("2026-11-17T08:00:00Z"),
            ),
            slots.map { it.instant },
        )
    }

    @Test
    fun `the instant window excludes slots outside it`() {
        val regimen = Fixtures.regimen(
            startDate = day("2026-11-01"),
            times = listOf(Fixtures.time("08:00", 1)),
        )

        val slots = DoseSlotRules.slotsDueBetween(
            listOf(regimen),
            Instant.parse("2026-11-16T08:00:01Z"),
            Instant.parse("2026-11-17T07:59:59Z"),
        )

        assertTrue(slots.isEmpty())
    }

    @Test
    fun `the instant window finds a gap-shifted slot by its real instant`() {
        val regimen = Fixtures.regimen(
            startDate = day("2026-03-01"),
            times = listOf(Fixtures.time("01:30", 1)),
        )

        val slots = DoseSlotRules.slotsDueBetween(
            listOf(regimen),
            Instant.parse("2026-03-29T01:30:00Z"),
            Instant.parse("2026-03-29T01:30:00Z"),
        )

        assertEquals(1, slots.size)
        assertEquals(LocalDateTime.parse("2026-03-29T01:30"), slots.single().scheduledLocal)
    }

    @Test
    fun `a slot near midnight belongs to its local date`() {
        val regimen = Fixtures.regimen(
            timeZone = ZoneId.of("America/Sao_Paulo"),
            startDate = day("2026-11-01"),
            times = listOf(Fixtures.time("23:30", 1)),
        )

        val slots = DoseSlotRules.slotsDueBetween(
            listOf(regimen),
            Instant.parse("2026-11-17T02:00:00Z"),
            Instant.parse("2026-11-17T03:00:00Z"),
        )

        assertEquals(1, slots.size)
        assertEquals(day("2026-11-16"), slots.single().localDate)
        assertEquals(Instant.parse("2026-11-17T02:30:00Z"), slots.single().instant)
    }

    @Test
    fun `the two slot-identity parts distinguish neighbouring doses`() {
        val regimen = Fixtures.regimen(times = listOf(Fixtures.time("08:00", 1)))
        val slots = DoseSlotRules.doseSlots(listOf(regimen), day("2026-11-16"), day("2026-11-17"))

        assertNotEquals(slots[0].slotId, slots[1].slotId)
        assertEquals(slots[0].slotId, DoseSlotId(regimen.id, LocalDateTime.parse("2026-11-16T08:00")))
    }
}
