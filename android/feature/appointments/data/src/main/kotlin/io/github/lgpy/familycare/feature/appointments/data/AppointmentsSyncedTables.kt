package io.github.lgpy.familycare.feature.appointments.data

import com.powersync.db.schema.Column
import com.powersync.db.schema.Table
import io.github.lgpy.familycare.core.data.sync.DuplicateInsert
import io.github.lgpy.familycare.core.data.sync.SyncedTable
import io.github.lgpy.familycare.core.data.sync.SyncedTables
import io.github.lgpy.familycare.core.data.sync.UploadPolicy

/**
 * The `appointments` table this module syncs, exactly as
 * `backend/supabase/migrations/20261122120000_appointments_and_info.sql` defines
 * it and as the `appointments` stream in `backend/powersync/sync-config.yaml`
 * delivers it.
 *
 * Column types follow what PowerSync and PostgREST exchange (the meds
 * conventions):
 *
 * - uuids, enum values, IANA zone names and timestamps are text;
 * - `duration_minutes` is an integer;
 * - `starts_local` is `timestamp without time zone` and is stored in Postgres's
 *   own text form (`YYYY-MM-DD HH:MM:SS`), so a row that has synced back is
 *   byte-identical to one written on this phone (see [formatStartsLocal]).
 *
 * The upload rules mirror the server's grants and triggers, so a local change
 * the server would refuse is never sent:
 *
 * - insert and update of the columns the app owns; `patient_id` is immutable
 *   (the trigger refuses a change) and the server stamps `created_by`,
 *   `created_at`, `updated_at` and `updated_by`;
 * - `cancelled_at` is part of an update: cancelling an appointment is a stamp,
 *   never a delete (D29);
 * - delete is owner-only in RLS, which the server decides.
 */
internal object AppointmentsSyncedTables : SyncedTables {

    override val tables: List<SyncedTable> = listOf(
        SyncedTable(
            schema = APPOINTMENTS_SCHEMA,
            table = APPOINTMENTS,
            upload = UploadPolicy(
                insertColumns = APPOINTMENT_INSERT_COLUMNS,
                updateColumns = APPOINTMENT_UPDATE_COLUMNS,
                deleteAllowed = true,
                duplicateInsert = DuplicateInsert.UPDATE_EXISTING,
            ),
        ),
    )
}

/** PostgREST schema of the module's table (`Content-Profile: appointments`). */
internal const val APPOINTMENTS_SCHEMA: String = "appointments"

/** Client-side table name: the server name, without the schema. */
internal const val APPOINTMENTS_TABLE: String = "appointments"

internal val APPOINTMENTS: Table = Table(
    name = APPOINTMENTS_TABLE,
    columns = listOf(
        Column.text("patient_id"),
        Column.text("kind"),
        Column.text("title"),
        Column.text("starts_local"),
        Column.text("time_zone"),
        Column.integer("duration_minutes"),
        Column.text("place"),
        Column.text("doctor"),
        Column.text("preparation"),
        Column.text("notes"),
        Column.text("companion_user_id"),
        Column.text("follow_up_of"),
        Column.text("cancelled_at"),
        Column.text("created_at"),
        Column.text("created_by"),
        Column.text("updated_at"),
        Column.text("updated_by"),
    ),
)

/** Columns the app writes on insert; the server owns the timestamps and `*_by`. */
private val APPOINTMENT_INSERT_COLUMNS: Set<String> = setOf(
    "patient_id",
    "kind",
    "title",
    "starts_local",
    "time_zone",
    "duration_minutes",
    "place",
    "doctor",
    "preparation",
    "notes",
    "companion_user_id",
    "follow_up_of",
)

/**
 * Columns an update may carry. `patient_id` is deliberately absent: an
 * appointment never moves to another patient (the server trigger refuses it),
 * and `cancelled_at` is included because cancelling is an in-place stamp.
 */
private val APPOINTMENT_UPDATE_COLUMNS: Set<String> =
    (APPOINTMENT_INSERT_COLUMNS - "patient_id") + "cancelled_at"
