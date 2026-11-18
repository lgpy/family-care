package io.github.lgpy.familycare.feature.medications.data

import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.feature.medications.domain.Medication
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.Regimen
import java.time.LocalDate

/**
 * The patient settings a dose-status calculation needs, read from the same
 * joined query as the medication so the two can never be out of step.
 *
 * Deliberately not the core [io.github.lgpy.familycare.core.domain.circle.Patient]:
 * this module only needs three values, and the core's patient type is a screen
 * concern (the screens read it through `PatientRepository`).
 */
data class PatientDoseSettings(
    val patientId: PatientId,
    /** The patient's name, so a doses-today card needs no second query. */
    val displayName: String,
    /** Minutes after the scheduled time before the slot is overdue. */
    val escalationDelayMinutes: Int,
    /** Minutes after the scheduled time after which an unconfirmed slot is missed. */
    val missedWindowMinutes: Int,
)

/**
 * One medication with every version of its regimen, plus the patient settings
 * that decide dose status.
 *
 * Regimens are versioned (D9), never edited: [currentRegimen] is the version in
 * force today (the latest open-ended one, or the latest one when the medication
 * was stopped), and [history] is everything before it.
 */
data class MedicationWithRegimens(
    val medication: Medication,
    val regimens: List<Regimen>,
    val patient: PatientDoseSettings,
) {

    /** Shorthand for the medication's id. */
    val id: MedicationId get() = medication.id

    /** The newest regimen version; null when the medication has no regimen yet. */
    val currentRegimen: Regimen?
        get() = regimens.filter { it.isOpenEnded }.maxByOrNull { it.startDate }
            ?: regimens.maxByOrNull { it.startDate }

    /** Earlier versions, oldest first, for the "history" section. */
    val history: List<Regimen>
        get() = regimens.filter { it.id != currentRegimen?.id }.sortedBy { it.startDate }

    /** The version that applies on [date], when a specific day is being shown. */
    fun regimenOn(date: LocalDate): Regimen? =
        regimens.filter { it.occursOn(date) }.maxByOrNull { it.startDate }

    /** Whether any version applies on [date]. */
    fun isActiveOn(date: LocalDate): Boolean = regimenOn(date) != null
}
