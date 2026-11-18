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

/** Parameters of [INSERT_PATIENT_INFO], in column order; `id` is the patient. */
internal fun patientInfoInsertParameters(patientId: String, info: PatientInfo): List<Any?> =
    listOf(patientId, patientId) + patientInfoUpdateParameters(info)

/** Parameters of [UPDATE_PATIENT_INFO], in column order, plus the filter id. */
internal fun patientInfoUpdateParameters(info: PatientInfo): List<Any?> = listOf(
    formatTextList(info.allergies),
    formatTextList(info.conditions),
    info.snsNumber,
    info.familyDoctor,
    info.healthCentre,
    info.healthCentrePhone,
    formatContacts(info.emergencyContacts),
    info.notes,
)

/** Parameters of [UPDATE_PATIENT_INFO] including its `WHERE id = ?`. */
internal fun patientInfoUpdateParameters(info: PatientInfo, patientId: String): List<Any?> =
    patientInfoUpdateParameters(info) + patientId
