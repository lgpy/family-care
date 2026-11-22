package io.github.lgpy.familycare.core.data.sync

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

/** The on-disk record of whose data the local database holds. */
class FileLocalOwnerStoreTest {

    private val dir: File = Files.createTempDirectory("owner-store").toFile().apply { deleteOnExit() }
    private val file = File(dir, FileLocalOwnerStore.FILE_NAME)

    @Test
    fun aMissingFileIsUnrecorded() {
        assertEquals(LocalOwner.Unrecorded, FileLocalOwnerStore(file).read())
    }

    @Test
    fun theRecordSurvivesANewInstance() {
        FileLocalOwnerStore(file).write(LocalOwner.User("user-a"))
        assertEquals(LocalOwner.User("user-a"), FileLocalOwnerStore(file).read())

        FileLocalOwnerStore(file).write(LocalOwner.Clean)
        assertEquals(LocalOwner.Clean, FileLocalOwnerStore(file).read())
    }

    @Test
    fun anUnreadableRecordIsUnrecorded() {
        file.writeText("something else")
        assertEquals(LocalOwner.Unrecorded, FileLocalOwnerStore(file).read())
    }
}
