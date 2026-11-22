package io.github.lgpy.familycare.feature.patientinfo.data

/**
 * The local SQL the patient-info repository runs against the encrypted
 * database.
 *
 * Everything here is the module's own table (`patient_info`) plus read-only
 * joins onto the core's `patients` and `memberships` — never a write to a core
 * table, and never a query another feature's tables (D5: a module owns its
 * tables).
 *
 * Parameters are always bound (`?`), never interpolated: ids are data.
 *
 * ### Why writes are plain INSERT/UPDATE
 * PowerSync tables are SQLite **views** with `INSTEAD OF` triggers, and SQLite
 * refuses `INSERT ... ON CONFLICT DO UPDATE` on a view. A save is therefore an
 * existence check plus a plain INSERT or UPDATE inside one write transaction.
 *
 * `created_at`/`updated_at`/`updated_by` are never written locally: the server
 * stamps them and they arrive through sync.
 */

/** One patient's info, provided it belongs to a circle the user is in. */
internal const val SELECT_PATIENT_INFO = """
    SELECT pi.id AS id,
           pi.patient_id AS patient_id,
           pi.allergies AS allergies,
           pi.conditions AS conditions,
           pi.sns_number AS sns_number,
           pi.family_doctor AS family_doctor,
           pi.health_centre AS health_centre,
           pi.health_centre_phone AS health_centre_phone,
           pi.emergency_contacts AS emergency_contacts,
           pi.notes AS notes
    FROM patient_info pi
    JOIN patients p ON p.id = pi.patient_id
    WHERE pi.patient_id = ?
      AND p.circle_id IN (SELECT circle_id FROM memberships WHERE user_id = ?)
"""

/**
 * The patient's row by id (`id = patient_id`), whatever its circle. Read inside
 * the write transaction, so a save merges onto the row as it is at that moment.
 */
internal const val SELECT_PATIENT_INFO_BY_ID = """
    SELECT pi.id AS id,
           pi.patient_id AS patient_id,
           pi.allergies AS allergies,
           pi.conditions AS conditions,
           pi.sns_number AS sns_number,
           pi.family_doctor AS family_doctor,
           pi.health_centre AS health_centre,
           pi.health_centre_phone AS health_centre_phone,
           pi.emergency_contacts AS emergency_contacts,
           pi.notes AS notes
    FROM patient_info pi
    WHERE pi.id = ?
"""

/** Whether a patient belongs to [given circle]. */
internal const val SELECT_PATIENT_IN_CIRCLE = """
    SELECT id FROM patients WHERE id = ? AND circle_id = ?
"""

/**
 * Inserts the patient's row. `id` equals `patient_id` (the server's
 * `patient_info_id_is_patient` check); the timestamps are the server's.
 */
internal const val INSERT_PATIENT_INFO = """
    INSERT INTO patient_info (
        id, patient_id, allergies, conditions, sns_number, family_doctor,
        health_centre, health_centre_phone, emergency_contacts, notes
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
"""

/**
 * Updates only [columns] of the patient's row. `id`/`patient_id` are deliberately
 * absent: the row belongs to its patient (the server trigger refuses a move too).
 *
 * A column not named here is not in the statement at all, so it is neither
 * overwritten locally nor uploaded. The names come from [PatientInfoColumns],
 * never from user input; the values are bound.
 */
internal fun updatePatientInfoSql(columns: List<String>): String {
    require(columns.isNotEmpty() && PatientInfoColumns.ALL.containsAll(columns)) {
        "Unknown or no patient_info columns: $columns"
    }
    return "UPDATE patient_info SET " + columns.joinToString(", ") { "$it = ?" } + " WHERE id = ?"
}
