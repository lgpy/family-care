package io.github.lgpy.familycare.feature.medications.domain

import io.github.lgpy.familycare.core.domain.circle.ReminderPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The medication rules from docs/domain/medications.md, case by case.
 *
 * The numbers are the `meds` schema's check constraints; if one of these tests
 * changes, the server has to change with it and the shared vectors follow.
 */
class MedicationRulesTest {

    @Test
    fun `a plain medication is valid`() {
        val validation = Fixtures.medication().validate()

        assertTrue(validation.isValid, "errors: ${validation.errors}")
    }

    @Test
    fun `the defaults match the domain document`() {
        val medication = Fixtures.medication()

        assertEquals(7, medication.lowStockThresholdDays)
        assertTrue(medication.requiresPrescription)
        assertNull(medication.reminderPolicy, "null inherits the patient's policy")
        assertNull(medication.primaryCaregiverId)
        assertFalse(medication.isStoppedAt(Instant.parse("2026-11-16T07:00:00Z")))
    }

    @Test
    fun `a medication is stopped only from the instant its stop takes effect`() {
        val effective = Instant.parse("2026-11-20T00:00:00Z")
        val medication = Fixtures.medication(stoppedAt = effective)

        assertFalse(medication.isStoppedAt(effective.minusSeconds(1)), "pending stop")
        assertTrue(medication.isStoppedAt(effective), "stopped_at <= now")
        assertTrue(medication.isStoppedAt(effective.plusSeconds(1)))
        assertFalse(Fixtures.medication(stoppedAt = null).isStoppedAt(effective))
    }

    @Test
    fun `name must be present after trimming`() {
        val errors = Fixtures.medication(name = "   ").validate().errors

        assertEquals(setOf(MedicationField.MEDICATION_NAME), errors)
    }

    @Test
    fun `name may not exceed 200 characters`() {
        assertTrue(Fixtures.medication(name = "a".repeat(200)).validate().isValid)
        assertEquals(
            setOf(MedicationField.MEDICATION_NAME),
            Fixtures.medication(name = "a".repeat(201)).validate().errors,
        )
    }

    @Test
    fun `optional texts may be null or blank and stay valid`() {
        val medication = Fixtures.medication(
            activeIngredient = null,
            strength = "  ",
            instructions = null,
        )

        assertTrue(medication.validate().isValid)
        assertNull(medication.normalized().strength)
    }

    @Test
    fun `optional texts have the server's maximum lengths`() {
        assertEquals(
            setOf(MedicationField.MEDICATION_ACTIVE_INGREDIENT),
            Fixtures.medication(activeIngredient = "a".repeat(201)).validate().errors,
        )
        assertEquals(
            setOf(MedicationField.MEDICATION_STRENGTH),
            Fixtures.medication(strength = "a".repeat(101)).validate().errors,
        )
        assertEquals(
            setOf(MedicationField.MEDICATION_INSTRUCTIONS),
            Fixtures.medication(instructions = "a".repeat(1001)).validate().errors,
        )
        assertTrue(
            Fixtures.medication(
                activeIngredient = "a".repeat(200),
                strength = "a".repeat(100),
                instructions = "a".repeat(1000),
            ).validate().isValid,
        )
    }

    @Test
    fun `stock unit is required and at most 50 characters`() {
        assertEquals(
            setOf(MedicationField.MEDICATION_STOCK_UNIT),
            Fixtures.medication(stockUnit = " ").validate().errors,
        )
        assertEquals(
            setOf(MedicationField.MEDICATION_STOCK_UNIT),
            Fixtures.medication(stockUnit = "a".repeat(51)).validate().errors,
        )
        assertTrue(Fixtures.medication(stockUnit = "a".repeat(50)).validate().isValid)
    }

    @Test
    fun `low stock threshold is 0 to 365 days`() {
        assertTrue(Fixtures.medication(lowStockThresholdDays = 0).validate().isValid)
        assertTrue(Fixtures.medication(lowStockThresholdDays = 365).validate().isValid)
        assertEquals(
            setOf(MedicationField.MEDICATION_LOW_STOCK_THRESHOLD),
            Fixtures.medication(lowStockThresholdDays = -1).validate().errors,
        )
        assertEquals(
            setOf(MedicationField.MEDICATION_LOW_STOCK_THRESHOLD),
            Fixtures.medication(lowStockThresholdDays = 366).validate().errors,
        )
    }

    @Test
    fun `a past prescription validity is allowed because it means expired`() {
        val medication = Fixtures.medication(
            prescriptionValidUntil = LocalDate.parse("2020-01-01"),
        )

        assertTrue(medication.validate().isValid)
    }

    @Test
    fun `every offered reminder policy may override the patient`() {
        ReminderPolicy.offered.forEach { policy ->
            assertTrue(
                Fixtures.medication(reminderPolicy = policy).validate().isValid,
                "policy $policy",
            )
        }
    }

    @Test
    fun `the rota policy is not offered and is rejected`() {
        assertEquals(
            setOf(MedicationField.MEDICATION_REMINDER_POLICY),
            Fixtures.medication(reminderPolicy = ReminderPolicy.ROTA).validate().errors,
        )
    }

    @Test
    fun `a blank primary caregiver override is an error rather than a silent null`() {
        assertEquals(
            setOf(MedicationField.MEDICATION_PRIMARY_CAREGIVER),
            Fixtures.medication(primaryCaregiverId = " ").validate().errors,
        )
        assertTrue(Fixtures.medication(primaryCaregiverId = "user-mom").validate().isValid)
    }

    @Test
    fun `normalized trims the stored text and turns blanks into null`() {
        val normalized = Fixtures.medication(
            name = "  Metformin ",
            activeIngredient = "",
            strength = " 850 mg ",
            stockUnit = " tablet ",
            instructions = "   ",
            primaryCaregiverId = "",
        ).normalized()

        assertEquals("Metformin", normalized.name)
        assertNull(normalized.activeIngredient)
        assertEquals("850 mg", normalized.strength)
        assertEquals("tablet", normalized.stockUnit)
        assertNull(normalized.instructions)
        assertNull(normalized.primaryCaregiverId)
    }

    @Test
    fun `forms round-trip through their server values`() {
        MedicationForm.entries.forEach { form ->
            assertEquals(form, MedicationForm.fromServer(form.serverValue))
        }
        assertNull(MedicationForm.fromServer("suppository"))
        assertNull(MedicationForm.fromServer(null))
        assertEquals("tablet", MedicationForm.TABLET.serverValue)
        assertEquals("other", MedicationForm.OTHER.serverValue)
    }
}
