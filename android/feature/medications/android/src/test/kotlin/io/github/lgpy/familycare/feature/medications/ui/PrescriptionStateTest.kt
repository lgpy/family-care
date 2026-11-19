package io.github.lgpy.familycare.feature.medications.ui

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The detail screen's prescription card never contradicts itself: the title
 * comes from the medication's own flags, not from the (low stock only) shopping
 * list. Metformin requires a prescription and has none, so it says "Prescription
 * needed" — never "No prescription needed" over "Needs a new prescription first".
 */
class PrescriptionStateTest {

    private val today = LocalDate.of(2026, 11, 19)

    @Test
    fun `requires a prescription and none recorded is needed`() {
        assertEquals(
            PrescriptionState.NEEDED,
            prescriptionState(
                requiresPrescription = true,
                prescriptionValidUntil = null,
                today = today,
            ),
        )
    }

    @Test
    fun `requires a prescription and expired yesterday is needed`() {
        assertEquals(
            PrescriptionState.NEEDED,
            prescriptionState(
                requiresPrescription = true,
                prescriptionValidUntil = today.minusDays(1),
                today = today,
            ),
        )
    }

    @Test
    fun `requires a prescription and valid today is valid`() {
        assertEquals(
            PrescriptionState.VALID,
            prescriptionState(
                requiresPrescription = true,
                prescriptionValidUntil = today,
                today = today,
            ),
        )
    }

    @Test
    fun `requires a prescription and valid later is valid`() {
        assertEquals(
            PrescriptionState.VALID,
            prescriptionState(
                requiresPrescription = true,
                prescriptionValidUntil = LocalDate.of(2027, 3, 1),
                today = today,
            ),
        )
    }

    @Test
    fun `does not require a prescription is not required even with an old date`() {
        assertEquals(
            PrescriptionState.NOT_REQUIRED,
            prescriptionState(
                requiresPrescription = false,
                prescriptionValidUntil = null,
                today = today,
            ),
        )
        assertEquals(
            PrescriptionState.NOT_REQUIRED,
            prescriptionState(
                requiresPrescription = false,
                prescriptionValidUntil = today.minusYears(1),
                today = today,
            ),
        )
    }
}
