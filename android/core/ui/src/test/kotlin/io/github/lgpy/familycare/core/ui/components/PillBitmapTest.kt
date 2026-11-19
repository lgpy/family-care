package io.github.lgpy.familycare.core.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification icon's pill layout: three across, two rows at most, every
 * pill the same size and inside the square.
 */
class PillBitmapTest {

    @Test
    fun `three pills share one row at the same size`() {
        val slots = pillBitmapSlots(count = 3, sizePx = 300)

        assertEquals(3, slots.size)
        assertEquals(1, slots.map { it.top }.distinct().size)
        assertEquals(3, slots.map { it.left }.distinct().size)
        assertEquals(1, slots.map { it.size }.distinct().size)
    }

    @Test
    fun `four pills wrap to a second row centred under the first`() {
        val slots = pillBitmapSlots(count = 4, sizePx = 300)

        assertEquals(4, slots.size)
        assertEquals(2, slots.map { it.top }.distinct().size)
        val last = slots.last()
        assertEquals(150f, last.left + last.size / 2f, 0.01f)
    }

    @Test
    fun `every pill stays inside the icon`() {
        val size = 120
        val slots = pillBitmapSlots(count = 6, sizePx = size)

        assertEquals(6, slots.size)
        slots.forEach { slot ->
            assertTrue(slot.left >= 0f)
            assertTrue(slot.top >= 0f)
            assertTrue(slot.left + slot.size <= size + 0.01f)
            assertTrue(slot.top + slot.size <= size + 0.01f)
        }
    }

    @Test
    fun `more than six pills are capped and empty input draws nothing`() {
        assertEquals(PillBitmapMaxPills, pillBitmapSlots(count = 9, sizePx = 300).size)
        assertTrue(pillBitmapSlots(count = 0, sizePx = 300).isEmpty())
        assertTrue(pillBitmapSlots(count = 3, sizePx = 0).isEmpty())
    }
}
