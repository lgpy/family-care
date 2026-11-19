package io.github.lgpy.familycare.core.ui.theme

import io.github.lgpy.familycare.core.domain.circle.PatientId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Patient identity colours must be stable (the same patient everywhere) and,
 * with the order-based resolver, must not collide for a small circle.
 */
class PatientColorsTest {

    private val palette = PatientPaletteLight

    @Test
    fun `the hash resolver gives the same patient the same colour every time`() {
        val resolve = hashPatientColors(palette)
        val id = PatientId("patient-1")
        assertEquals(resolve(id), resolve(id))
    }

    @Test
    fun `every hashed colour comes from the palette`() {
        val resolve = hashPatientColors(palette)
        (1..50).forEach { index ->
            assertTrue(resolve(PatientId("patient-$index")) in palette)
        }
    }

    @Test
    fun `eight patients get eight different colours in id order`() {
        val ids = (1..8).map { PatientId("patient-$it") }
        val resolve = orderedPatientColors(ids, palette)
        val colours = ids.map(resolve)
        assertEquals(8, colours.distinct().size)
        assertTrue(colours.all { it in palette })
    }

    @Test
    fun `a patient missing from the ordered list falls back to the hash`() {
        val resolve = orderedPatientColors(listOf(PatientId("a"), PatientId("b")), palette)
        val unknown = PatientId("unknown")
        assertEquals(hashPatientColors(palette)(unknown), resolve(unknown))
    }
}
