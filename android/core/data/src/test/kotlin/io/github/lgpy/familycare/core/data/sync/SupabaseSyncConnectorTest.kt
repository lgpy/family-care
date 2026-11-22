package io.github.lgpy.familycare.core.data.sync

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * The connector's upload loop: a write is only sent with a user access token.
 *
 * Without one (the session is between an expired token and a refresh that
 * failed offline) supabase-kt would send the publishable key instead, the
 * write would run as `anon` and come back as an RLS refusal; the queue must
 * wait and retry instead of discarding the change.
 */
class SupabaseSyncConnectorTest {

    private class RecordingUploader : CrudUploader {
        val calls = mutableListOf<String>()

        override suspend fun insert(
            schema: String,
            table: String,
            id: String,
            values: Map<String, JsonElement>,
            duplicate: DuplicateInsert,
        ) {
            calls += "insert:$table"
        }

        override suspend fun update(schema: String, table: String, id: String, values: Map<String, JsonElement>) {
            calls += "update:$table"
        }

        override suspend fun delete(schema: String, table: String, id: String) {
            calls += "delete:$table"
        }
    }

    @Test
    fun `without a user token nothing is sent and the queue is retried`() = runTest {
        val uploader = RecordingUploader()
        val rejections = UploadRejections()
        val completed = mutableListOf<String>()
        val queue = ArrayDeque(listOf(pending(completed)))

        try {
            uploadQueue(
                nextTransaction = { queue.firstOrNull() },
                hasUserToken = { false },
                uploader = uploader,
                rejections = rejections,
            )
            fail("a missing token must reach PowerSync as a retryable error")
        } catch (thrown: MissingUserTokenException) {
            assertFalse(UploadFailure.of(thrown).isPermanent)
        }

        assertEquals(emptyList<String>(), uploader.calls)
        assertEquals(emptyList<String>(), completed)
        assertNull(rejections.state.value)
    }

    @Test
    fun `with a user token every queued transaction is sent and completed`() = runTest {
        val uploader = RecordingUploader()
        val completed = mutableListOf<String>()
        val queue = ArrayDeque<PendingTransaction>()
        repeat(2) { queue += pending(completed) { queue.removeFirst() } }

        uploadQueue(
            nextTransaction = { queue.firstOrNull() },
            hasUserToken = { true },
            uploader = uploader,
            rejections = UploadRejections(),
        )

        assertEquals(listOf("update:$PATIENTS_TABLE", "update:$PATIENTS_TABLE"), uploader.calls)
        assertEquals(listOf("done", "done"), completed)
    }

    @Test
    fun `an empty queue does not need a token`() = runTest {
        uploadQueue(
            nextTransaction = { null },
            hasUserToken = { false },
            uploader = RecordingUploader(),
            rejections = UploadRejections(),
        )
    }

    private fun pending(
        completed: MutableList<String>,
        onComplete: () -> Unit = {},
    ) = PendingTransaction(
        writes = listOf(
            CrudWrite(
                schema = CORE_SCHEMA,
                table = PATIENTS_TABLE,
                id = "row-1",
                kind = CrudWriteKind.UPDATE,
                values = mapOf("display_name" to JsonPrimitive("Grandma")),
            ),
        ),
        complete = {
            completed += "done"
            onComplete()
        },
    )
}
