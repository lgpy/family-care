package io.github.lgpy.familycare.feature.patientinfo.data

import io.github.lgpy.familycare.feature.patientinfo.domain.EmergencyContact
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfo
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfoField
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfoRules
import io.github.lgpy.familycare.feature.patientinfo.domain.PatientInfoValidation

/**
 * What a save from an edit form writes onto a row that may have moved on since
 * the form was loaded.
 *
 * [columns] holds only the columns that change, with the value to store; a
 * column the user did not touch, or whose merged value equals the row's, is not
 * in it. [validation] is the merged record's validation restricted to those
 * columns, so a legacy value in a column nobody edited never blocks a save.
 */
internal data class PatientInfoUpdatePlan(
    val columns: Map<String, Any?>,
    val validation: PatientInfoValidation,
)

/**
 * Plans the update for a form filled from [loaded], edited into [edited] and
 * saved onto the row's [current] value.
 *
 * - A scalar the user changed takes the user's value; one the user did not
 *   change is left alone, whatever the row now says.
 * - A list takes the user's *difference* — entries added to, and removed from,
 *   [loaded] — applied to [current]: removals drop the matching entries of
 *   [current], additions are appended after [current]'s own order, and entries
 *   someone else added meanwhile stay. An edited entry is a removal plus an
 *   addition.
 * - A column is compared structurally, never as text, because Postgres hands
 *   `jsonb` back with other spacing than the app writes.
 *
 * All three arguments must already be [PatientInfo.normalized] (a stored row
 * is compared by trimmed value, so one written untrimmed by an older version
 * still matches).
 */
internal fun planPatientInfoUpdate(
    loaded: PatientInfo,
    edited: PatientInfo,
    current: PatientInfo,
): PatientInfoUpdatePlan {
    val allergies = mergeList(loaded.allergies, edited.allergies, current.allergies, ::sameText)
    val conditions = mergeList(loaded.conditions, edited.conditions, current.conditions, ::sameText)
    val contacts = mergeList(
        loaded.emergencyContacts,
        edited.emergencyContacts,
        current.emergencyContacts,
        ::sameContact,
    )

    val sns = mergeScalar(loaded.snsNumber, edited.snsNumber, current.snsNumber)
    val doctor = mergeScalar(loaded.familyDoctor, edited.familyDoctor, current.familyDoctor)
    val centre = mergeScalar(loaded.healthCentre, edited.healthCentre, current.healthCentre)
    val centrePhone = mergeScalar(
        loaded.healthCentrePhone,
        edited.healthCentrePhone,
        current.healthCentrePhone,
    )
    val notes = mergeScalar(loaded.notes, edited.notes, current.notes)

    val merged = current.copy(
        allergies = allergies.value,
        conditions = conditions.value,
        snsNumber = sns.value,
        familyDoctor = doctor.value,
        healthCentre = centre.value,
        healthCentrePhone = centrePhone.value,
        emergencyContacts = contacts.value,
        notes = notes.value,
    )

    val values = patientInfoColumnValues(merged)
    val changed = linkedMapOf(
        PatientInfoColumns.ALLERGIES to allergies.changed,
        PatientInfoColumns.CONDITIONS to conditions.changed,
        PatientInfoColumns.SNS_NUMBER to sns.changed,
        PatientInfoColumns.FAMILY_DOCTOR to doctor.changed,
        PatientInfoColumns.HEALTH_CENTRE to centre.changed,
        PatientInfoColumns.HEALTH_CENTRE_PHONE to centrePhone.changed,
        PatientInfoColumns.EMERGENCY_CONTACTS to contacts.changed,
        PatientInfoColumns.NOTES to notes.changed,
    ).filterValues { it }.keys

    val fieldOf = mapOf(
        PatientInfoColumns.ALLERGIES to PatientInfoField.ALLERGIES,
        PatientInfoColumns.CONDITIONS to PatientInfoField.CONDITIONS,
        PatientInfoColumns.SNS_NUMBER to PatientInfoField.SNS_NUMBER,
        PatientInfoColumns.FAMILY_DOCTOR to PatientInfoField.FAMILY_DOCTOR,
        PatientInfoColumns.HEALTH_CENTRE to PatientInfoField.HEALTH_CENTRE,
        PatientInfoColumns.HEALTH_CENTRE_PHONE to PatientInfoField.HEALTH_CENTRE_PHONE,
        PatientInfoColumns.EMERGENCY_CONTACTS to PatientInfoField.EMERGENCY_CONTACTS,
        PatientInfoColumns.NOTES to PatientInfoField.NOTES,
    )
    val written = changed.map { fieldOf.getValue(it) }.toSet()
    val all = merged.validate()

    return PatientInfoUpdatePlan(
        columns = values.filterKeys { it in changed },
        validation = PatientInfoValidation(
            errors = all.errors.filterTo(mutableSetOf()) { it in written },
            contactErrors = if (PatientInfoField.EMERGENCY_CONTACTS in written) {
                all.contactErrors
            } else {
                emptySet()
            },
        ),
    )
}

private class Merged<T>(val value: T, val changed: Boolean)

/** The user's value when they changed it and the row does not already hold it. */
private fun mergeScalar(loaded: String?, edited: String?, current: String?): Merged<String?> {
    val untouched = loaded == edited
    val alreadyThere = PatientInfoRules.normalizeOptional(current) == edited
    return if (untouched || alreadyThere) Merged(current, false) else Merged(edited, true)
}

private fun <T> mergeList(
    loaded: List<T>,
    edited: List<T>,
    current: List<T>,
    same: (T, T) -> Boolean,
): Merged<List<T>> {
    val removed = minusEach(loaded, edited, same)
    val added = minusEach(edited, loaded, same)

    val result = current.toMutableList()
    for (entry in removed) {
        val index = result.indexOfFirst { same(it, entry) }
        if (index >= 0) result.removeAt(index)
    }
    for (entry in added) {
        // Someone else may have added the same entry meanwhile: keep it once.
        if (result.none { same(it, entry) }) result += entry
    }
    return Merged(result, changed = result != current)
}

/** [from] without one match in [take] per entry of [take] (a multiset difference). */
private fun <T> minusEach(from: List<T>, take: List<T>, same: (T, T) -> Boolean): List<T> {
    val remaining = take.toMutableList()
    return from.filter { entry ->
        val index = remaining.indexOfFirst { same(it, entry) }
        if (index >= 0) {
            remaining.removeAt(index)
            false
        } else {
            true
        }
    }
}

private fun sameText(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

private fun sameContact(a: EmergencyContact, b: EmergencyContact): Boolean =
    a.name.trim() == b.name.trim() &&
        a.phone.trim() == b.phone.trim() &&
        PatientInfoRules.normalizeOptional(a.relationship) ==
        PatientInfoRules.normalizeOptional(b.relationship)
