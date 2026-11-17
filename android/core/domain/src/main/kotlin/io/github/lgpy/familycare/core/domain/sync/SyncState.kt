package io.github.lgpy.familycare.core.domain.sync

import kotlin.time.Instant

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
 */
data class SyncState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val uploading: Boolean = false,
    val downloading: Boolean = false,
    val hasSynced: Boolean? = null,
    val lastSyncedAt: Instant? = null,
    val hasError: Boolean = false,
)
