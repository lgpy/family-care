package io.github.lgpy.familycare.feature.medications.data

import io.github.lgpy.familycare.core.data.sync.DuplicateInsert
import io.github.lgpy.familycare.core.data.sync.SyncedTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The medications module's contribution to the sync layer: its three `meds`
 * tables and the upload policy of each.
 *
 * `core:data` owns the mapping from a queued change to a request, and its own
 * unit tests pin that a table with no update columns drops every PATCH and a
 * table without `deleteAllowed` drops every DELETE. What this test pins is that
 * the module declares exactly those policies, so a `dose_log` PATCH or DELETE and
 * a `regimens` DELETE can never reach the queue, and that a regimen update can
 * only ever carry `end_date`.
 *
 * The behaviour against real queued rows (a real PowerSync queue, real PostgREST
 * requests) is in `:feature:medications:data:jvmtests`, where the core's
 * internal mapping types are reachable.
 */
class MedsUploadTest {

    private val tables: Map<String, SyncedTable> =
        MedicationsSyncedTables.tables.associateBy { it.table.name }

    @Test
    fun `the module contributes the three meds tables in the meds schema`() {
        assertEquals(
            setOf(MEDICATIONS_TABLE, REGIMENS_TABLE, DOSE_LOG_TABLE),
            tables.keys,
        )
        assertTrue(tables.values.all { it.schema == MEDS_SCHEMA })
    }

    @Test
    fun `a dose log is append-only, so no patch or delete can be uploaded`() {
        val policy = tables.getValue(DOSE_LOG_TABLE).upload

        assertFalse(policy.insertColumns.isEmpty())
        // No UPDATE grant on the server and a trigger refuses one for every role.
        assertTrue(policy.updateColumns.isEmpty())
        // Entries are never removed: a mistake is a correction entry (D6).
        assertFalse(policy.deleteAllowed)
        // A retry of an insert whose response was lost is a success, never an
        // UPDATE the trigger would reject.
        assertEquals(DuplicateInsert.ALREADY_APPLIED, policy.duplicateInsert)
    }

    @Test
    fun `a dose log quantity is a decimal and given_by_other is a boolean`() {
        val policy = tables.getValue(DOSE_LOG_TABLE).upload

        assertEquals(setOf("actual_quantity"), policy.decimalColumns)
        assertEquals(setOf("given_by_other"), policy.booleanColumns)
        assertTrue(policy.jsonColumns.isEmpty())
    }

    @Test
    fun `a regimen is versioned, so an update only ever carries end_date`() {
        val policy = tables.getValue(REGIMENS_TABLE).upload

        assertEquals(setOf("end_date"), policy.updateColumns)
        // A stopped treatment is an end date, not a missing row, and the server
        // grants no DELETE.
        assertFalse(policy.deleteAllowed)
        // A new version is a new row with an id generated on the phone; a retry is
        // already applied.
        assertEquals(DuplicateInsert.ALREADY_APPLIED, policy.duplicateInsert)
        assertTrue(policy.insertColumns.contains("medication_id"))
        assertTrue(policy.insertColumns.contains("times"))
        // The server's trigger refuses any other change, so the timestamps stay out.
        assertFalse(policy.insertColumns.contains("created_at"))
    }

    @Test
    fun `regimen times are sent as a json value, not as text`() {
        val policy = tables.getValue(REGIMENS_TABLE).upload

        // Stored as JSON text locally (`jsonb` is text to SQLite) and sent back as
        // an array, which is what the times_are_valid check expects.
        assertEquals(setOf("times"), policy.jsonColumns)
    }

    @Test
    fun `a medication may be inserted, edited and deleted, but never moves patient`() {
        val policy = tables.getValue(MEDICATIONS_TABLE).upload

        assertTrue(policy.insertColumns.contains("patient_id"))
        assertTrue(policy.updateColumns.contains("name"))
        assertTrue(policy.updateColumns.contains("stopped_at"))
        // The server trigger refuses moving a medication to another patient.
        assertFalse(policy.updateColumns.contains("patient_id"))
        assertTrue(policy.deleteAllowed)
        // An editable row: a local INSERT OR REPLACE of a synced row is an edit.
        assertEquals(DuplicateInsert.UPDATE_EXISTING, policy.duplicateInsert)
        // The server stamps these.
        assertFalse(policy.insertColumns.contains("created_at"))
        assertFalse(policy.insertColumns.contains("updated_at"))
        assertFalse(policy.insertColumns.contains("created_by"))
    }

    @Test
    fun `every declared column exists on its table`() {
        tables.forEach { (name, synced) ->
            val declared = synced.table.columns.map { it.name }.toSet()
            val uploadable = synced.upload.insertColumns + synced.upload.updateColumns

            assertEquals(
                "$name declares an upload column it does not sync",
                emptySet<String>(),
                uploadable - declared,
            )
        }
    }

    @Test
    fun `the appended columns are the ones the policy converts, not the read-only ones`() {
        // A guard against a typo that would silently send text to a numeric column.
        val regimen = tables.getValue(REGIMENS_TABLE).upload
        assertEquals(setOf("times"), regimen.jsonColumns)
        assertNotEquals(setOf("start_date"), regimen.jsonColumns)
    }
}
