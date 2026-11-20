package io.github.lgpy.familycare.feature.medications.dashboard

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The late line of an overdue dose slot: how the elapsed time is split into
 * minutes and whole hours, and when a slot is not late at all.
 */
class LateSpanTest {

    private val scheduled = LocalDateTime.of(2026, 11, 16, 8, 0)

    @Test
    fun `a slot at its time is not late`() {
        assertNull(lateSpan(now = scheduled, scheduled = scheduled))
    }

    @Test
    fun `a slot still in the future is not late`() {
        assertNull(lateSpan(now = scheduled.minusMinutes(1), scheduled = scheduled))
    }

    @Test
    fun `forty minutes late stays in minutes`() {
        assertEquals(
            LateSpan.Minutes(40),
            lateSpan(now = scheduled.plusMinutes(40), scheduled = scheduled),
        )
    }

    @Test
    fun `a whole hour is an hour without minutes`() {
        assertEquals(
            LateSpan.Hours(hours = 2, minutes = 0),
            lateSpan(now = scheduled.plusHours(2), scheduled = scheduled),
        )
    }

    @Test
    fun `hours keep their remaining minutes`() {
        assertEquals(
            LateSpan.Hours(hours = 2, minutes = 15),
            lateSpan(now = scheduled.plusMinutes(135), scheduled = scheduled),
        )
    }
}
