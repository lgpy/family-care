package io.github.lgpy.familycare.feature.medications.ui

import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.RegimenId
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schedule editor's parsing: the text forms a time may take, and which
 * [MedicationField] a rejected schedule marks.
 *
 * These cover the step-2 gate directly, because a field the gate reports but
 * the screen does not draw looks exactly like a Next button that does nothing.
 */
class ScheduleFormTest {

    private val lisbon = ZoneId.of("Europe/Lisbon")

    private fun ScheduleFormState.parse() = parse(
        id = RegimenId("reg-1"),
        medicationId = MedicationId("med-1"),
        timeZone = lisbon,
    )

    @Test
    fun `parseTime accepts a trailing space`() {
        assertEquals(LocalTime.of(20, 0), parseTime("20:00 "))
        assertEquals(LocalTime.of(20, 0), parseTime(" 20:00"))
    }

    @Test
    fun `parseTime rejects an impossible time`() {
        assertNull(parseTime("25:00"))
        assertNull(parseTime("20:61"))
        assertNull(parseTime(""))
    }

    @Test
    fun `two identical times mark the times field`() {
        val parsed = ScheduleFormState(
            times = listOf(DoseTimeEntry("08:00"), DoseTimeEntry("08:00")),
        ).parse()

        assertEquals(setOf(MedicationField.REGIMEN_TIMES), parsed.fields)
    }

    @Test
    fun `a second time edited to 20 00 leaves no errors`() {
        val parsed = ScheduleFormState(
            times = listOf(DoseTimeEntry("08:00"), DoseTimeEntry("20:00")),
        ).parse()

        assertTrue(parsed.fields.isEmpty())
    }

    @Test
    fun `a trailing space in an edited time still parses`() {
        val parsed = ScheduleFormState(
            times = listOf(DoseTimeEntry("08:00"), DoseTimeEntry("20:00 ")),
        ).parse()

        assertTrue(parsed.fields.isEmpty())
        assertEquals(LocalTime.of(20, 0), parsed.regimen?.times?.last()?.time)
    }
}
