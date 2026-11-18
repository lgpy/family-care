package io.github.lgpy.familycare.feature.patientinfo.data

import com.powersync.db.schema.Column
import com.powersync.db.schema.Table
import io.github.lgpy.familycare.core.data.sync.DuplicateInsert
import io.github.lgpy.familycare.core.data.sync.SyncedTable
import io.github.lgpy.familycare.core.data.sync.SyncedTables
import io.github.lgpy.familycare.core.data.sync.UploadPolicy

/**
 * The `info.patient_info` table this module syncs, exactly as
 * `backend/supabase/migrations/20261122120000_appointments_and_info.sql` defines
 * it and as the `patient_info` stream in `backend/powersync/sync-config.yaml`
 * delivers it.
 *
 * PowerSync adds `id` itself. The row's id **is** the patient id (`id =
 * patient_id`, a server check constraint): that deterministic key is what makes
 * two phones creating the row offline converge on one row instead of two.
 *
 * Column types follow what PowerSync and PostgREST exchange: uuids and
 * timestamps are text, and the three `jsonb` columns (`allergies`,
 * `conditions`, `emergency_contacts`) are JSON text locally — like
 * `regimens.times` — and are sent back as JSON arrays rather than strings.
 *
 * The upload rules mirror the server's grants and trigger:
 *
 * - insert and update of the columns the app owns (`created_at`, `updated_at`
 *   and `updated_by` are the server's: `updated_by` is set by the trigger to
 *   `auth.uid()`);
 * - `patient_id` is never updated: the trigger refuses moving the row to
 *   another patient, so the update policy omits it;
 * - no delete: the app offers no "delete patient info", and the server's
 *   DELETE grant is owner-only.
 *
 * A duplicate INSERT becomes an UPDATE: the phone chose the id (the patient's),
 * so a local `INSERT OR REPLACE` — or a retry whose response was lost — is a
 * legitimate edit (D29, last write wins).
 */
internal object PatientInfoSyncedTables : SyncedTables {

    override val tables: List<SyncedTable> = listOf(
        SyncedTable(
            schema = INFO_SCHEMA,
            table = PATIENT_INFO,
            upload = UploadPolicy(
                insertColumns = PATIENT_INFO_UPLOAD_COLUMNS,
                updateColumns = PATIENT_INFO_UPLOAD_COLUMNS - "patient_id",
                jsonColumns = JSON_COLUMNS,
                duplicateInsert = DuplicateInsert.UPDATE_EXISTING,
            ),
        ),
    )
}

/** PostgREST schema of the module's table (`Content-Profile: info`). */
internal const val INFO_SCHEMA: String = "info"

/** Client-side table name: the server name, without the schema. */
internal const val PATIENT_INFO_TABLE: String = "patient_info"

/** The `jsonb` columns: JSON text locally, JSON values on the wire. */
private val JSON_COLUMNS: Set<String> = setOf("allergies", "conditions", "emergency_contacts")

internal val PATIENT_INFO: Table = Table(
    name = PATIENT_INFO_TABLE,
    columns = listOf(
        Column.text("patient_id"),
        Column.text("allergies"),
        Column.text("conditions"),
        Column.text("sns_number"),
        Column.text("family_doctor"),
        Column.text("health_centre"),
        Column.text("health_centre_phone"),
        Column.text("emergency_contacts"),
        Column.text("notes"),
        Column.text("created_at"),
        Column.text("updated_at"),
        Column.text("updated_by"),
    ),
)

/** Columns the app writes on the row; the server owns the timestamps. */
private val PATIENT_INFO_UPLOAD_COLUMNS: Set<String> = setOf(
    "patient_id",
    "allergies",
    "conditions",
    "sns_number",
    "family_doctor",
    "health_centre",
    "health_centre_phone",
    "emergency_contacts",
    "notes",
)
