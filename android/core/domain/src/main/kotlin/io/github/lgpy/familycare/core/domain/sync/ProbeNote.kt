package io.github.lgpy.familycare.core.domain.sync

/**
 * One row of the M1 probe table (`core.sync_probe`), as the rest of the app
 * sees it (build-plan M1: "one test table synced through PowerSync").
 *
 * Pure Kotlin: the UI and the tests read notes without depending on PowerSync,
 * SQLite or the network. Timestamps stay ISO-8601 text because that is the form
 * the local database and PostgREST exchange; a screen that wants to format them
 * can parse them without this module taking a date-time dependency.
 *
 * @param id Server-generated uuid (created on the phone with `uuid()`).
 * @param text The note, 1..500 characters (the database enforces the same).
 * @param createdAt ISO-8601 UTC, from the phone that created the note.
 * @param updatedAt ISO-8601 UTC, from the database trigger, so it never trusts
 *   a phone clock. Null until the server stamps a note this phone just wrote.
 */
data class ProbeNote(
    val id: String,
    val text: String,
    val createdAt: String,
    val updatedAt: String?,
)
