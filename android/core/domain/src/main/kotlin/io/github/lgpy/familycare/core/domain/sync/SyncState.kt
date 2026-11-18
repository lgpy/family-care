package io.github.lgpy.familycare.core.domain.sync

import kotlin.time.Instant

/**
 * Which kind of local change the server refused. Coarse on purpose: the UI only
 * needs to say what was lost, never which row it was (security-and-privacy §4).
 */
enum class RejectedChange {
    /** A patient row (add, edit or archive). */
    PATIENT,

    /** The signed-in user's profile, shown as "your name". */
    PROFILE,

    /** A care circle's name. */
    CARE_CIRCLE,

    /** A table this app does not know about; only used as a safe fallback. */
    OTHER,
}

/** Why the server refused a change, in the coarsest useful terms. */
enum class RejectionReason {
    /** A policy refused the change (Postgres `42501`, an RLS denial). */
    NOT_ALLOWED,

    /** The data itself is not acceptable (a class 22/23 constraint failure). */
    INVALID_DATA,

    /** Any other permanent refusal. */
    OTHER,
}

/**
 * A local change the server permanently refused, so the upload queue dropped
 * it and the row was undone on the next sync.
 *
 * Deliberately carries no values, no ids and no server message: the UI needs to
 * say that something was undone and why in general terms, nothing more.
 */
data class RejectedUpload(
    val change: RejectedChange,
    val reason: RejectionReason,
)

/**
 * What the sync layer is doing, for the screens that show a sync indicator
 * (build-plan M1). Pure Kotlin so the UI never imports PowerSync.
 *
 * @param connected The PowerSync connection is ready to download, and local
 *   changes may be uploaded.
 * @param connecting The connection is being established.
 * @param uploading Local changes are being uploaded right now.
 * @param downloading Server changes are being downloaded right now.
 * @param hasSynced Whether the first full download has completed; null while
 *   unknown (for example before the local database has been opened).
 * @param lastSyncedAt When a sync last completed, if it ever has. Only
 *   meaningful for the life of the process (PowerSync reports it per run).
 * @param hasError Something failed and the user may want to retry later. The
 *   flag deliberately carries no message: row data and tokens never reach the
 *   UI or the logs (security-and-privacy §4).
 * @param rejectedUpload The last change the server permanently refused and the
 *   queue therefore discarded. Unlike a transient error this is an event the
 *   user must be told about; it stays set until the user acknowledges it, even
 *   after a later upload succeeds. Null when there is nothing to acknowledge.
 */
data class SyncState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val uploading: Boolean = false,
    val downloading: Boolean = false,
    val hasSynced: Boolean? = null,
    val lastSyncedAt: Instant? = null,
    val hasError: Boolean = false,
    val rejectedUpload: RejectedUpload? = null,
)
