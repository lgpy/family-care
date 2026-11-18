package io.github.lgpy.familycare.feature.medications.data

import io.github.lgpy.familycare.feature.medications.domain.DoseLogEntry
import io.github.lgpy.familycare.feature.medications.domain.Medication
import io.github.lgpy.familycare.feature.medications.domain.Regimen

/*
 * Parameter lists for the module's INSERT/UPDATE statements. Kept in one place,
 * next to each other and next to LocalMedsSql, so the column order of a
 * statement and its parameters are read together.
 *
 * All values are SQLite's own types: text for uuids/enums/dates/timestamps, an
 * integer 0/1 for a boolean, and decimal *text* for a quantity (so three
 * decimals are exact; the uploader converts it to a JSON number).
 */

/** Parameters of [INSERT_MEDICATION], in column order. */
internal fun medicationParameters(medication: Medication): List<Any?> = listOf(
    medication.id.value,
    medication.patientId.value,
    medication.name,
    medication.activeIngredient,
    medication.strength,
    medication.form.serverValue,
    medication.stockUnit,
    medication.instructions,
    medication.requiresPrescription.toSqlite(),
    medication.prescriptionValidUntil?.let(::formatDate),
    medication.lowStockThresholdDays,
    medication.reminderPolicy?.serverValue,
    medication.primaryCaregiverId,
    medication.stoppedAt?.toString(),
)

/** Parameters of [UPDATE_MEDICATION]: the insert's, less id and patient, plus the filter id. */
internal fun medicationUpdateParameters(medication: Medication): List<Any?> =
    medicationParameters(medication).drop(2) + medication.id.value

/** Parameters of [INSERT_REGIMEN], in column order. */
internal fun regimenParameters(regimen: Regimen): List<Any?> = listOf(
    regimen.id.value,
    regimen.medicationId.value,
    regimen.scheduleType.serverValue,
    regimen.weekdays,
    regimen.intervalDays,
    formatDate(regimen.startDate),
    regimen.endDate?.let(::formatDate),
    regimen.timeZone.id,
    formatTimes(regimen.times),
)

/** Parameters of [INSERT_DOSE_LOG], in column order. */
internal fun doseLogParameters(entry: DoseLogEntry): List<Any?> = listOf(
    entry.id.value,
    entry.regimenId.value,
    entry.medicationId.value,
    formatScheduledLocal(entry.scheduledLocal),
    entry.outcome.serverValue,
    entry.skipReason?.serverValue,
    entry.note,
    entry.actualAt.toString(),
    entry.actualQuantity?.let(::formatQuantity),
    entry.confirmedBy,
    entry.givenByUserId,
    entry.givenByOther.toSqlite(),
    entry.givenByName,
    entry.correctsEntryId?.value,
)

/** SQLite has no boolean: store 1/0, which the upload policy converts back. */
private fun Boolean.toSqlite(): Long = if (this) 1L else 0L
