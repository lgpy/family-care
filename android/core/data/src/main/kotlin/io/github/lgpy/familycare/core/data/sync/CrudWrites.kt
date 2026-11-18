package io.github.lgpy.familycare.core.data.sync

import com.powersync.db.crud.CrudEntry
import com.powersync.db.crud.CrudTransaction
import com.powersync.db.crud.UpdateType
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonElement

/**
 * A local change turned into the operation the backend needs.
 *
 * PowerSync records every write to the local database in an upload queue and
 * hands it back as [CrudEntry]s: `PUT` (insert or replace, all non-null
 * columns), `PATCH` (only the changed columns) and `DELETE`. PostgREST needs the
 * matching upsert, update and delete, which this type expresses in plain Kotlin
 * so the mapping can be unit-tested with a fake uploader.
 *
 * @param table Client-side table name; the uploader maps it to the writable
 *   columns and drops writes to read-only tables.
 * @param id Row id, used as the filter for update/delete and as the conflict
 *   target for upsert.
 * @param values Column values with their JSON types, taken from the queue so
 *   integers stay numbers and a cleared column stays an explicit null.
 */
internal data class CrudWrite(
    val table: String,
    val id: String,
    val kind: CrudWriteKind,
    val values: Map<String, JsonElement>,
)

internal enum class CrudWriteKind { UPSERT, UPDATE, DELETE }

/**
 * Maps one queued change onto a [CrudWrite], or null when nothing can be sent:
 * the table is read-only locally (`memberships`), so its writes must not be
 * uploaded at all.
 */
internal fun CrudEntry.toCrudWrite(): CrudWrite? {
    if (uploadColumns(table) == null) return null

    return when (op) {
        UpdateType.PUT ->
            CrudWrite(table = table, id = id, kind = CrudWriteKind.UPSERT, values = opValues())

        UpdateType.PATCH ->
            CrudWrite(table = table, id = id, kind = CrudWriteKind.UPDATE, values = opValues())

        UpdateType.DELETE ->
            CrudWrite(table = table, id = id, kind = CrudWriteKind.DELETE, values = emptyMap())
    }
}

/** The changes in [this] transaction that this app can upload. */
internal fun CrudTransaction.crudWrites(): List<CrudWrite> =
    crud.mapNotNull { it.toCrudWrite() }

/**
 * The write an upload failed on, with the uploader's exception as [cause].
 *
 * The table is needed to tell the user what was undone, and only the writer
 * knows it; the exception itself must not be rethrown wrapped, so that a
 * transient failure still reaches PowerSync as the original error.
 */
internal class UploadWriteFailure(
    val table: String,
    cause: Throwable,
) : Exception(cause)

/** Sends the writes to the backend, in order. */
internal suspend fun uploadWrites(
    writes: List<CrudWrite>,
    uploader: CrudUploader,
) {
    writes.forEach { write -> write.send(uploader) }
}

private suspend fun CrudWrite.send(uploader: CrudUploader) {
    try {
        when (kind) {
            CrudWriteKind.UPSERT -> uploader.upsert(table, id, values)
            CrudWriteKind.UPDATE -> uploader.update(table, id, values)
            CrudWriteKind.DELETE -> uploader.delete(table, id)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        throw UploadWriteFailure(table, error)
    }
}

private fun CrudEntry.opValues(): Map<String, JsonElement> = opData?.jsonValues.orEmpty()
