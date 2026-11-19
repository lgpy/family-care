package io.github.lgpy.familycare.core.data.sync

import com.powersync.db.schema.Column
import com.powersync.db.schema.Schema
import com.powersync.db.schema.Table

/**
 * The client-side schema of the synced `core` tables, mirroring
 * `backend/supabase/migrations/20261118120000_core_care_circle.sql` and the
 * streams in `backend/powersync/sync-config.yaml`, together with the rules that
 * let those rows be uploaded.
 *
 * The tables keep their server names: a Sync Streams query on `"core"."patients"`
 * creates the client table `patients`. PowerSync adds the `id` text column to
 * every table itself, so it is not declared here; the id is a uuid generated on
 * the phone (`uuid()`), which is what lets a patient be created offline.
 *
 * Column types follow what PowerSync and PostgREST exchange: uuids, enums and
 * timestamps are text, the two minute counts are integers. Every server column
 * of a synced table is declared, including `created_at`/`updated_at` (read-only
 * here: the server stamps them and the uploader never sends them).
 *
 * `memberships` is synced but never written locally, because the server has no
 * INSERT/UPDATE/DELETE grant on it: membership changes go through the `core.*`
 * RPCs.
 *
 * Feature modules contribute their own tables through [SyncedTables]; this
 * object is only the core's contribution and is composed with theirs by
 * [TableCatalog] (see `DataLayer.create`).
 */
internal object CoreSyncedTables : SyncedTables {

    /**
     * Everything on a patient the app may write (the server stamps the rest).
     *
     * Inside the object, not at file scope: a top-level property would put this
     * file's initialisation in a cycle with [CoreSchema], which reads [tables].
     */
    private val PATIENT_UPLOAD_COLUMNS: Set<String> = setOf(
        "circle_id",
        "display_name",
        "linked_user_id",
        "birth_year",
        "reminder_policy",
        "primary_caregiver_id",
        "escalation_delay_minutes",
        "missed_window_minutes",
        "agreement_note",
        "archived_at",
    )

    override val tables: List<SyncedTable> = listOf(
        SyncedTable(
            schema = CORE_SCHEMA,
            table = Table(
                name = CARE_CIRCLES_TABLE,
                columns = listOf(
                    Column.text("name"),
                    Column.text("created_at"),
                    Column.text("updated_at"),
                ),
            ),
            // The row is created by `core.create_circle`; the server grants
            // only SELECT and UPDATE, so a local insert is not uploaded.
            upload = UploadPolicy(
                updateColumns = setOf("name"),
                duplicateInsert = DuplicateInsert.UPDATE_EXISTING,
            ),
        ),
        SyncedTable(
            schema = CORE_SCHEMA,
            table = Table(
                name = MEMBERSHIPS_TABLE,
                columns = listOf(
                    Column.text("circle_id"),
                    Column.text("user_id"),
                    Column.text("role"),
                    Column.text("created_at"),
                    Column.text("updated_at"),
                ),
            ),
            // No DML grant at all: every membership change is an RPC.
            upload = UploadPolicy(),
        ),
        SyncedTable(
            schema = CORE_SCHEMA,
            table = Table(
                name = PROFILES_TABLE,
                columns = listOf(
                    Column.text("display_name"),
                    Column.text("preferred_language"),
                    Column.text("created_at"),
                    Column.text("updated_at"),
                ),
            ),
            // The profile row is created by a server trigger; the user edits
            // their name and language.
            upload = UploadPolicy(
                updateColumns = setOf("display_name", "preferred_language"),
                duplicateInsert = DuplicateInsert.UPDATE_EXISTING,
            ),
        ),
        SyncedTable(
            schema = CORE_SCHEMA,
            table = Table(
                name = PATIENTS_TABLE,
                columns = listOf(
                    Column.text("circle_id"),
                    Column.text("display_name"),
                    Column.text("linked_user_id"),
                    Column.integer("birth_year"),
                    Column.text("reminder_policy"),
                    Column.text("primary_caregiver_id"),
                    Column.integer("escalation_delay_minutes"),
                    Column.integer("missed_window_minutes"),
                    Column.text("agreement_note"),
                    Column.text("archived_at"),
                    Column.text("created_at"),
                    Column.text("updated_at"),
                ),
            ),
            // Everything on a patient except the server-managed timestamps.
            // `circle_id` is never changed by an edit (a patient never moves),
            // but it stays in the update set so a retried insert's fallback
            // sends the same row the PUT carried, as it did in M2.
            upload = UploadPolicy(
                insertColumns = PATIENT_UPLOAD_COLUMNS,
                updateColumns = PATIENT_UPLOAD_COLUMNS,
                deleteAllowed = true,
                duplicateInsert = DuplicateInsert.UPDATE_EXISTING,
            ),
        ),
    )
}

/**
 * The client-side schema of the core tables alone (tests and diagnostics).
 *
 * Lazy on purpose: a plain initialiser would make the first access through
 * [CoreSyncedTables] (an object whose own initialiser is still running) observe
 * `tables` as null.
 */
internal val CoreSchema: Schema by lazy { Schema(CoreSyncedTables.tables.map { it.table }) }

/** Client-side table names (no schema prefix) of the synced `core` tables. */
internal const val CARE_CIRCLES_TABLE: String = "care_circles"
internal const val MEMBERSHIPS_TABLE: String = "memberships"
internal const val PROFILES_TABLE: String = "profiles"
internal const val PATIENTS_TABLE: String = "patients"

/** PostgREST schema of the core tables, from the `Accept-Profile`/`Content-Profile` headers. */
internal const val CORE_SCHEMA: String = "core"

/**
 * The `core.invitations` table. It is deliberately not synced (it holds invitee
 * emails), so it is read and written online through PostgREST only.
 */
internal const val INVITATIONS_TABLE: String = "invitations"

/** Local database file, encrypted with [WrappedDatabaseKey]. */
internal const val DATABASE_FILE_NAME: String = "familycare.db"
