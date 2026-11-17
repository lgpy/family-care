package io.github.lgpy.familycare.core.data.sync

import com.powersync.db.schema.Column
import com.powersync.db.schema.Schema
import com.powersync.db.schema.Table

/**
 * The client-side schema of the M1 probe table, mirroring
 * `backend/supabase/migrations/20261117120000_core_sync_probe.sql` and the
 * `sync_probe` sync stream in `backend/powersync/sync-config.yaml`.
 *
 * The synced stream is named `sync_probe` on the client (the `core` schema is
 * only how the source database is addressed). PowerSync adds the `id` text
 * column itself, so it is not declared here.
 *
 * Every column is `text`: PostgREST and SQLite exchange timestamps and uuids as
 * text, and the app never does arithmetic on them locally.
 */
internal val ProbeSchema: Schema = Schema(
    listOf(
        Table(
            name = PROBE_TABLE,
            columns = listOf(
                Column.text("owner_id"),
                Column.text("note"),
                Column.text("created_at"),
                Column.text("updated_at"),
            ),
        ),
    ),
)

/** Client-side table name (no schema prefix). */
internal const val PROBE_TABLE: String = "sync_probe"

/** PostgREST schema holding the table, from the `Accept-Profile`/`Content-Profile` headers. */
internal const val PROBE_SCHEMA: String = "core"

/** Local database file, encrypted with [WrappedDatabaseKey]. */
internal const val DATABASE_FILE_NAME: String = "familycare.db"
