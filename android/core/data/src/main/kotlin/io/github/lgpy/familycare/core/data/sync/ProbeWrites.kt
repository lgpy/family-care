package io.github.lgpy.familycare.core.data.sync

import com.powersync.db.crud.CrudEntry
import com.powersync.db.crud.CrudTransaction
import com.powersync.db.crud.UpdateType

/**
 * A local change turned into the operation the backend needs.
 *
 * PowerSync records every write to the local database in an upload queue and
 * hands it back as [CrudEntry]s: `PUT` (insert or replace, all non-null
 * columns), `PATCH` (only the changed columns) and `DELETE`. PostgREST needs the
 * matching upsert, update and delete, which this type expresses in plain Kotlin
 * so the mapping can be unit-tested with a fake uploader.
 *
 * @param id Row id (`sync_probe.id`), used as the filter for update/delete.
 * @param kind Which PostgREST operation to use.
 * @param values Column values; null values are dropped so server defaults (the
 *   `updated_at` trigger) keep working.
 */
internal data class ProbeWrite(
    val id: String,
    val kind: ProbeWriteKind,
    val values: Map<String, String?>,
)

internal enum class ProbeWriteKind { UPSERT, UPDATE, DELETE }

/**
 * Maps one queued change onto a [ProbeWrite], or null when there is nothing to
 * send (an insert or update with no data).
 */
internal fun CrudEntry.toProbeWrite(): ProbeWrite? = when (op) {
    UpdateType.PUT -> ProbeWrite(id = id, kind = ProbeWriteKind.UPSERT, values = opValues())
    UpdateType.PATCH -> ProbeWrite(id = id, kind = ProbeWriteKind.UPDATE, values = opValues())
    UpdateType.DELETE -> ProbeWrite(id = id, kind = ProbeWriteKind.DELETE, values = emptyMap())
}

/** The changes in [this] transaction that belong to the probe table. */
internal fun CrudTransaction.probeWrites(): List<ProbeWrite> =
    crud.filter { it.table == PROBE_TABLE }.mapNotNull { it.toProbeWrite() }

private fun CrudEntry.opValues(): Map<String, String?> = opData?.filterValues { it != null }.orEmpty()

/** Sends the writes to the backend, in order. */
internal suspend fun uploadProbeWrites(
    writes: List<ProbeWrite>,
    uploader: ProbeUploader,
) {
    writes.forEach { write ->
        when (write.kind) {
            ProbeWriteKind.UPSERT -> uploader.upsert(write.id, write.values)
            ProbeWriteKind.UPDATE -> uploader.update(write.id, write.values)
            ProbeWriteKind.DELETE -> uploader.delete(write.id)
        }
    }
}
