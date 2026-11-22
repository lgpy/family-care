package io.github.lgpy.familycare.core.data.sync

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Whose synced data the local database holds, as last recorded. */
internal sealed interface LocalOwner {

    /**
     * No record: a fresh install, or an install upgraded from a build that did
     * not keep one. The database may hold a user's data.
     */
    data object Unrecorded : LocalOwner

    /** The database was cleared and nothing has been synced into it since. */
    data object Clean : LocalOwner

    /** The database holds (or is being filled with) this user's rows. */
    data class User(val userId: String) : LocalOwner
}

/**
 * The record of [LocalOwner], kept outside the process so a cold start knows
 * whether the local database still holds a previous user's data. An interface
 * so [SyncLifecycle] is tested with a fake.
 */
internal interface LocalOwnerStore {
    fun read(): LocalOwner

    fun write(owner: LocalOwner)
}

/**
 * [LocalOwnerStore] in a file under `Context.noBackupFilesDir`, next to the
 * wrapped database key: Android's backup never carries it to another device,
 * where it would describe a database that is not there.
 *
 * The file holds the user id (an opaque UUID, no health data) or a marker for
 * a clean database. Writes replace the file atomically, so a process killed
 * mid-write leaves the previous record. An unreadable file is [LocalOwner.Unrecorded].
 */
internal class FileLocalOwnerStore(
    private val file: File,
) : LocalOwnerStore {

    override fun read(): LocalOwner {
        if (!file.exists()) return LocalOwner.Unrecorded
        val text = file.readText().trim()
        return when {
            text == CLEAN -> LocalOwner.Clean
            text.startsWith(USER_PREFIX) && text.length > USER_PREFIX.length ->
                LocalOwner.User(text.removePrefix(USER_PREFIX))

            else -> LocalOwner.Unrecorded
        }
    }

    override fun write(owner: LocalOwner) {
        val text = when (owner) {
            LocalOwner.Unrecorded -> {
                file.delete()
                return
            }

            LocalOwner.Clean -> CLEAN
            is LocalOwner.User -> USER_PREFIX + owner.userId
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(text)
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    companion object {
        /** File name inside `Context.noBackupFilesDir`. */
        internal const val FILE_NAME: String = "sync-local-owner"

        private const val CLEAN = "clean"
        private const val USER_PREFIX = "user:"
    }
}
