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
     * The current circle's archived patients ([Patient.isArchived]), sorted
     * like [patients]. They are kept apart so the list can show them collapsed
     * (D30); they still sync, so the profile opens read-only.
     */
    val archivedPatients: Flow<List<Patient>>

    /**
     * One patient by id, or null when it is not (or no longer) in the local
     * database. Emits again when the row changes.
     *
     * An archived patient is returned too: the profile must still open, with a
     * banner and no edit action.
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

    /**
     * Archives the patient: the local `archived_at` is set and uploaded, which
     * makes the patient read-only for everyone and hides them from Today, the
     * Agenda and reminders (D30). Owners only; the server refuses anyone else.
     *
     * Archiving is reversible ([unarchive]) and keeps every row, so it works
     * offline like any other local write.
     *
     * @return [CoreError.NotAllowed] for a caregiver or viewer, [CoreError.NotFound]
     *   when the patient is not in the local database.
     */
    suspend fun archive(patientId: PatientId): CoreResult<PatientId>

    /** Clears `archived_at`, making the patient editable again. Owners only. */
    suspend fun unarchive(patientId: PatientId): CoreResult<PatientId>

    /**
     * Erases the patient and everything that belongs to them through
     * `core.erase_patient(p_patient_id)`. Owners only; **cannot be undone**,
     * and needs a connection (the RPC is the only path, D30).
     *
     * The local row disappears when the sync brings the server's delete; this
     * call does not delete it locally, because a local delete would be queued
     * as a direct `core.patients` delete, which the server refuses.
     *
     * @return [CoreError.NotAllowed] for anyone but an owner, [CoreError.Offline]
     *   with no connection, [CoreError.NotFound] when the patient is gone,
     *   [CoreError.Rejected] when the server refused the call.
     */
    suspend fun erase(patientId: PatientId): CoreResult<PatientId>

    /**
     * Records the export **before** any file is written, through
     * `core.log_patient_export(p_patient_id, p_format)` (D30,
     * docs/domain/patient-lifecycle.md "Export").
     *
     * Owners and caregivers may export; a viewer is refused locally and by the
     * server. The call is the only online part of an export, so it needs a
     * connection: [CoreError.Offline] means the caller must not generate a file.
     *
     * @return [CoreError.NotAllowed] for a viewer, [CoreError.Offline] with no
     *   connection, [CoreError.NotFound] when the patient is not local,
     *   [CoreError.Rejected] when the server refused the call.
     */
    suspend fun logExport(
        patientId: PatientId,
        format: PatientExportFormat,
    ): CoreUnitResult
}
