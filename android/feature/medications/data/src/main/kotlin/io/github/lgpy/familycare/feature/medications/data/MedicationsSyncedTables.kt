package io.github.lgpy.familycare.feature.medications.data

import com.powersync.db.schema.Column
import com.powersync.db.schema.Table
import io.github.lgpy.familycare.core.data.sync.DuplicateInsert
import io.github.lgpy.familycare.core.data.sync.SyncedTable
import io.github.lgpy.familycare.core.data.sync.SyncedTables
import io.github.lgpy.familycare.core.data.sync.UploadPolicy

/**
 * The `meds` tables this module syncs, exactly as
 * `backend/supabase/migrations/20261119120000_meds.sql` defines them and as the
 * `medications`, `regimens`, `dose_log` and `stock_movements` streams in
 * `backend/powersync/sync-config.yaml` deliver them.
 *
 * PowerSync adds `id` itself (a uuid generated on the phone, so a medication or
 * a dose entry can be created offline). Column types follow what PowerSync and
 * PostgREST exchange:
 *
 * - uuids, enum values, IANA zone names and timestamps are text;
 * - `weekdays`, `interval_days` and `low_stock_threshold_days` are integers;
 * - the two booleans are 0/1 integers (SQLite has no boolean) and are converted
 *   to JSON booleans at upload;
 * - `regimens.times` is `jsonb` on the server and JSON text locally, sent back
 *   as a JSON array;
 * - `dose_log.actual_quantity` is `numeric(7,3)` on the server and a decimal
 *   *text* locally (`"0.500"`), so three-decimal quantities are exact and are
 *   sent as JSON numbers;
 * - `start_date`/`end_date`/`prescription_valid_until` are `date` and are
 *   `YYYY-MM-DD` text;
 * - `scheduled_local` is `timestamp without time zone` and is stored in
 *   Postgres's own text form (`YYYY-MM-DD HH:MM:SS`), so a row that has synced
 *   back is byte-identical to one written on this phone (see
 *   [formatScheduledLocal]).
 *
 * The upload rules mirror the server's grants and triggers, so a local change
 * the server would refuse is never sent:
 *
 * - `medications`: insert and update of the columns the app owns (the server
 *   stamps `created_by`, `created_at`, `updated_at`); delete (owner-only in
 *   RLS, which the server decides).
 * - `regimens`: insert only, plus an update of `end_date` alone. The database
 *   trigger refuses every other change (a versioned regimen, D9), and there is
 *   no DELETE grant: a stopped treatment is an end date, not a missing row.
 * - `dose_log`: insert only. Append-only (D6): no UPDATE/DELETE grant, and a
 *   trigger refuses UPDATE for every role.
 * - `stock_movements`: insert only. Append-only (D6/D28): a count, a refill and
 *   a discard are new rows, never edits, and there is no DELETE grant. The
 *   server stamps `created_at` and `created_by`, so neither is uploaded (the
 *   `dose_log` convention).
 *
 * Both insert-only tables treat a duplicate INSERT as already applied: a retry
 * of an entry that reached the server is a success, never an UPDATE that the
 * server's own rules would refuse.
 */
internal object MedicationsSyncedTables : SyncedTables {

    override val tables: List<SyncedTable> = listOf(
        SyncedTable(
            schema = MEDS_SCHEMA,
            table = MEDICATIONS,
            upload = UploadPolicy(
                insertColumns = MEDICATION_UPLOAD_COLUMNS,
                updateColumns = MEDICATION_UPLOAD_COLUMNS - "patient_id",
                deleteAllowed = true,
                duplicateInsert = DuplicateInsert.UPDATE_EXISTING,
            ),
        ),
        SyncedTable(
            schema = MEDS_SCHEMA,
            table = REGIMENS,
            upload = UploadPolicy(
                insertColumns = REGIMEN_UPLOAD_COLUMNS,
                updateColumns = setOf("end_date"),
                // `times` is `jsonb` on the server and JSON text locally; the
                // uploader has to send it back as an array, not as a string.
                jsonColumns = setOf("times"),
                duplicateInsert = DuplicateInsert.ALREADY_APPLIED,
            ),
        ),
        SyncedTable(
            schema = MEDS_SCHEMA,
            table = DOSE_LOG,
            upload = UploadPolicy(
                insertColumns = DOSE_LOG_UPLOAD_COLUMNS,
                decimalColumns = setOf("actual_quantity"),
                booleanColumns = setOf("given_by_other"),
                duplicateInsert = DuplicateInsert.ALREADY_APPLIED,
            ),
        ),
        SyncedTable(
            schema = MEDS_SCHEMA,
            table = STOCK_MOVEMENTS,
            upload = UploadPolicy(
                insertColumns = STOCK_MOVEMENT_UPLOAD_COLUMNS,
                decimalColumns = setOf("quantity"),
                duplicateInsert = DuplicateInsert.ALREADY_APPLIED,
            ),
        ),
    )
}

/** PostgREST schema of the module's tables (`Content-Profile: meds`). */
internal const val MEDS_SCHEMA: String = "meds"

/** Client-side table names: the server names, without the schema. */
internal const val MEDICATIONS_TABLE: String = "medications"
internal const val REGIMENS_TABLE: String = "regimens"
internal const val DOSE_LOG_TABLE: String = "dose_log"
internal const val STOCK_MOVEMENTS_TABLE: String = "stock_movements"

internal val MEDICATIONS: Table = Table(
    name = MEDICATIONS_TABLE,
    columns = listOf(
        Column.text("patient_id"),
        Column.text("name"),
        Column.text("active_ingredient"),
        Column.text("strength"),
        Column.text("form"),
        Column.text("stock_unit"),
        Column.text("instructions"),
        Column.integer("requires_prescription"),
        Column.text("prescription_valid_until"),
        Column.integer("low_stock_threshold_days"),
        Column.text("reminder_policy"),
        Column.text("primary_caregiver_id"),
        Column.text("stopped_at"),
        Column.text("created_at"),
        Column.text("updated_at"),
        Column.text("created_by"),
    ),
)

internal val REGIMENS: Table = Table(
    name = REGIMENS_TABLE,
    columns = listOf(
        Column.text("medication_id"),
        Column.text("schedule_type"),
        Column.integer("weekdays"),
        Column.integer("interval_days"),
        Column.text("start_date"),
        Column.text("end_date"),
        Column.text("time_zone"),
        Column.text("times"),
        Column.text("created_at"),
        Column.text("created_by"),
    ),
)

internal val DOSE_LOG: Table = Table(
    name = DOSE_LOG_TABLE,
    columns = listOf(
        Column.text("regimen_id"),
        Column.text("medication_id"),
        Column.text("scheduled_local"),
        Column.text("outcome"),
        Column.text("skip_reason"),
        Column.text("note"),
        Column.text("actual_at"),
        Column.text("actual_quantity"),
        Column.text("confirmed_by"),
        Column.text("given_by_user_id"),
        Column.integer("given_by_other"),
        Column.text("given_by_name"),
        Column.text("corrects_entry_id"),
        Column.text("created_at"),
    ),
)

/**
 * `meds.stock_movements` (D28): the append-only stock ledger.
 *
 * `quantity` is `numeric(9,3)` on the server and decimal text locally, so
 * 2.5 tablets are exact; `occurred_at` is a `timestamptz` and is text locally,
 * in the same form the dose log writes `actual_at`.
 */
internal val STOCK_MOVEMENTS: Table = Table(
    name = STOCK_MOVEMENTS_TABLE,
    columns = listOf(
        Column.text("medication_id"),
        Column.text("kind"),
        Column.text("quantity"),
        Column.text("reason"),
        Column.text("occurred_at"),
        Column.text("created_at"),
        Column.text("created_by"),
    ),
)

/** Columns the app writes on a medication; the server owns the timestamps. */
private val MEDICATION_UPLOAD_COLUMNS: Set<String> = setOf(
    "patient_id",
    "name",
    "active_ingredient",
    "strength",
    "form",
    "stock_unit",
    "instructions",
    "requires_prescription",
    "prescription_valid_until",
    "low_stock_threshold_days",
    "reminder_policy",
    "primary_caregiver_id",
    "stopped_at",
)

/** Columns the app writes on a regimen version (`created_at` is the server's). */
private val REGIMEN_UPLOAD_COLUMNS: Set<String> = setOf(
    "medication_id",
    "schedule_type",
    "weekdays",
    "interval_days",
    "start_date",
    "end_date",
    "time_zone",
    "times",
)

/** Columns the app writes on a dose-log entry (`created_at` is the server's). */
private val DOSE_LOG_UPLOAD_COLUMNS: Set<String> = setOf(
    "regimen_id",
    "medication_id",
    "scheduled_local",
    "outcome",
    "skip_reason",
    "note",
    "actual_at",
    "actual_quantity",
    "confirmed_by",
    "given_by_user_id",
    "given_by_other",
    "given_by_name",
    "corrects_entry_id",
)

/**
 * Columns the app writes on a stock movement. `created_by` is the server's
 * (`auth.uid()` default), as it is for the dose log, and `created_at` too.
 */
private val STOCK_MOVEMENT_UPLOAD_COLUMNS: Set<String> = setOf(
    "medication_id",
    "kind",
    "quantity",
    "reason",
    "occurred_at",
)
