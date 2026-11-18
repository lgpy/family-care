package io.github.lgpy.familycare.core.data.sync

import io.github.lgpy.familycare.core.domain.sync.RejectedChange
import io.github.lgpy.familycare.core.domain.sync.RejectedUpload
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The latest local change the server permanently refused, held in memory for
 * the life of the process.
 *
 * Only an explicit [acknowledge] clears it: a later successful upload must not,
 * because the row that was refused is already gone and the user has not been
 * told yet. Signing out clears it through the sync service, because the message
 * belongs to the session that queued the change.
 */
internal class UploadRejections {

    private val _state = MutableStateFlow<RejectedUpload?>(null)

    /** The unacknowledged refusal, or null when there is nothing to show. */
    val state: StateFlow<RejectedUpload?> = _state.asStateFlow()

    /**
     * Records a permanent refusal, replacing an earlier unacknowledged one:
     * the banner describes the latest loss, never a stale one.
     */
    fun record(rejection: RejectedUpload) {
        _state.value = rejection
    }

    /** The user has seen and dismissed the message. */
    fun acknowledge() {
        _state.value = null
    }
}

/**
 * Sends one queued transaction and completes it, whether it was accepted or
 * permanently refused.
 *
 * Extracted from [SupabaseSyncConnector] so the policy is unit-tested with a
 * fake uploader and no PowerSync types:
 *
 * - success: complete the transaction and leave any earlier refusal alone;
 * - transient failure (offline, 5xx, token refresh, rate limit): rethrow the
 *   original error so PowerSync retries, and record nothing;
 * - permanent failure (policy, constraint, malformed request): record a coarse
 *   [RejectedUpload] and complete the transaction anyway, so one refused row
 *   cannot wedge the queue behind it.
 *
 * @param complete Completes the PowerSync transaction (a suspend call in the
 *   SDK). Called exactly once per call that returns normally, including after a
 *   permanent refusal.
 * @param failureOf How an uploader exception is classified; a parameter so the
 *   tests can present a Postgres SQLSTATE without building a PostgREST
 *   response.
 */
internal suspend fun uploadTransaction(
    writes: List<CrudWrite>,
    uploader: CrudUploader,
    rejections: UploadRejections,
    complete: suspend () -> Unit,
    failureOf: (Throwable) -> UploadFailure = { UploadFailure.of(it) },
) {
    try {
        uploadWrites(writes, uploader)
        complete()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        val failedWrite = error as? UploadWriteFailure
        val cause = failedWrite?.cause ?: error
        val failure = failureOf(cause)
        if (!failure.isPermanent) throw cause

        rejections.record(
            RejectedUpload(
                change = rejectedChangeOf(failedWrite?.table),
                reason = failure.rejectionReason(),
            ),
        )
        complete()
    }
}

/** The user-facing target of a refused write; unknown tables are a fallback. */
private fun rejectedChangeOf(table: String?): RejectedChange = when (table) {
    PATIENTS_TABLE -> RejectedChange.PATIENT
    PROFILES_TABLE -> RejectedChange.PROFILE
    CARE_CIRCLES_TABLE -> RejectedChange.CARE_CIRCLE
    else -> RejectedChange.OTHER
}
