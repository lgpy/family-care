package io.github.lgpy.familycare.feature.patientinfo.data

import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfo

/*
 * Parameter lists for the module's INSERT/UPDATE statements. Kept next to
 * LocalPatientInfoSql, so the column order of a statement and its parameters
 * are read together.
 *
 * All values are SQLite's own types: text for uuids, and JSON text for the
 * three `jsonb` columns (the uploader converts those to JSON values).
 */

/** The editable columns of `patient_info`, in the order the INSERT lists them. */
internal object PatientInfoColumns {
    const val ALLERGIES = "allergies"
    const val CONDITIONS = "conditions"
    const val SNS_NUMBER = "sns_number"
    const val FAMILY_DOCTOR = "family_doctor"
    const val HEALTH_CENTRE = "health_centre"
    const val HEALTH_CENTRE_PHONE = "health_centre_phone"
    const val EMERGENCY_CONTACTS = "emergency_contacts"
    const val NOTES = "notes"

    val ALL: List<String> = listOf(
        ALLERGIES, CONDITIONS, SNS_NUMBER, FAMILY_DOCTOR, HEALTH_CENTRE,
        HEALTH_CENTRE_PHONE, EMERGENCY_CONTACTS, NOTES,
    )
}

/** Every editable column of [info] as the value stored for it, in column order. */
internal fun patientInfoColumnValues(info: PatientInfo): Map<String, Any?> = linkedMapOf(
    PatientInfoColumns.ALLERGIES to formatTextList(info.allergies),
    PatientInfoColumns.CONDITIONS to formatTextList(info.conditions),
    PatientInfoColumns.SNS_NUMBER to info.snsNumber,
    PatientInfoColumns.FAMILY_DOCTOR to info.familyDoctor,
    PatientInfoColumns.HEALTH_CENTRE to info.healthCentre,
    PatientInfoColumns.HEALTH_CENTRE_PHONE to info.healthCentrePhone,
    PatientInfoColumns.EMERGENCY_CONTACTS to formatContacts(info.emergencyContacts),
    PatientInfoColumns.NOTES to info.notes,
)

/** Parameters of [INSERT_PATIENT_INFO], in column order; `id` is the patient. */
internal fun patientInfoInsertParameters(patientId: String, info: PatientInfo): List<Any?> =
    listOf(patientId, patientId) + patientInfoColumnValues(info).values

/** Parameters of `updatePatientInfoSql(values.keys)`: the values, then the row id. */
internal fun patientInfoUpdateParameters(values: Map<String, Any?>, patientId: String): List<Any?> =
    values.values.toList() + patientId
