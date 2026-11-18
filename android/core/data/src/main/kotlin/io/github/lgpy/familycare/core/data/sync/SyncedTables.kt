package io.github.lgpy.familycare.core.data.sync

import com.powersync.db.schema.Table

/**
 * The extension point a feature module uses to join the sync layer (D5).
 *
 * `core:data` owns one PowerSync database, one schema and one uploader. A
 * feature module that owns synced tables (the medications module's
 * `medications`, `regimens` and `dose_log`) contributes them here instead of
 * editing the core: the app's DI passes every contribution to
 * `DataLayer.create`, which merges them into the local schema and the upload
 * rules. The core therefore never depends on a feature.
 *
 * Each table declares its own PostgREST schema (`meds`, `core`, …) because the
 * tables keep their server names and the schemas stay separate (architecture
 * "Rules", D5).
 *
 * Contribution rules:
 * - a table name must be unique across contributions (checked when the catalog
 *   is built, so a clash fails the app at start-up rather than on a phone);
 * - every column of the synced table is declared, exactly as the server sends
 *   it, so PowerSync creates the local view with the right shape;
 * - [SyncedTable.upload] describes what may reach PostgREST. Anything it does
 *   not allow is dropped from the upload queue without a request.
 */
public interface SyncedTables {

    /** The tables this module adds to the local schema, with their upload rules. */
    public val tables: List<SyncedTable>
}

/**
 * One synced table: its PowerSync definition, the PostgREST schema it lives in
 * and how writes to it are uploaded.
 *
 * @param schema PostgREST schema (`core`, `meds`, …) sent as
 *   `Content-Profile`/`Accept-Profile`.
 * @param table PowerSync table definition; `id` is added by PowerSync itself.
 * @param upload What the client may write and how.
 */
public class SyncedTable(
    public val schema: String,
    public val table: Table,
    public val upload: UploadPolicy,
)

/**
 * The upload half of a synced table: which queued local changes may reach the
 * server, and in what shape.
 *
 * PowerSync records every local write as a `PUT` (insert or replace, all
 * non-null columns), `PATCH` (only the changed columns) or `DELETE`. Each maps
 * onto one PostgREST statement, and the server's grants and triggers decide
 * what is possible:
 *
 * - `medications`: INSERT, UPDATE and (owner-only) DELETE.
 * - `regimens`: INSERT, and an UPDATE of `end_date` only — the database trigger
 *   refuses any other change (a versioned regimen, D9). No DELETE: a stopped
 *   treatment is an end date, not a missing row.
 * - `dose_log`: INSERT only. The table is append-only (D6): no UPDATE or
 *   DELETE grant exists, and a trigger refuses an UPDATE for every role.
 *
 * A local change that the policy does not allow is dropped from the upload
 * queue (the transaction completes without a request), mirroring how
 * `memberships` was already treated in M2.
 *
 * @param insertColumns Columns sent on an INSERT. Empty means the table is never
 *   inserted from the phone.
 * @param updateColumns Columns an UPDATE may carry. Empty drops every `PATCH`.
 * @param deleteAllowed Whether a local `DELETE` is uploaded.
 * @param duplicateInsert What a unique violation on an INSERT means. A retry of
 *   an INSERT whose response was lost is the usual cause (see
 *   [DuplicateInsert]).
 * @param decimalColumns Columns stored as decimal text locally (`"0.500"`) and
 *   sent as JSON numbers (`0.500`), which is what Postgres `numeric` accepts.
 * @param jsonColumns Columns stored as JSON text locally and sent as JSON
 *   values, so a `jsonb` column receives an array rather than a string.
 * @param booleanColumns Columns stored as 0/1 integers locally (SQLite has no
 *   boolean) and sent as JSON booleans.
 */
public data class UploadPolicy(
    public val insertColumns: Set<String> = emptySet(),
    public val updateColumns: Set<String> = emptySet(),
    public val deleteAllowed: Boolean = false,
    public val duplicateInsert: DuplicateInsert = DuplicateInsert.ALREADY_APPLIED,
    public val decimalColumns: Set<String> = emptySet(),
    public val jsonColumns: Set<String> = emptySet(),
    public val booleanColumns: Set<String> = emptySet(),
) {

    /** Whether nothing about this table is ever uploaded (`memberships`). */
    public val isReadOnly: Boolean
        get() = insertColumns.isEmpty() && updateColumns.isEmpty() && !deleteAllowed
}

/**
 * What a `23505` (unique violation) on an INSERT means for a table.
 *
 * Both cases are retries of an INSERT that reached the server but whose
 * response was lost, or a local replace of a row that already exists.
 */
public enum class DuplicateInsert {

    /**
     * The row is already on the server exactly as it was sent, so the write
     * succeeded. Used by insert-only and append-only tables (a dose-log entry
     * or a regimen version): falling back to an UPDATE would be refused by the
     * server's own rules and shown to the user as a refusal of a write that
     * actually worked.
     */
    ALREADY_APPLIED,

    /**
     * Send the same values as an UPDATE instead. Used by editable rows whose
     * id the phone chose (`patients`, `profiles`), where a local
     * `INSERT OR REPLACE` is a legitimate edit.
     */
    UPDATE_EXISTING,
}
