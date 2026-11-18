package io.github.lgpy.familycare.core.data.sync

import io.github.lgpy.familycare.core.domain.sync.RejectedChange
import io.github.lgpy.familycare.core.domain.sync.RejectedUpload
import io.github.lgpy.familycare.core.domain.sync.RejectionReason
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * What one queued transaction does when the server refuses it: the queue keeps
 * moving, the lost change is recorded once, and a transient failure stays a
 * retry. No PowerSync, no network.
 */
class UploadTransactionTest {

    private class FailingUploader(private val error: Exception? = null) : CrudUploader {
        val calls = mutableListOf<String>()

        override suspend fun upsert(table: String, id: String, values: Map<String, JsonElement>) {
            calls += "upsert:$table"
            error?.let { throw it }
        }

        override suspend fun update(table: String, id: String, values: Map<String, JsonElement>) {
            calls += "update:$table"
            error?.let { throw it }
        }

        override suspend fun delete(table: String, id: String) {
            calls += "delete:$table"
            error?.let { throw it }
        }
    }

    @Test
    fun `a policy refusal records the table and completes the transaction`() = runTest {
        val rejections = UploadRejections()
        val completed = mutableListOf<String>()

        uploadTransaction(
            writes = listOf(write(PATIENTS_TABLE)),
            uploader = FailingUploader(IOException("refused")),
            rejections = rejections,
            complete = { completed += "done" },
            failureOf = { UploadFailure(statusCode = 403, postgresCode = "42501") },
        )

        assertEquals(
            RejectedUpload(change = RejectedChange.PATIENT, reason = RejectionReason.NOT_ALLOWED),
            rejections.state.value,
        )
        assertEquals(listOf("done"), completed)
    }

    @Test
    fun `each uploadable table maps to what the user sees`() = runTest {
        assertEquals(RejectedChange.PATIENT, refusalOf(PATIENTS_TABLE).change)
        assertEquals(RejectedChange.PROFILE, refusalOf(PROFILES_TABLE).change)
        assertEquals(RejectedChange.CARE_CIRCLE, refusalOf(CARE_CIRCLES_TABLE).change)
        // A table this app does not own can never be uploaded, but the mapping
        // must still be total.
        assertEquals(RejectedChange.OTHER, refusalOf("doses").change)
    }

    @Test
    fun `a constraint failure is reported as invalid data`() = runTest {
        val refusal = refusalOf(
            PROFILES_TABLE,
            failureOf = { UploadFailure(statusCode = 409, postgresCode = "23502") },
        )

        assertEquals(RejectionReason.INVALID_DATA, refusal.reason)
    }

    @Test
    fun `a transient failure is rethrown, records nothing and does not complete`() = runTest {
        val rejections = UploadRejections()
        val offline = IOException("no route to host")
        val completed = mutableListOf<String>()

        try {
            uploadTransaction(
                writes = listOf(write(PATIENTS_TABLE)),
                uploader = FailingUploader(offline),
                rejections = rejections,
                complete = { completed += "done" },
            )
            fail("the transient failure must reach PowerSync")
        } catch (thrown: IOException) {
            // The original error, not the wrapper: PowerSync's retry path sees
            // the same exception the uploader threw.
            assertSame(offline, thrown)
        }

        assertNull(rejections.state.value)
        assertEquals(emptyList<String>(), completed)
    }

    @Test
    fun `a later success keeps the refusal until it is acknowledged`() = runTest {
        val rejections = UploadRejections()
        val refusal = RejectedUpload(
            change = RejectedChange.PATIENT,
            reason = RejectionReason.NOT_ALLOWED,
        )

        uploadTransaction(
            writes = listOf(write(PATIENTS_TABLE)),
            uploader = FailingUploader(IOException("refused")),
            rejections = rejections,
            complete = {},
            failureOf = { UploadFailure(statusCode = 403, postgresCode = "42501") },
        )
        // The next transaction uploads fine; the lost change is still unread.
        uploadTransaction(
            writes = listOf(write(PATIENTS_TABLE)),
            uploader = FailingUploader(),
            rejections = rejections,
            complete = {},
        )

        assertEquals(refusal, rejections.state.value)

        rejections.acknowledge()

        assertNull(rejections.state.value)
    }

    private suspend fun refusalOf(
        table: String,
        failureOf: (Throwable) -> UploadFailure = { UploadFailure(statusCode = 403, postgresCode = "42501") },
    ): RejectedUpload {
        val rejections = UploadRejections()
        uploadTransaction(
            writes = listOf(write(table)),
            uploader = FailingUploader(IOException("refused")),
            rejections = rejections,
            complete = {},
            failureOf = failureOf,
        )
        return requireNotNull(rejections.state.value)
    }

    private fun write(table: String) = CrudWrite(
        table = table,
        id = "row-1",
        kind = CrudWriteKind.UPDATE,
        values = mapOf("display_name" to kotlinx.serialization.json.JsonPrimitive("Grandma")),
    )
}
