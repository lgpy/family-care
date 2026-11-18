package io.github.lgpy.familycare.feature.patientinfo.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The patient-info rules from docs/domain/patient-info.md, case by case.
 *
 * The numbers and character sets are the `info.patient_info` check constraints;
 * if one of these tests changes, the server has to change with it.
 */
class PatientInfoRulesTest {

    private fun info(
        allergies: List<String> = emptyList(),
        conditions: List<String> = emptyList(),
        snsNumber: String? = null,
        familyDoctor: String? = null,
        healthCentre: String? = null,
        healthCentrePhone: String? = null,
        emergencyContacts: List<EmergencyContact> = emptyList(),
        notes: String? = null,
    ) = PatientInfo(
        allergies = allergies,
        conditions = conditions,
        snsNumber = snsNumber,
        familyDoctor = familyDoctor,
        healthCentre = healthCentre,
        healthCentrePhone = healthCentrePhone,
        emergencyContacts = emergencyContacts,
        notes = notes,
    )

    @Test
    fun `an empty record is valid and reports itself empty`() {
        val value = info()

        assertTrue(value.validate().isValid, "errors: ${value.validate()}")
        assertTrue(value.isEmpty)
    }

    @Test
    fun `a full record is valid`() {
        val value = info(
            allergies = listOf("Penicillin", "Nuts"),
            conditions = listOf("Type 2 diabetes"),
            snsNumber = "123456789",
            familyDoctor = "Dra. Silva",
            healthCentre = "Centro de Saúde de Belém",
            healthCentrePhone = "+351 21 000 0000",
            emergencyContacts = listOf(
                EmergencyContact(name = "Ana", relationship = "Daughter", phone = "912 345 678"),
                EmergencyContact(name = "Bruno", phone = "(21) 000-0000"),
            ),
            notes = "Prefers morning appointments.",
        )

        assertTrue(value.validate().isValid, "errors: ${value.validate()}")
        assertFalse(value.isEmpty)
    }

    // --- Allergies ---------------------------------------------------------

    @Test
    fun `allergies allow 30 entries of 100 characters`() {
        val allergies = List(30) { "a".repeat(100) }

        assertTrue(info(allergies = allergies).validate().isValid)
        assertEquals(
            setOf(PatientInfoField.ALLERGIES),
            info(allergies = allergies + "one more").validate().errors,
        )
        assertEquals(
            setOf(PatientInfoField.ALLERGIES),
            info(allergies = listOf("a".repeat(101))).validate().errors,
        )
    }

    @Test
    fun `a blank allergy is an error, not something dropped`() {
        val value = info(allergies = listOf("Penicillin", "   "))

        assertEquals(setOf(PatientInfoField.ALLERGIES), value.validate().errors)
        assertEquals(listOf("Penicillin", ""), value.normalized().allergies)
    }

    @Test
    fun `allergies are trimmed`() {
        assertEquals(listOf("Penicillin"), info(allergies = listOf("  Penicillin ")).normalized().allergies)
    }

    // --- Conditions --------------------------------------------------------

    @Test
    fun `conditions allow 30 entries of 200 characters`() {
        val conditions = List(30) { "c".repeat(200) }

        assertTrue(info(conditions = conditions).validate().isValid)
        assertEquals(
            setOf(PatientInfoField.CONDITIONS),
            info(conditions = conditions + "one more").validate().errors,
        )
        assertEquals(
            setOf(PatientInfoField.CONDITIONS),
            info(conditions = listOf("c".repeat(201))).validate().errors,
        )
    }

    // --- SNS number --------------------------------------------------------

    @Test
    fun `the SNS number is exactly nine digits`() {
        assertTrue(info(snsNumber = "123456789").validate().isValid)
        assertTrue(info(snsNumber = null).validate().isValid)
        assertTrue(info(snsNumber = "  ").validate().isValid, "blank is stored as null")

        listOf("12345678", "1234567890", "12345678a", "+12345678", "12345 6789").forEach { value ->
            assertEquals(
                setOf(PatientInfoField.SNS_NUMBER),
                info(snsNumber = value).validate().errors,
                "expected $value to be rejected",
            )
        }
    }

    @Test
    fun `a blank SNS number normalizes to null`() {
        assertNull(info(snsNumber = "  ").normalized().snsNumber)
        assertEquals("123456789", info(snsNumber = " 123456789 ").normalized().snsNumber)
    }

    // --- Names and phones --------------------------------------------------

    @Test
    fun `the doctor and centre names allow 200 characters`() {
        assertTrue(info(familyDoctor = "d".repeat(200), healthCentre = "h".repeat(200)).validate().isValid)
        assertEquals(
            setOf(PatientInfoField.FAMILY_DOCTOR),
            info(familyDoctor = "d".repeat(201)).validate().errors,
        )
        assertEquals(
            setOf(PatientInfoField.HEALTH_CENTRE),
            info(healthCentre = "h".repeat(201)).validate().errors,
        )
    }

    @Test
    fun `the health centre phone uses the server charset and length`() {
        listOf("912345678", "+351 21 000-0000", "(21) 000 0000", "210000000").forEach { value ->
            assertTrue(info(healthCentrePhone = value).validate().isValid, "$value should pass")
        }

        listOf("912 345 67a", "912.345", "9".repeat(31), "*123").forEach { value ->
            assertEquals(
                setOf(PatientInfoField.HEALTH_CENTRE_PHONE),
                info(healthCentrePhone = value).validate().errors,
                "expected $value to be rejected",
            )
        }
    }

    // --- Emergency contacts -------------------------------------------------

    @Test
    fun `up to five emergency contacts are allowed`() {
        val five = List(5) { EmergencyContact(name = "Contact $it", phone = "912345678") }

        assertTrue(info(emergencyContacts = five).validate().isValid)
        assertEquals(
            setOf(PatientInfoField.EMERGENCY_CONTACTS),
            info(emergencyContacts = five + EmergencyContact("One more", phone = "912345678"))
                .validate().errors,
        )
    }

    @Test
    fun `a contact needs a name and a phone`() {
        val validation = info(
            emergencyContacts = listOf(
                EmergencyContact(name = "  ", phone = "912345678"),
                EmergencyContact(name = "Ana", phone = ""),
            ),
        ).validate()

        assertEquals(
            setOf(
                EmergencyContactError(0, EmergencyContactField.NAME),
                EmergencyContactError(1, EmergencyContactField.PHONE),
            ),
            validation.contactErrors,
        )
    }

    @Test
    fun `a contact's name is at most 100 and its relationship at most 50`() {
        assertTrue(
            info(
                emergencyContacts = listOf(
                    EmergencyContact(
                        name = "n".repeat(100),
                        relationship = "r".repeat(50),
                        phone = "912345678",
                    ),
                ),
            ).validate().isValid,
        )

        assertEquals(
            setOf(
                EmergencyContactError(0, EmergencyContactField.NAME),
                EmergencyContactError(0, EmergencyContactField.RELATIONSHIP),
            ),
            info(
                emergencyContacts = listOf(
                    EmergencyContact(
                        name = "n".repeat(101),
                        relationship = "r".repeat(51),
                        phone = "912345678",
                    ),
                ),
            ).validate().contactErrors,
        )
    }

    @Test
    fun `a contact's phone uses the server charset`() {
        assertEquals(
            setOf(EmergencyContactError(0, EmergencyContactField.PHONE)),
            info(emergencyContacts = listOf(EmergencyContact("Ana", phone = "912.345")))
                .validate().contactErrors,
        )
    }

    @Test
    fun `a blank relationship normalizes to null`() {
        val contact = EmergencyContact(name = "  Ana ", relationship = "  ", phone = " 912345678 ")

        assertEquals(
            EmergencyContact(name = "Ana", relationship = null, phone = "912345678"),
            info(emergencyContacts = listOf(contact)).normalized().emergencyContacts.single(),
        )
    }

    // --- Notes --------------------------------------------------------------

    @Test
    fun `notes allow 2000 characters`() {
        assertTrue(info(notes = "n".repeat(2000)).validate().isValid)
        assertEquals(setOf(PatientInfoField.NOTES), info(notes = "n".repeat(2001)).validate().errors)
        assertNull(info(notes = "   ").normalized().notes)
    }
}
