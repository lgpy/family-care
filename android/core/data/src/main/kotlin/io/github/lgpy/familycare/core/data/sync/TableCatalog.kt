package io.github.lgpy.familycare.core.data.sync

import com.powersync.db.schema.Schema

/**
 * Every synced table the app knows about: the core's own tables plus the ones
 * feature modules contribute (D5).
 *
 * Built once by `DataLayer.create` from the contributions the app's DI passes
 * in. It produces the PowerSync [schema] and answers, per table, which
 * PostgREST schema to write to and what the upload policy allows.
 *
 * Duplicate table names are rejected here, at start-up, rather than letting
 * PowerSync silently create two definitions of one view.
 */
internal class TableCatalog(contributions: List<SyncedTables>) {

    private val byName: Map<String, SyncedTable>

    /** The schema the local PowerSync database is opened with. */
    val schema: Schema

    init {
        val tables = contributions.flatMap { it.tables }
        val duplicates = tables.groupBy { it.table.name }
            .filterValues { it.size > 1 }
            .keys
        require(duplicates.isEmpty()) {
            "Two modules declared the same synced table: ${duplicates.sorted()}"
        }

        byName = tables.associateBy { it.table.name }
        schema = Schema(tables.map { it.table })
    }

    /** The definition and upload rules of [table], or null when it is not synced. */
    fun syncedTable(table: String): SyncedTable? = byName[table]
}
