package io.github.lgpy.familycare.feature.medications.data

import io.github.lgpy.familycare.core.domain.circle.PatientId
import io.github.lgpy.familycare.feature.medications.domain.DoseTime
import io.github.lgpy.familycare.feature.medications.domain.Medication
import io.github.lgpy.familycare.feature.medications.domain.MedicationId
import io.github.lgpy.familycare.feature.medications.domain.Regimen
import io.github.lgpy.familycare.feature.medications.domain.RegimenId
import io.github.lgpy.familycare.feature.medications.domain.ScheduleType
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/**
 * The medications and their versioned regimens (docs/domain/medications.md).
 *
 * Reads come from the encrypted local database and writes are local too: the
 * row appears immediately, works in airplane mode (D3), and PowerSync uploads it
 * when the network returns. Domain validation runs before the write, so a
 * mistake is a [MedsError.Invalid] a form can show rather than a constraint
 * error the upload queue could only report as permanent. The signed-in role is
 * checked first: a viewer gets [MedsError.NotAllowed] and nothing is written.
 *
 * ### Regimen versioning (D9)
 * A regimen is never edited. [changeRegimen] ends the current version and
 * inserts a new one in **one** write transaction; [stop] sets the last version's
 * end date and the medication's `stopped_at`. Both use [RegimenRules].
 *
 * Ids are generated on the phone ([MedicationId], [RegimenId] supplied by the
 * caller or by the repository), which is what lets a medication be created
 * offline.
 */
interface MedicationRepository {

    /**
     * The patient's medications with every regimen version, including stopped
     * ones (a patient's history), sorted by name. Emits again on every local or
     * synced change; empty while the rows are still downloading.
     */
    fun observe(patientId: PatientId): Flow<List<MedicationWithRegimens>>

    /**
     * Every active medication of the current circle — not stopped, patient not
     * archived — with its regimen history. The doses-today query and the
     * medication list both build on this.
     */
    val activeMedications: Flow<List<MedicationWithRegimens>>

    /** One medication of the current circle, or null when it is not local. */
    fun observeMedication(medicationId: MedicationId): Flow<MedicationWithRegimens?>

    /**
     * Stores a new [medication] and its [firstRegimen] in one write transaction,
     * so a medication never exists without the schedule that makes it useful.
     *
     * Both are validated with [Medication.validate] / [Regimen.validate] — and
     * the regimen must belong to [medication]. The patient must be in the
     * current circle and the signed-in role must be able to write.
     *
     * @return the stored id, or [MedsError.Invalid], [MedsError.NotAllowed],
     *   [MedsError.NoCircle], [MedsError.AlreadyExists].
     */
    suspend fun create(medication: Medication, firstRegimen: Regimen): MedsResult<MedicationId>

    /**
     * Updates the user-editable columns of an existing medication (name, form,
     * stock unit, instructions, prescription, policy overrides, `stopped_at`).
     * The regimen is **not** touched: use [changeRegimen] or [stop].
     *
     * @return lookup errors as for [create], with [MedsError.NotFound] when the
     *   row is not local.
     */
    suspend fun update(medication: Medication): MedsResult<MedicationId>

    /**
     * Ends [current] on the day before [effectiveFrom] and starts a new version
     * from that day, in one transaction, using [RegimenRules.changeRegimen].
     *
     * [effectiveFrom] must be after the current version's start date and at
     * most on its last day; anything else is [MedsError.Invalid] with the
     * offending [io.github.lgpy.familycare.feature.medications.domain.MedicationField]s.
     *
     * @return the new version's id.
     */
    suspend fun changeRegimen(
        current: Regimen,
        effectiveFrom: LocalDate,
        times: List<DoseTime> = current.times,
        scheduleType: ScheduleType = current.scheduleType,
        weekdays: Int? = current.weekdays,
        intervalDays: Int? = current.intervalDays,
        timeZone: ZoneId = current.timeZone,
    ): MedsResult<RegimenId>

    /**
     * Stops taking [medication]: ends [regimen] on [lastDay] (inclusive) and
     * sets the medication's `stopped_at`, in one transaction.
     *
     * [lastDay] may only move the end date earlier, never later, and never
     * before the regimen's start date ([RegimenRules.stop]). History (regimens,
     * dose log) is kept.
     */
    suspend fun stop(medication: Medication, regimen: Regimen, lastDay: LocalDate): MedsResult<Unit>
}
