package io.github.lgpy.familycare.core.data.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mapping from queued local changes to backend operations, against a fake
 * uploader: no PowerSync, no PostgREST, no network.
 */
class ProbeWritesTest {

    private class RecordingUploader : ProbeUploader {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun upsert(id: String, values: Map<String, String?>) {
            calls += "upsert:$id" to values.toString()
        }

        override suspend fun update(id: String, values: Map<String, String?>) {
            calls += "update:$id" to values.toString()
        }

        override suspend fun delete(id: String) {
            calls += "delete:$id" to ""
        }
    }

    @Test
    fun putBecomesAnUpsertWithTheServerId() = runTest {
        val uploader = RecordingUploader()

        uploadProbeWrites(
            listOf(
                ProbeWrite(
                    id = "note-1",
                    kind = ProbeWriteKind.UPSERT,
                    values = mapOf("owner_id" to "user-1", "note" to "Hello", "created_at" to "2026-11-17T10:00:00Z"),
                ),
            ),
            uploader,
        )

        assertEquals(listOf("upsert:note-1"), uploader.calls.map { it.first })
        assertEquals(
            "{owner_id=user-1, note=Hello, created_at=2026-11-17T10:00:00Z}",
            uploader.calls.single().second,
        )
    }

    @Test
    fun patchBecomesAnUpdateFilteredById() = runTest {
        val uploader = RecordingUploader()

        uploadProbeWrites(
            listOf(ProbeWrite("note-1", ProbeWriteKind.UPDATE, mapOf("note" to "Edited"))),
            uploader,
        )

        assertEquals(listOf("update:note-1"), uploader.calls.map { it.first })
        assertEquals("{note=Edited}", uploader.calls.single().second)
    }

    @Test
    fun deleteBecomesADeleteById() = runTest {
        val uploader = RecordingUploader()

        uploadProbeWrites(listOf(ProbeWrite("note-1", ProbeWriteKind.DELETE, emptyMap())), uploader)

        assertEquals(listOf("delete:note-1"), uploader.calls.map { it.first })
    }

    @Test
    fun aTransactionUploadsInOrder() = runTest {
        val uploader = RecordingUploader()

        uploadProbeWrites(
            listOf(
                ProbeWrite("a", ProbeWriteKind.UPSERT, mapOf("note" to "first")),
                ProbeWrite("a", ProbeWriteKind.UPDATE, mapOf("note" to "second")),
                ProbeWrite("b", ProbeWriteKind.UPSERT, mapOf("note" to "third")),
            ),
            uploader,
        )

        assertEquals(listOf("upsert:a", "update:a", "upsert:b"), uploader.calls.map { it.first })
    }

    @Test
    fun nothingToUploadMeansNoCalls() = runTest {
        val uploader = RecordingUploader()

        uploadProbeWrites(emptyList(), uploader)

        assertEquals(emptyList<Pair<String, String>>(), uploader.calls)
    }
}
