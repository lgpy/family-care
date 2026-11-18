package io.github.lgpy.familycare.core.data.sync

import com.powersync.db.schema.Column
import com.powersync.db.schema.Schema
import com.powersync.db.schema.Table

/**
 * The client-side schema of the synced `core` tables, mirroring
 * `backend/supabase/migrations/20261118120000_core_care_circle.sql` and the four
 * streams in `backend/powersync/sync-config.yaml`.
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
 */
internal val CoreSchema: Schema = Schema(
    listOf(
        Table(
            name = CARE_CIRCLES_TABLE,
            columns = listOf(
                Column.text("name"),
                Column.text("created_at"),
                Column.text("updated_at"),
            ),
        ),
        Table(
            name = MEMBERSHIPS_TABLE,
            columns = listOf(
                Column.text("circle_id"),
                Column.text("user_id"),
                Column.text("role"),
                Column.text("created_at"),
                Column.text("updated_at"),
            ),
        ),
        Table(
            name = PROFILES_TABLE,
            columns = listOf(
                Column.text("display_name"),
                Column.text("preferred_language"),
                Column.text("created_at"),
                Column.text("updated_at"),
            ),
        ),
        Table(
            name = PATIENTS_TABLE,
            columns = listOf(
                Column.text("circle_id"),
                Column.text("display_name"),
                Column.text("linked_user_id"),
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
    ),
)

/** Client-side table names (no schema prefix) of the synced `core` tables. */
internal const val CARE_CIRCLES_TABLE: String = "care_circles"
internal const val MEMBERSHIPS_TABLE: String = "memberships"
internal const val PROFILES_TABLE: String = "profiles"
internal const val PATIENTS_TABLE: String = "patients"

/** PostgREST schema of every table, from the `Accept-Profile`/`Content-Profile` headers. */
internal const val CORE_SCHEMA: String = "core"

/**
 * The `core.invitations` table. It is deliberately not synced (it holds invitee
 * emails), so it is read and written online through PostgREST only.
 */
internal const val INVITATIONS_TABLE: String = "invitations"

/** Local database file, encrypted with [WrappedDatabaseKey]. */
internal const val DATABASE_FILE_NAME: String = "familycare.db"
