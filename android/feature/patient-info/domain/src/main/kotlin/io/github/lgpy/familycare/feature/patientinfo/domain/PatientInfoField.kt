package io.github.lgpy.familycare.feature.patientinfo.domain

/**
 * The inputs a patient-info rule can point at, so a form can mark the offending
 * field without carrying a message.
 *
 * Deliberately local to the feature (D5): the core knows nothing about allergies
 * or an SNS number, and the form is the only place that maps these values to
 * labels.
 */
enum class PatientInfoField {
    /** The allergy list: at most 30 entries, each 1..100 characters. */
    ALLERGIES,

    /** The conditions list: at most 30 entries, each 1..200 characters. */
    CONDITIONS,

    /** The SNS number: null, or exactly 9 digits. */
    SNS_NUMBER,

    /** The family doctor's name: null or at most 200 characters. */
    FAMILY_DOCTOR,

    /** The health centre's name: null or at most 200 characters. */
    HEALTH_CENTRE,

    /** The health centre's phone: null, or 1..30 characters of `[0-9 +()-]`. */
    HEALTH_CENTRE_PHONE,

    /** The contact list as a whole: at most 5 entries. */
    EMERGENCY_CONTACTS,

    /** The free notes: null or at most 2000 characters. */
    NOTES,
}

/** Which input of one emergency contact a rule rejected. */
enum class EmergencyContactField {
    /** The contact's name: required, 1..100 characters. */
    NAME,

    /** The relationship: optional, at most 50 characters. */
    RELATIONSHIP,

    /** The phone: required, 1..30 characters of `[0-9 +()-]`. */
    PHONE,
}

/**
 * One rejected input of the emergency-contact at [index] (0-based, in the order
 * the form shows them).
 */
data class EmergencyContactError(val index: Int, val field: EmergencyContactField)

/**
 * What a patient-info rule rejected.
 *
 * The two sets are disjoint in meaning: [errors] are the row's own fields and
 * [contactErrors] point inside one contact, which a form marks inline.
 */
data class PatientInfoValidation(
    val errors: Set<PatientInfoField> = emptySet(),
    val contactErrors: Set<EmergencyContactError> = emptySet(),
) {

    /** Whether the record can be stored as it is. */
    val isValid: Boolean get() = errors.isEmpty() && contactErrors.isEmpty()
}
