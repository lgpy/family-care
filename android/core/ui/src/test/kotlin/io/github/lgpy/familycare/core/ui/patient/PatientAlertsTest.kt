package io.github.lgpy.familycare.core.ui.patient

import io.github.lgpy.familycare.core.domain.circle.PatientId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The allergy contract's safe default: no implementation, no alerts. */
class PatientAlertsTest {

    @Test
    fun theFallbackReportsNoAllergies() {
        val patient = PatientId("patient-1")

        val allergies = runBlocking { NoPatientAlerts.allergies(patient).first() }

        assertEquals(emptyList<String>(), allergies)
    }
}
