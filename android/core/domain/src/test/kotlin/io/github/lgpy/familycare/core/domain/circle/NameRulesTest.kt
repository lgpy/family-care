package io.github.lgpy.familycare.core.domain.circle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Circle and profile name rules, mirroring the M2 check constraints. */
class NameRulesTest {

    @Test
    fun `a circle name must be one to a hundred characters after trimming`() {
        assertTrue(CareCircle.isValidName("Our family"))
        assertTrue(CareCircle.isValidName("a".repeat(100)))
        assertFalse(CareCircle.isValidName(""))
        assertFalse(CareCircle.isValidName("   "))
        assertFalse(CareCircle.isValidName("a".repeat(101)))
    }

    @Test
    fun `circle names are trimmed before they are sent`() {
        assertEquals("Our family", CareCircle.normalizeName("  Our family  "))
    }

    @Test
    fun `a display name must be one to a hundred characters after trimming`() {
        assertTrue(Profile.isValidDisplayName("Mom"))
        assertTrue(Profile.isValidDisplayName("a".repeat(100)))
        assertFalse(Profile.isValidDisplayName(""))
        assertFalse(Profile.isValidDisplayName("   "))
        assertFalse(Profile.isValidDisplayName("a".repeat(101)))
    }

    @Test
    fun `display names are trimmed before they are stored`() {
        assertEquals("Mom", Profile.normalizeDisplayName("  Mom "))
    }

    @Test
    fun `the server languages are the two the app ships`() {
        assertEquals(setOf("pt-PT", "en"), Profile.LANGUAGES)
    }
}
