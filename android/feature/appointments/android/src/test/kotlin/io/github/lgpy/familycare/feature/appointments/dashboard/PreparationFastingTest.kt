package io.github.lgpy.familycare.feature.appointments.dashboard

import io.github.lgpy.familycare.feature.appointments.ui.preparationMentionsFasting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Next 7 days" row's preparation tag: the preparation is free text, so
 * fasting is recognised from the words the app offers in either language.
 */
class PreparationFastingTest {

    @Test
    fun `fasting in English is recognised`() {
        assertTrue(preparationMentionsFasting("Fasting"))
        assertTrue(preparationMentionsFasting("Fasting from 22:00"))
        assertTrue(preparationMentionsFasting("fasting since midnight"))
    }

    @Test
    fun `fasting in Portuguese is recognised`() {
        assertTrue(preparationMentionsFasting("Jejum"))
        assertTrue(preparationMentionsFasting("Jejum desde as 22:00"))
    }

    @Test
    fun `other preparations are not fasting`() {
        assertFalse(preparationMentionsFasting("Bring results"))
        assertFalse(preparationMentionsFasting("SNS card"))
        assertFalse(preparationMentionsFasting("Trazer os resultados"))
    }
}
