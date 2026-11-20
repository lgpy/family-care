package io.github.lgpy.familycare.feature.medications.ui

import io.github.lgpy.familycare.feature.medications.domain.MedicationField
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.RegimenId
import java.math.BigDecimal
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
    fun `a typed dose accepts a comma or a dot and rejects what the domain rejects`() {
        assertEquals(java.math.BigDecimal("1.5"), parseDoseQuantity("1,5"))
        assertEquals(java.math.BigDecimal("0.25"), parseDoseQuantity("0.25"))
        assertEquals(java.math.BigDecimal("1"), parseDoseQuantity(" 1 "))
        assertNull("zero is not a dose", parseDoseQuantity("0"))
        assertNull("a bare separator is not a number", parseDoseQuantity(","))
        assertNull("not a number", parseDoseQuantity("one"))
        assertNull("over the maximum", parseDoseQuantity("101"))
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

    @Test
    fun `dose steps go by halves and snap typed amounts to the half grid`() {
        assertEquals(BigDecimal("1.5"), steppedQuantity(BigDecimal.ONE, 1))
        assertEquals(BigDecimal("0.5"), steppedQuantity(BigDecimal.ONE, -1))
        assertEquals(BigDecimal("0.5"), steppedQuantity(BigDecimal("0.5"), -1))
        assertEquals(BigDecimal("1"), steppedQuantity(BigDecimal("0.75"), 1))
        assertEquals(BigDecimal("0.5"), steppedQuantity(BigDecimal("0.75"), -1))
        assertEquals(BigDecimal("0.25"), steppedQuantity(BigDecimal("0.25"), -1))
        assertEquals(BigDecimal("0.5"), steppedQuantity(BigDecimal("0.25"), 1))
    }
}
