package io.github.lgpy.familycare.feature.medications.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Fasting" lives in the instructions text: the chip adds or removes the phrase
 * and every screen recognises it in either language.
 */
class MedsFastingTest {

    @Test
    fun `fasting is recognised in either language`() {
        assertTrue(instructionsMentionFasting("Fasting"))
        assertTrue(instructionsMentionFasting("Take on an empty stomach"))
        assertTrue(instructionsMentionFasting("Em jejum, com água"))
        assertFalse(instructionsMentionFasting("Take with food"))
        assertFalse(instructionsMentionFasting(null))
    }

    @Test
    fun `turning it on puts the phrase in front of the note`() {
        assertEquals("Fasting", toggleFastingInstructions("", fasting = true, phrase = "Fasting"))
        assertEquals(
            "Em jejum, com água",
            toggleFastingInstructions(" com água ", fasting = true, phrase = "Em jejum"),
        )
    }

    @Test
    fun `turning it on twice does not repeat it`() {
        assertEquals(
            "Jejum, com água",
            toggleFastingInstructions("Jejum, com água", fasting = true, phrase = "Fasting"),
        )
    }

    @Test
    fun `turning it off keeps the rest of the note`() {
        assertEquals("com água", toggleFastingInstructions("Em jejum, com água", fasting = false, phrase = "Em jejum"))
        assertEquals("with water", toggleFastingInstructions("Fasting, with water", fasting = false, phrase = "Fasting"))
        assertEquals("", toggleFastingInstructions("Fasting", fasting = false, phrase = "Fasting"))
        assertEquals(
            "Take, with water",
            toggleFastingInstructions("Take on an empty stomach, with water", fasting = false, phrase = "Fasting"),
        )
    }
}
