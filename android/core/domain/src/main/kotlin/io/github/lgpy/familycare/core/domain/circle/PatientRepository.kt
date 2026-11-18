package io.github.lgpy.familycare.core.domain.circle

import kotlinx.coroutines.flow.Flow

/**
 * The patients of the current care circle.
 *
 * Reads come from the encrypted local database, writes are local too: the row
 * appears immediately, works offline (D3), and PowerSync uploads it when the
 * network returns. Validation runs before the write, so a mistake is a
 * [CoreResult.Failure] a form can show rather than a constraint error the upload
 * queue can only report as permanent.
 *
 * Feature modules use [PatientId] only (D5); they read the display name through
 * [patients] or [observe].
 */
interface PatientRepository {

    /**
     * The current circle's active patients, sorted by display name (case
     * insensitive). Archived patients ([Patient.isArchived]) are excluded. Emits
     * again on every local or synced change; empty while the rows are still
     * downloading.
     */
    val patients: Flow<List<Patient>>

    /**
     * One patient by id, or null when it is not (or no longer) in the local
     * database. Emits again when the row changes.
     */
    fun observe(patientId: PatientId): Flow<Patient?>

    /**
     * A patient that has never been stored, with a phone-generated id and the
     * defaults from docs/domain/care-circle.md:
     *
     * - `primary_with_escalation`;
     * - primary caregiver = [linkedUserId] when the patient also uses the app
     *   (self-managed), otherwise [chosenPrimaryCaregiverId];
     * - escalation delay 30 minutes, missed window 120 minutes.
     *
     * Pass the result to [save]. The values are not stored until then, and the
     * id only becomes a row once [save] succeeds.
     */
    fun newPatient(
        circleId: CareCircleId,
        displayName: String,
        linkedUserId: String? = null,
        chosenPrimaryCaregiverId: String? = null,
        reminderPolicy: ReminderPolicy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
        escalationDelayMinutes: Int = PatientRules.DEFAULT_ESCALATION_DELAY_MINUTES,
        missedWindowMinutes: Int = PatientRules.DEFAULT_MISSED_WINDOW_MINUTES,
        agreementNote: String? = null,
    ): Patient

    /**
     * Inserts [patient] when its id is new, updates it otherwise. Offline-first:
     * only the local database is written here.
     *
     * The name is trimmed and a blank agreement note becomes null
     * ([Patient.normalized]). The patient must belong to the current circle, and
     * the signed-in role must be able to write ([MemberRole.canWrite]: owner or
     * caregiver).
     *
     * @return the stored patient's id, or [CoreError.Invalid] with the offending
     *   [CoreField]s (see [Patient.validate]; use [Patient.validateAgainst] with
     *   [CareCircleRepository.members] beforehand to catch the relationship
     *   rules too), [CoreError.NotAllowed] for a viewer, [CoreError.NoCircle].
     */
    suspend fun save(patient: Patient): CoreResult<PatientId>
}
