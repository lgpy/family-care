package io.github.lgpy.familycare.feature.patientinfo.domain

/**
 * One emergency contact (docs/domain/patient-info.md): a name, an optional
 * relationship and a phone number.
 *
 * The phone is free text on purpose — the server's only rule is its charset and
 * length — so the screen can dial it as typed (`+351 912 345 678`).
 */
data class EmergencyContact(
    val name: String,
    val relationship: String? = null,
    val phone: String,
)

/**
 * A patient's medical info: the row `info.patient_info`, one per patient.
 *
 * Instances come from the synced local database, so they are only as fresh as
 * the last download. Persist one through the module's `PatientInfoRepository`;
 * [normalized] and [validate] are the rules it must pass first, and they mirror
 * the table's check constraints exactly so a refusal is never deferred to the
 * upload queue (where it could only be reported as a permanent error).
 *
 * @param allergies The allergy list shown as a banner on the profile (≤ 30
 *   entries of 1..100 characters). Empty means "not recorded", never "none".
 * @param conditions The conditions list under the banner (≤ 30 entries of 1..200).
 * @param snsNumber The Portuguese SNS user number: null or exactly 9 digits.
 * @param familyDoctor The family doctor's name, null when not recorded.
 * @param healthCentre The health centre's name.
 * @param healthCentrePhone The health centre's phone, dialable as typed.
 * @param emergencyContacts Up to 5 contacts, in the order the family listed them.
 * @param notes Free notes, up to 2000 characters.
 */
data class PatientInfo(
    val allergies: List<String> = emptyList(),
    val conditions: List<String> = emptyList(),
    val snsNumber: String? = null,
    val familyDoctor: String? = null,
    val healthCentre: String? = null,
    val healthCentrePhone: String? = null,
    val emergencyContacts: List<EmergencyContact> = emptyList(),
    val notes: String? = null,
) {

    /** Whether no field carries a value (the empty state of the Info tab). */
    val isEmpty: Boolean
        get() = allergies.isEmpty() &&
            conditions.isEmpty() &&
            snsNumber.isNullOrBlank() &&
            familyDoctor.isNullOrBlank() &&
            healthCentre.isNullOrBlank() &&
            healthCentrePhone.isNullOrBlank() &&
            emergencyContacts.isEmpty() &&
            notes.isNullOrBlank()

    /**
     * The values as they should be stored: every text trimmed and a blank
     * optional text turned into null. A blank list entry is *not* dropped — it
     * is a validation error, exactly as the server's list check treats it.
     */
    fun normalized(): PatientInfo = copy(
        allergies = allergies.map(PatientInfoRules::normalizeText),
        conditions = conditions.map(PatientInfoRules::normalizeText),
        snsNumber = PatientInfoRules.normalizeOptional(snsNumber),
        familyDoctor = PatientInfoRules.normalizeOptional(familyDoctor),
        healthCentre = PatientInfoRules.normalizeOptional(healthCentre),
        healthCentrePhone = PatientInfoRules.normalizeOptional(healthCentrePhone),
        emergencyContacts = emergencyContacts.map { contact ->
            contact.copy(
                name = PatientInfoRules.normalizeText(contact.name),
                relationship = PatientInfoRules.normalizeOptional(contact.relationship),
                phone = PatientInfoRules.normalizeText(contact.phone),
            )
        },
        notes = PatientInfoRules.normalizeOptional(notes),
    )

    /** Intrinsic validity, mirroring the `info.patient_info` check constraints. */
    fun validate(): PatientInfoValidation = PatientInfoRules.validate(this)
}
