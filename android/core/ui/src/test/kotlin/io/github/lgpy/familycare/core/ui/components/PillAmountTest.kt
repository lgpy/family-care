package io.github.lgpy.familycare.core.ui.components

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a dose is split into for the drawn illustration: whole pills plus the
 * fraction of one, and nothing above the cap the row can hold.
 */
class PillAmountTest {

    private fun parts(quantity: String) = pillAmountParts(BigDecimal(quantity))

    @Test
    fun `one is one whole pill`() {
        assertEquals(PillAmountParts(whole = 1, fraction = 0f), parts("1"))
        assertEquals(PillAmountParts(whole = 2, fraction = 0f), parts("2"))
    }

    @Test
    fun `a quarter over one is one pill and a quarter of the next`() {
        val parts = parts("1.25")!!
        assertEquals(1, parts.whole)
        assertEquals(0.25f, parts.fraction, 0.0001f)
    }

    @Test
    fun `less than one is a fraction of a single pill`() {
        val half = parts("0.5")!!
        assertEquals(0, half.whole)
        assertEquals(0.5f, half.fraction, 0.0001f)

        val threeQuarters = parts("0.75")!!
        assertEquals(0, threeQuarters.whole)
        assertEquals(0.75f, threeQuarters.fraction, 0.0001f)
    }

    @Test
    fun `the whole part is kept exactly and the rest is the fraction`() {
        val parts = parts("3.125")!!
        assertEquals(3, parts.whole)
        assertEquals(0.125f, parts.fraction, 0.0001f)
    }

    @Test
    fun `the cap draws the last amount that fits`() {
        assertEquals(MaxDrawnPills, parts(MaxDrawnPills.toString())!!.whole)

        val atCap = parts("$MaxDrawnPills.5")!!
        assertEquals(MaxDrawnPills, atCap.whole)
        assertEquals(0.5f, atCap.fraction, 0.0001f)

        assertNull("more pills than the row can say", parts("${MaxDrawnPills + 1}"))
    }

    @Test
    fun `nothing is drawn for an amount that is not a dose`() {
        assertNull(parts("0"))
        assertNull(parts("-1"))
    }
}
