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
 * matching insert, update and delete, which this type expresses in plain Kotlin
 * so the mapping can be unit-tested with a fake uploader.
 *
 * @param schema The PostgREST schema of the table (`core`, `meds`).
 * @param table Client-side table name; the uploader maps it to the writable
 *   columns and drops writes the table's policy does not allow.
 * @param id Row id, used as the filter for update/delete and as the conflict
 *   target for insert.
 * @param values Column values with their JSON types, taken from the queue so
 *   integers stay numbers and a cleared column stays an explicit null.
 * @param duplicateInsert What a unique violation on the insert means (see
 *   [DuplicateInsert]).
 */
internal data class CrudWrite(
    val schema: String,
    val table: String,
    val id: String,
    val kind: CrudWriteKind,
    val values: Map<String, JsonElement>,
    val duplicateInsert: DuplicateInsert = DuplicateInsert.ALREADY_APPLIED,
)

internal enum class CrudWriteKind { INSERT, UPDATE, DELETE }

/**
 * Maps one queued change onto a [CrudWrite], or null when nothing can be sent:
 * the table is not synced, or the write is one its policy does not allow.
 *
 * The policy is what keeps append-only and versioned tables honest: a stray
 * local `PATCH` on `dose_log` (no UPDATE grant on the server) or a `PATCH` that
 * touches anything but a regimen's `end_date` is dropped here instead of being
 * uploaded, refused and shown to the user as a permanent error.
 */
internal fun CrudEntry.toCrudWrite(tables: TableCatalog): CrudWrite? {
    val synced = tables.syncedTable(table) ?: return null
    val upload = synced.upload
    val values = opData?.jsonValues.orEmpty()

    return when (op) {
        UpdateType.PUT ->
            if (upload.insertColumns.isEmpty()) {
                null
            } else {
                CrudWrite(
                    schema = synced.schema,
                    table = table,
                    id = id,
                    kind = CrudWriteKind.INSERT,
                    values = values,
                    duplicateInsert = upload.duplicateInsert,
                )
            }

        UpdateType.PATCH ->
            if (upload.updateColumns.isEmpty()) {
                null
            } else {
                CrudWrite(
                    schema = synced.schema,
                    table = table,
                    id = id,
                    kind = CrudWriteKind.UPDATE,
                    values = values,
                )
            }

        UpdateType.DELETE ->
            if (!upload.deleteAllowed) {
                null
            } else {
                CrudWrite(
                    schema = synced.schema,
                    table = table,
                    id = id,
                    kind = CrudWriteKind.DELETE,
                    values = emptyMap(),
                )
            }
    }
}

/** The changes in [this] transaction that this app can upload. */
internal fun CrudTransaction.crudWrites(tables: TableCatalog): List<CrudWrite> =
    crud.mapNotNull { it.toCrudWrite(tables) }

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
            CrudWriteKind.INSERT -> uploader.insert(schema, table, id, values, duplicateInsert)
            CrudWriteKind.UPDATE -> uploader.update(schema, table, id, values)
            CrudWriteKind.DELETE -> uploader.delete(schema, table, id)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        throw UploadWriteFailure(table, error)
    }
}
