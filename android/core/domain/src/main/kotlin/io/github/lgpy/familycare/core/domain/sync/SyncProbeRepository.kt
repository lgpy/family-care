package io.github.lgpy.familycare.core.domain.sync

import kotlinx.coroutines.flow.Flow

/**
 * The M1 probe notes (build-plan M1: "one test table synced through PowerSync,
 * encrypted locally, protected by Row-Level Security").
 *
 * Offline-first (D3): [add] and [delete] write to the local database and never
 * wait for the network. A note written in airplane mode is uploaded and appears
 * on the other phones once the connection returns.
 *
 * The implementation lives in `core/data`; this interface keeps PowerSync out of
 * every module that just wants to read or write a note.
 */
interface SyncProbeRepository {

    /**
     * The signed-in user's notes, newest first. Emits again whenever the local
     * database changes (a local write, or a change synced down from the server).
     * Emits an empty list while signed out.
     */
    val notes: Flow<List<ProbeNote>>

    /**
     * Stores a note for the signed-in user.
     *
     * @param text 1..500 characters after trimming; the note is trimmed before
     *   it is stored.
     * @throws IllegalArgumentException if the text is blank or too long.
     */
    suspend fun add(text: String)

    /** Removes the note with [id], if it is still there. */
    suspend fun delete(id: String)

    companion object {
        /** Mirrors the `sync_probe_note_length` check constraint on the server. */
        const val MAX_TEXT_LENGTH: Int = 500

        /** Whether [text] can be stored, using the same rule as [add]. */
        fun isValidText(text: String): Boolean {
            val trimmed = text.trim()
            return trimmed.length in 1..MAX_TEXT_LENGTH
        }
    }
}
