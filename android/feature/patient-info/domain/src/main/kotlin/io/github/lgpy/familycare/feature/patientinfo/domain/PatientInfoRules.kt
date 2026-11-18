package io.github.lgpy.familycare.feature.patientinfo.domain

/**
 * The patient-info rules from docs/domain/patient-info.md, in one place.
 *
 * The numbers and character sets mirror the check constraints of
 * `info.patient_info` (`backend/supabase/migrations/20261122120000_appointments_and_info.sql`)
 * so a local write is rejected before it reaches the upload queue, where a
 * constraint failure could only be reported as a permanent error. Changing one
 * side means changing the other.
 *
 * The list rules are the server's `info.text_list_is_valid` and
 * `info.contacts_are_valid`: a *trimmed* entry must be 1..n characters, so a
 * blank entry is an error rather than something silently removed.
 */
object PatientInfoRules {

    /** Mirrors `patient_info_allergies_valid`: at most 30 entries. */
    const val MAX_ALLERGIES: Int = 30

    /** Mirrors `patient_info_allergies_valid`: each entry 1..100 characters. */
    const val MAX_ALLERGY_LENGTH: Int = 100

    /** Mirrors `patient_info_conditions_valid`: at most 30 entries. */
    const val MAX_CONDITIONS: Int = 30

    /** Mirrors `patient_info_conditions_valid`: each entry 1..200 characters. */
    const val MAX_CONDITION_LENGTH: Int = 200

    /** Mirrors `patient_info_sns_number_format`: exactly nine digits. */
    const val SNS_NUMBER_DIGITS: Int = 9

    /** Mirrors the two name constraints: `family_doctor`, `health_centre`. */
    const val MAX_CENTRE_TEXT_LENGTH: Int = 200

    /** Mirrors `patient_info_health_centre_phone_format` and the contact phone. */
    const val MAX_PHONE_LENGTH: Int = 30

    /** Mirrors `info.contacts_are_valid`: at most five contacts. */
    const val MAX_EMERGENCY_CONTACTS: Int = 5

    /** Mirrors `info.contacts_are_valid`: a contact's name is 1..100. */
    const val MAX_CONTACT_NAME_LENGTH: Int = 100

    /** Mirrors `info.contacts_are_valid`: a relationship is optional, ≤ 50. */
    const val MAX_CONTACT_RELATIONSHIP_LENGTH: Int = 50

    /** Mirrors `patient_info_notes_length`. */
    const val MAX_NOTES_LENGTH: Int = 2000

    /** The server's phone charset, anchored, as Postgres writes it. */
    private val PHONE = Regex("^[0-9 +()-]{1,30}$")

    /** The server's SNS format, anchored. */
    private val SNS_NUMBER = Regex("^[0-9]{9}$")

    /** Trims a text field, as the server stores it. */
    fun normalizeText(value: String): String = value.trim()

    /** A blank optional text is stored as null; nothing else changes. */
    fun normalizeOptional(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }

    /** Whether [value] is acceptable as an optional phone (null is fine). */
    fun isValidPhone(value: String?): Boolean {
        val phone = normalizeOptional(value) ?: return true
        return PHONE.matches(phone)
    }

    /** Whether [value] is acceptable as an optional SNS number (null is fine). */
    fun isValidSnsNumber(value: String?): Boolean {
        val sns = normalizeOptional(value) ?: return true
        return SNS_NUMBER.matches(sns)
    }

    /** Whether a trimmed list entry is within 1..[maxLength] characters. */
    private fun isValidEntry(value: String, maxLength: Int): Boolean =
        normalizeText(value).length in 1..maxLength

    /** The intrinsic rules: counts, lengths, the SNS digits and the phone charset. */
    fun validate(info: PatientInfo): PatientInfoValidation {
        val errors = mutableSetOf<PatientInfoField>()
        val contactErrors = mutableSetOf<EmergencyContactError>()

        if (info.allergies.size > MAX_ALLERGIES ||
            info.allergies.any { !isValidEntry(it, MAX_ALLERGY_LENGTH) }
        ) {
            errors += PatientInfoField.ALLERGIES
        }

        if (info.conditions.size > MAX_CONDITIONS ||
            info.conditions.any { !isValidEntry(it, MAX_CONDITION_LENGTH) }
        ) {
            errors += PatientInfoField.CONDITIONS
        }

        if (!isValidSnsNumber(info.snsNumber)) {
            errors += PatientInfoField.SNS_NUMBER
        }

        if (lengthOrZero(info.familyDoctor) > MAX_CENTRE_TEXT_LENGTH) {
            errors += PatientInfoField.FAMILY_DOCTOR
        }

        if (lengthOrZero(info.healthCentre) > MAX_CENTRE_TEXT_LENGTH) {
            errors += PatientInfoField.HEALTH_CENTRE
        }

        if (!isValidPhone(info.healthCentrePhone)) {
            errors += PatientInfoField.HEALTH_CENTRE_PHONE
        }

        if (info.emergencyContacts.size > MAX_EMERGENCY_CONTACTS) {
            errors += PatientInfoField.EMERGENCY_CONTACTS
        }

        info.emergencyContacts.forEachIndexed { index, contact ->
            if (!isValidEntry(contact.name, MAX_CONTACT_NAME_LENGTH)) {
                contactErrors += EmergencyContactError(index, EmergencyContactField.NAME)
            }
            if (lengthOrZero(contact.relationship) > MAX_CONTACT_RELATIONSHIP_LENGTH) {
                contactErrors += EmergencyContactError(index, EmergencyContactField.RELATIONSHIP)
            }
            // Required on a contact, unlike the optional texts above.
            val phone = normalizeOptional(contact.phone)
            if (phone == null || !PHONE.matches(phone)) {
                contactErrors += EmergencyContactError(index, EmergencyContactField.PHONE)
            }
        }

        if (lengthOrZero(info.notes) > MAX_NOTES_LENGTH) {
            errors += PatientInfoField.NOTES
        }

        return PatientInfoValidation(errors = errors, contactErrors = contactErrors)
    }

    /** Length of an optional text after trimming; 0 when it is blank. */
    private fun lengthOrZero(value: String?): Int = normalizeOptional(value)?.length ?: 0
}
